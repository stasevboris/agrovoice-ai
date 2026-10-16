"""Revision-pinned DEIMv2-N transfer study. One GPU worker; atomic epoch checkpoints."""
from __future__ import annotations
import argparse,copy,json,math,os,random,sys,time
from pathlib import Path
import numpy as np
from PIL import Image,ImageOps
import torch
from torch import nn
from torch.utils.data import Dataset,DataLoader
from torchvision.transforms import ColorJitter,functional as TF
from accuracy_study import ROOT,REPORT,checksum,read,save,stamp,atomic_torch,seed_everything

SOURCE=ROOT/'ml-pipeline/third_party/deimv2'
sys.path.insert(0,str(SOURCE))
from engine.core import YAMLConfig
from safetensors.torch import load_file
from faster_coco_eval import COCO,COCOeval_faster

DATA=ROOT/'ml-pipeline/dataset_accuracy_v1'
IMAGES=ROOT/'ml-pipeline/dataset_clean_v2/images'
RUNS=ROOT/'runs/accuracy_v1'
WEIGHTS=ROOT/'ml-pipeline/runtime/detector_weights/deimv2_n_model.safetensors'
SIDE=416

def geometry(w,h):
    ratio=min(SIDE/w,SIDE/h);rw=max(1,round(w*ratio));rh=max(1,round(h*ratio))
    return rw,rh,(SIDE-rw)//2,(SIDE-rh)//2

class Frames(Dataset):
    def __init__(self,split,train=False):
        self.split=split;self.train=train;self.annotations=read(DATA/f'coco_{split}.json')
        self.images=self.annotations['images'];self.boxes={r['id']:[] for r in self.images}
        for box in self.annotations['annotations']:self.boxes[box['image_id']].append(box)
        self.jitter=ColorJitter(.12,.12,.08,.01)
    def __len__(self):return len(self.images)
    def __getitem__(self,index):
        row=self.images[index];w,h=row['width'],row['height']
        with Image.open(IMAGES/self.split/row['file_name']) as f:image=f.convert('RGB')
        assert image.size==(w,h)
        annotations=self.boxes[row['id']]
        boxes=np.array([a['bbox'] for a in annotations],dtype=np.float32).reshape(-1,4)
        boxes[:,2:]+=boxes[:,:2]
        if self.train:
            image=self.jitter(image)
            if random.random()<.5:
                image=ImageOps.mirror(image);left=boxes[:,0].copy();boxes[:,0]=w-boxes[:,2];boxes[:,2]=w-left
        rw,rh,px,py=geometry(w,h)
        frame=Image.new('RGB',(SIDE,SIDE),(114,114,114));frame.paste(image.resize((rw,rh),Image.Resampling.BILINEAR),(px,py))
        boxes[:,[0,2]]=boxes[:,[0,2]]*(rw/w)+px;boxes[:,[1,3]]=boxes[:,[1,3]]*(rh/h)+py
        wh=boxes[:,2:]-boxes[:,:2];cxcy=(boxes[:,:2]+boxes[:,2:])/2
        target={'boxes':torch.from_numpy(np.concatenate((cxcy,wh),1)/SIDE),
                'labels':torch.tensor([a['category_id'] for a in annotations],dtype=torch.long),
                'image_id':torch.tensor(row['id']),'orig_size':torch.tensor([w,h]),'size':torch.tensor([SIDE,SIDE])}
        return TF.to_tensor(frame),target

def collate(batch):return torch.stack([x for x,_ in batch]),[y for _,y in batch]

def make_config():
    cfg=YAMLConfig(str(SOURCE/'configs/deimv2/deimv2_hgnetv2_n_coco.yml'))
    cfg.yaml_cfg['HGNetv2']['pretrained']=False
    cfg.yaml_cfg.update(num_classes=30,remap_mscoco_category=False,eval_spatial_size=[SIDE,SIDE])
    cfg.yaml_cfg['DEIMCriterion']['matcher']['matcher_change_epoch']=50
    return cfg

def transfer(net):
    source=load_file(str(WEIGHTS));target=net.state_dict()
    accepted={k:v for k,v in source.items() if k in target and target[k].shape==v.shape}
    rejected=[k for k in source if k not in accepted]
    report=net.load_state_dict(accepted,strict=False)
    # Require successful backbone transfer; prevent silently training an incompatible model from scratch.
    assert any(k.startswith('backbone.') for k in accepted) and len(accepted)>len(target)*.75
    return {'accepted':len(accepted),'source_tensors':len(source),'skipped':rejected,'missing':report.missing_keys,
            'weights_sha256':checksum(WEIGHTS)}

@torch.no_grad()
def evaluate(net,post,loader,split,device):
    net.eval();predictions=[]
    for x,targets in loader:
        outputs=net(x.to(device));sizes=torch.full((len(x),2),SIDE,device=device,dtype=torch.int64)
        results=post(outputs,sizes)
        for t,result in zip(targets,results):
            w,h=t['orig_size'].tolist();rw,rh,px,py=geometry(w,h)
            boxes=result['boxes'].cpu().numpy();labels=result['labels'].cpu().numpy();scores=result['scores'].cpu().numpy()
            boxes[:,[0,2]]=(boxes[:,[0,2]]-px)/(rw/w);boxes[:,[1,3]]=(boxes[:,[1,3]]-py)/(rh/h)
            boxes[:,[0,2]]=boxes[:,[0,2]].clip(0,w);boxes[:,[1,3]]=boxes[:,[1,3]].clip(0,h)
            for b,c,s in zip(boxes,labels,scores):
                if float(s)<.001 or b[2]<=b[0] or b[3]<=b[1]:continue
                predictions.append({'image_id':int(t['image_id']),'category_id':int(c),'score':float(s),
                                    'bbox':[float(b[0]),float(b[1]),float(b[2]-b[0]),float(b[3]-b[1])]})
    gt=COCO(str(DATA/f'coco_{split}.json'))
    if not predictions:return {'map50_95':0.,'map50':0.,'predictions':0}
    dt=gt.loadRes(predictions);ev=COCOeval_faster(gt,dt,'bbox');ev.params.maxDets=[1,10,300]
    ev.evaluate();ev.accumulate();ev.summarize()
    p=ev.eval['precision'];byclass=[]
    for i,c in enumerate(ev.params.catIds):
        a=p[:,:,i,0,-1];a50=p[0,:,i,0,-1]
        byclass.append({'id':int(c),'map50_95':float(a[a>=0].mean()) if (a>=0).any() else None,
                        'map50':float(a50[a50>=0].mean()) if (a50>=0).any() else None})
    # Compute AP directly at the requested maxDets, independent of summary's legacy maxDets=100 lookup.
    a=p[:,:,:,0,-1];a50=p[0,:,:,0,-1]
    return {'map50_95':float(a[a>=0].mean()),'map50':float(a50[a50>=0].mean()),'classes':byclass,
            'predictions':len(predictions),'evaluator':'faster-coco-eval bbox, maxDets=300; no NMS',
            'split':split,'images':len(loader.dataset)}

class Deploy(nn.Module):
    def __init__(self,net,post):
        super().__init__();self.net=net.deploy();self.post=post.deploy()
        self.register_buffer('sizes',torch.tensor([[SIDE,SIDE]],dtype=torch.int64))
    def forward(self,images):
        labels,boxes,scores=self.post(self.net(images),self.sizes)
        return torch.cat((boxes,scores.unsqueeze(-1),labels.to(boxes.dtype).unsqueeze(-1)),dim=-1)

def export(net,post,run,full_validation=True):
    import onnxruntime as ort
    torch.set_num_threads(2)
    native=copy.deepcopy(net.cpu().eval());native_post=copy.deepcopy(post.cpu().eval())
    deploy=Deploy(net.cpu().eval(),post.cpu().eval()).eval();frames=Frames('val')
    example=frames[0][0][None];path=run/'model.onnx'
    torch.onnx.export(deploy,example,str(path),opset_version=17,dynamo=False,input_names=['images'],output_names=['detections'])
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(path),sess_options=options,providers=['CPUExecutionProvider']);comparisons=[]
    for i in range(3):
        value=frames[i][0][None]
        with torch.no_grad():expected=deploy(value).numpy()
        actual=session.run(None,{'images':value.numpy()})[0]
        # TopK can reorder nearly equal scores. Require one-to-one class/box/score parity.
        from scipy.optimize import linear_sum_assignment
        reference=expected[0];candidate=actual[0]
        distance=np.abs(reference[:,None,:5]-candidate[None,:,:5]).max(2)
        cost=distance+(reference[:,None,5]!=candidate[None,:,5])*1e6
        rows,cols=linear_sum_assignment(cost);aligned=candidate[cols]
        maximum=float(np.abs(reference-aligned).max())
        if not full_validation and not np.allclose(reference,aligned,atol=.003,rtol=1e-4):
            np.save(run/'parity_expected.npy',expected);np.save(run/'parity_actual.npy',actual)
            raise RuntimeError(f'DEIM graph differs after class/box matching: {maximum}')
        comparisons.append({'file':frames.images[i]['file_name'],'matched_max_absolute_error':maximum,
                            'rowwise_max_absolute_error':float(np.abs(expected-actual).max()),
                            'raw_strict_pass':bool(np.allclose(reference,aligned,atol=.003,rtol=1e-4))})
    validation=None
    if full_validation:
        from deim_export_validation import validate
        validation=validate(native,native_post,deploy,frames,path,DATA/'coco_val.json',geometry,SIDE)
        save(run/'export_validation.json',validation)
        assert validation['status']=='passed',('DEIM visible detections/AP differ',validation['visible_failed_images'],validation['maximum_ap_delta'])
    save(run/'export.json',{'status':'passed','kind':'deimv2_n','sha256':checksum(path),'bytes':path.stat().st_size,
        'input_shape':[1,3,SIDE,SIDE],'output_shape':[1,300,6],
        'input':'RGB float32 /255, proportional letterbox 114; no ImageNet normalization',
        'output':'xyxy in padded 416 frame, score, class ID; no NMS','comparisons':comparisons,
        'export_code_sha256':checksum(__file__),'validation':str(run/'export_validation.json') if validation else None,
        'parity_policy':validation['policy'] if validation else 'Strict raw output parity on three CPU probe images',
        'scope':'Complete val visible-detection parity and full AP stability; raw background TopK differences recorded. Android and shared end-to-end assessment pending' if validation else 'Untrained CPU probe; raw graph parity'})

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--seed',type=int,default=20261003)
    parser.add_argument('--stop-file',type=Path);parser.add_argument('--cpu-probe',action='store_true')
    parser.add_argument('--export-only',action='store_true')
    args=parser.parse_args();torch.set_num_threads(2 if args.cpu_probe or args.export_only else 8)
    cfg=make_config();net=cfg.model;initial=transfer(net)
    if args.export_only:
        run=RUNS/f'deimv2_n_seed{args.seed}'
        last=torch.load(run/'last.pt',map_location='cpu',weights_only=False)
        assert last['epoch']==60 and last['dataset_sha256']==checksum(DATA/'manifest.json')
        state=torch.load(run/'best.pt',map_location='cpu',weights_only=False);net.load_state_dict(state['model'])
        export(net,cfg.postprocessor,run)
        manifest=read(run/'manifest.json');manifest.update(status='completed',ended_utc=stamp(),
            best_validation_score=last['best'],export_code_sha256=checksum(__file__))
        save(run/'manifest.json',manifest);return
    if args.cpu_probe:
        net.eval()
        with torch.no_grad():out=net(torch.zeros(1,3,SIDE,SIDE))
        frames=Frames('train',True);x,targets=collate([frames[0],frames[1]])
        net.train();criterion=cfg.criterion
        losses=criterion(net(x,targets=targets),targets,epoch=0,step=0,global_step=0,epoch_step=1)
        loss=sum(losses.values());assert torch.isfinite(loss).item();loss.backward()
        gradients=sum(p.grad is not None and torch.isfinite(p.grad).all().item() for p in net.parameters())
        assert gradients>100,gradients
        probe=ROOT/'ml-pipeline/runtime/probes/deimv2_n';probe.mkdir(parents=True,exist_ok=True)
        export(net,cfg.postprocessor,probe,full_validation=False)
        report={'status':'passed','device':'CPU only','transfer':initial,
                'training_probe_loss':float(loss.detach()),'parameters_with_finite_gradient':gradients,
                'export_probe':read(probe/'export.json'),'note':'Untrained 30-class head, this graph is never packaged into APK',
                'parameters':sum(p.numel() for p in net.parameters()),'outputs':{k:list(v.shape) for k,v in out.items() if isinstance(v,torch.Tensor)}}
        save(REPORT/'deim_cpu_probe.json',report);print(json.dumps(report));return
    assert torch.cuda.is_available(),'ROCm GPU unavailable'
    seed_everything(args.seed)
    # Recreate the random 30-class head after seeding; source COCO tensors retain their original values.
    cfg=make_config();net=cfg.model;initial=transfer(net);net.to('cuda:0');criterion=cfg.criterion.to('cuda:0');post=cfg.postprocessor
    run=RUNS/f'deimv2_n_seed{args.seed}';run.mkdir(parents=True,exist_ok=True)
    if (run/'export.json').exists() and read(run/'export.json')['status']=='passed':return
    generator=torch.Generator().manual_seed(args.seed)
    train=DataLoader(Frames('train',True),batch_size=8,shuffle=True,generator=generator,num_workers=4,pin_memory=True,collate_fn=collate)
    val=DataLoader(Frames('val'),batch_size=8,shuffle=False,num_workers=4,pin_memory=True,collate_fn=collate)
    backbone=[p for n,p in net.named_parameters() if n.startswith('backbone.')]
    other=[p for n,p in net.named_parameters() if not n.startswith('backbone.')]
    optimizer=torch.optim.AdamW([{'params':backbone,'lr':.0001},{'params':other,'lr':.0002}],weight_decay=.0001)
    scheduler=torch.optim.lr_scheduler.CosineAnnealingLR(optimizer,T_max=60)
    start=0;best=-1.;fp=checksum(DATA/'manifest.json');history=[]
    if (run/'last.pt').exists():
        state=torch.load(run/'last.pt',map_location='cuda:0',weights_only=False);assert state['dataset_sha256']==fp
        net.load_state_dict(state['model']);optimizer.load_state_dict(state['optimizer']);scheduler.load_state_dict(state['scheduler'])
        start=state['epoch'];best=state['best'];generator.set_state(state['loader_rng'].cpu())
        torch.set_rng_state(state['torch_rng'].cpu());torch.cuda.set_rng_state(state['gpu_rng'].cpu())
        random.setstate(state['python_rng']);np.random.set_state(state['numpy_rng']);history=read(run/'history.json')
    if start==60:
        state=torch.load(run/'best.pt',map_location='cpu',weights_only=False);net.cpu().load_state_dict(state['model']);export(net,post,run)
        manifest=read(run/'manifest.json');manifest.update(status='completed',ended_utc=stamp(),
            best_validation_score=best,export_code_sha256=checksum(__file__))
        save(run/'manifest.json',manifest);return
    save(run/'manifest.json',{'status':'training','seed':args.seed,'kind':'deimv2_n','epochs':60,'batch':8,'input':SIDE,
        'dataset_sha256':fp,'source_code':read(REPORT/'third_party_sources.json'),'transfer':initial,'amp':False,
        'recipe':'AdamW cosine, backbone 1e-4/head 2e-4, flip/color; simplified transfer recipe, not original COCO dense-augmentation schedule',
        'code_sha256':checksum(__file__),'parameters':sum(p.numel() for p in net.parameters()),'started_utc':stamp()})
    for epoch in range(start,60):
        began=time.monotonic();net.train();criterion.train();total=0;seen=0
        for step,(x,targets) in enumerate(train):
            targets=[{k:v.to('cuda:0') for k,v in t.items()} for t in targets];x=x.to('cuda:0')
            optimizer.zero_grad(set_to_none=True);outputs=net(x,targets=targets)
            losses=criterion(outputs,targets,epoch=epoch,step=step,global_step=epoch*len(train)+step,epoch_step=len(train))
            loss=sum(losses.values());assert torch.isfinite(loss).item(),'Non-finite DETR loss'
            loss.backward();nn.utils.clip_grad_norm_(net.parameters(),1.);optimizer.step();total+=float(loss.detach())*len(x);seen+=len(x)
        scheduler.step();metrics=evaluate(net,post,val,'val','cuda:0');score=metrics['map50_95'];improved=score>best;best=max(best,score)
        history.append({'epoch':epoch+1,'loss':total/seen,'validation':metrics,'seconds':time.monotonic()-began});save(run/'history.json',history)
        checkpoint={'model':net.state_dict(),'optimizer':optimizer.state_dict(),'scheduler':scheduler.state_dict(),'epoch':epoch+1,
            'best':best,'dataset_sha256':fp,'seed':args.seed,'metrics':metrics,'loader_rng':generator.get_state(),
            'torch_rng':torch.get_rng_state(),'gpu_rng':torch.cuda.get_rng_state(),'python_rng':random.getstate(),'numpy_rng':np.random.get_state()}
        atomic_torch(run/'last.pt',checkpoint)
        if improved:atomic_torch(run/'best.pt',checkpoint)
        save(REPORT/'detector_progress.json',{'job':run.name,'epoch':epoch+1,'epochs':60,'best_map50_95':best,'updated_utc':stamp()})
        print(f'{run.name}: epoch {epoch+1}/60 AP={score:.4f} loss={total/seen:.4f}',flush=True)
        if args.stop_file and args.stop_file.exists():return
    state=torch.load(run/'best.pt',map_location='cpu',weights_only=False);net.cpu().load_state_dict(state['model']);export(net,post,run)
    manifest=read(run/'manifest.json');manifest.update(status='completed',ended_utc=stamp(),best_validation_score=best);save(run/'manifest.json',manifest)

if __name__=='__main__':main()
