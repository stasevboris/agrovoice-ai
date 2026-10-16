"""Sequential, resumable architecture experiments; one GPU process for all jobs."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import random
import time
from datetime import datetime,timezone
from pathlib import Path
import numpy as np
from PIL import Image,ImageOps
import torch
from torch import nn
from torch.utils.data import Dataset,DataLoader,WeightedRandomSampler
from torchvision import models,transforms

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'ml-pipeline/dataset_accuracy_v1'
REPORT=ROOT/'docs/experiments/accuracy_v1'
RUNS=ROOT/'runs/accuracy_v1'

def stamp():return datetime.now(timezone.utc).isoformat()
def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def save(p,obj):
    p=Path(p);p.parent.mkdir(parents=True,exist_ok=True);tmp=p.with_suffix(p.suffix+'.tmp')
    tmp.write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8');os.replace(tmp,p)
def checksum(p):
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def atomic_torch(p,obj):
    p=Path(p);tmp=p.with_suffix('.tmp');torch.save(obj,tmp);os.replace(tmp,p)

class Square:
    def __call__(self,image):
        side=max(image.size);canvas=Image.new(image.mode,(side,side),114 if image.mode=='L' else (114,114,114))
        if image.mode=='L':canvas=Image.new('L',(side,side),0)
        canvas.paste(image,((side-image.width)//2,(side-image.height)//2))
        return canvas.resize((224,224),Image.Resampling.NEAREST if image.mode=='L' else Image.Resampling.BILINEAR)

class Leaves(Dataset):
    def __init__(self,rows,train=False,mask=False):
        self.rows=rows;self.train=train;self.mask=mask
        self.colour=transforms.ColorJitter(.12,.12,.08,.01)
    def __len__(self):return len(self.rows)
    def __getitem__(self,index):
        row=self.rows[index]
        with Image.open(DATA/row['file']) as f:image=Square()(f.convert('RGB'))
        mask=None
        if self.mask:
            with Image.open(DATA/row['mask']) as f:mask=Square()(f.convert('L'))
        if self.train:
            if random.random()<.5:
                image=ImageOps.mirror(image)
                if mask is not None:mask=ImageOps.mirror(mask)
            if random.random()<.2:
                image=ImageOps.flip(image)
                if mask is not None:mask=ImageOps.flip(mask)
            image=self.colour(image)
        tensor=transforms.functional.to_tensor(image)
        return tensor,transforms.functional.to_tensor(mask) if mask is not None else row['class_id']

class Classifier(nn.Module):
    def __init__(self,kind,classes=30,pretrained=False):
        super().__init__()
        if kind=='efficientnet_b0':
            self.network=models.efficientnet_b0(weights=models.EfficientNet_B0_Weights.DEFAULT if pretrained else None)
            self.network.classifier[1]=nn.Linear(self.network.classifier[1].in_features,classes)
        elif kind=='teacher_efficientnet_v2_s':
            self.network=models.efficientnet_v2_s(weights=models.EfficientNet_V2_S_Weights.DEFAULT if pretrained else None)
            self.network.classifier[1]=nn.Linear(self.network.classifier[1].in_features,classes)
        else:
            self.network=models.mobilenet_v3_large(weights=models.MobileNet_V3_Large_Weights.DEFAULT if pretrained else None)
            self.network.classifier[3]=nn.Linear(self.network.classifier[3].in_features,classes)
        self.register_buffer('mean',torch.tensor([.485,.456,.406]).reshape(1,3,1,1))
        self.register_buffer('std',torch.tensor([.229,.224,.225]).reshape(1,3,1,1))
    def forward(self,x):return self.network((x-self.mean)/self.std)

def block(a,b):return nn.Sequential(nn.Conv2d(a,b,3,padding=1,bias=False),nn.BatchNorm2d(b),nn.ReLU(),
                                   nn.Conv2d(b,b,3,padding=1,bias=False),nn.BatchNorm2d(b),nn.ReLU())
class LeafMask(nn.Module):
    def __init__(self):
        super().__init__();self.a=block(3,16);self.b=block(16,32);self.c=block(32,64)
        self.d=block(96,32);self.e=block(48,16);self.out=nn.Conv2d(16,1,1)
    def forward(self,x):
        a=self.a(x);b=self.b(nn.functional.max_pool2d(a,2));c=self.c(nn.functional.max_pool2d(b,2))
        d=self.d(torch.cat([nn.functional.interpolate(c,size=b.shape[-2:],mode='bilinear',align_corners=False),b],1))
        return self.out(self.e(torch.cat([nn.functional.interpolate(d,size=a.shape[-2:],mode='bilinear',align_corners=False),a],1)))

class MaskClassifier(nn.Module):
    def __init__(self):
        super().__init__();self.mask=LeafMask();self.classifier=Classifier('mobilenet_v3_large')
    def forward(self,x):
        binary=(self.mask(x).sigmoid()>=.5).to(x.dtype)
        area=binary.mean((2,3),keepdim=True);valid=((area>=.05)&(area<=.95)).to(x.dtype)
        masked=x*binary+(114/255)*(1-binary)
        return self.classifier(masked*valid+x*(1-valid))

def create(kind):
    if kind=='leaf_mask':return LeafMask()
    if kind=='masked_mobilenet_v3_large':return MaskClassifier()
    return Classifier(kind)

def stats(confusion):
    tp=confusion.diagonal().astype(float);support=confusion.sum(1);predicted=confusion.sum(0)
    precision=np.divide(tp,predicted,out=np.zeros_like(tp),where=predicted>0)
    recall=np.divide(tp,support,out=np.zeros_like(tp),where=support>0)
    f1=np.divide(2*precision*recall,precision+recall,out=np.zeros_like(tp),where=precision+recall>0)
    present=support>0
    return {'accuracy':float(tp.sum()/max(1,confusion.sum())),
            'balanced_accuracy':float(recall[present].mean()),'macro_f1':float(f1[present].mean()),
            'represented_classes':int(present.sum()),'samples':int(support.sum()),
            'classes':[{'id':i,'support':int(support[i]),'precision':float(precision[i]),'recall':float(recall[i]),
                        'f1':float(f1[i])} if support[i] else {'id':i,'support':0,'precision':None,'recall':None,'f1':None}
                       for i in range(len(tp))],'confusion_matrix':confusion.tolist()}

@torch.no_grad()
def evaluate(net,loader,device,mask=False):
    net.eval();conf=np.zeros((30,30),dtype=np.int64);intersection=union=0
    for x,y in loader:
        x=x.to(device);output=net(x)
        if mask:
            p=output.sigmoid().cpu()>=.5;t=y>=.5
            intersection+=int((p&t).sum());union+=int((p|t).sum())
        else:
            pred=output.argmax(1).cpu().numpy();true=y.numpy();np.add.at(conf,(true,pred),1)
    return {'mask_iou':intersection/max(1,union),'scope':'Lab whole-leaf masks only, not lesion/field accuracy'} if mask else stats(conf)

def loaders(data,kind,seed,batch):
    ismask=kind=='leaf_mask';rows=data['masks'] if ismask else data['rows']
    train=[r for r in rows if r['split']=='train'];val=[r for r in rows if r['split']==('val_aux' if ismask else 'val')]
    counts=np.bincount([r['class_id'] for r in train],minlength=30)
    weights=[min(3.,np.sqrt(len(train)/30/max(1,counts[r['class_id']]))) for r in train]
    generator=torch.Generator().manual_seed(seed)
    sampler=WeightedRandomSampler(weights,len(train),replacement=True,generator=generator)
    def seed_worker(_):
        s=torch.initial_seed()%2**32;random.seed(s);np.random.seed(s)
    common=dict(batch_size=batch,num_workers=4,pin_memory=True,worker_init_fn=seed_worker)
    return (DataLoader(Leaves(train,True,ismask),sampler=sampler,generator=generator,**common),
            DataLoader(Leaves(val,False,ismask),shuffle=False,**common),val)

def seed_everything(seed):
    random.seed(seed);np.random.seed(seed);torch.manual_seed(seed);torch.cuda.manual_seed_all(seed)
    torch.set_num_threads(8);torch.backends.cudnn.benchmark=False
    torch.use_deterministic_algorithms(True,warn_only=True)

def export(net,run,kind,val_rows,device):
    import onnxruntime as ort
    checkpoint=torch.load(run/'best.pt',map_location='cpu',weights_only=False)
    net.load_state_dict(checkpoint['model']);net.cpu().eval()
    x=Leaves(val_rows[:3],False,kind=='leaf_mask')[0][0][None]
    path=run/'model.onnx'
    torch.onnx.export(net,x,str(path),opset_version=17,input_names=['images'],output_names=['mask_logits' if kind=='leaf_mask' else 'logits'],
                      dynamic_axes=None,dynamo=False)
    options=ort.SessionOptions();options.intra_op_num_threads=4;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(path),sess_options=options,providers=['CPUExecutionProvider'])
    comparisons=[]
    for i in range(min(3,len(val_rows))):
        value=Leaves([val_rows[i]],False,kind=='leaf_mask')[0][0][None]
        with torch.no_grad():expected=net(value).numpy()
        actual=session.run(None,{'images':value.numpy()})[0]
        maximum=float(np.abs(actual-expected).max())
        assert np.allclose(actual,expected,rtol=1e-4,atol=.002),(kind,maximum)
        comparisons.append({'file':val_rows[i]['file'],'max_absolute_error':maximum})
    save(run/'export.json',{'status':'passed','kind':kind,'onnx':str(path),'sha256':checksum(path),'bytes':path.stat().st_size,
                          'input_shape':[1,3,224,224],'input':'RGB float32 /255, proportional square padding 114; ImageNet normalization embedded',
                          'output':'binary leaf mask logits' if kind=='leaf_mask' else '30 class logits; softmax externally',
                          'seed':checkpoint['seed'],'dataset_sha256':checkpoint['dataset_sha256'],'comparisons':comparisons,
                          'dependency':checkpoint.get('dependency'),
                          'scope':'GT leaf crop classifier or lab leaf mask; full detector pipeline and Android validation pending'})

def train(job,data,args):
    kind=job['kind'];seed=job['seed'];run=RUNS/job['id'];run.mkdir(parents=True,exist_ok=True)
    if (run/'export.json').exists() and read(run/'export.json')['status']=='passed':return 'already_completed'
    seed_everything(seed);device='cuda:0';ismask=kind=='leaf_mask'
    base_kind='mobilenet_v3_large' if kind in ('distilled_mobilenet_v3_large','masked_mobilenet_v3_large') else kind
    net=LeafMask() if ismask else Classifier(base_kind,pretrained=True)
    teacher=None;dependency=None
    if kind=='masked_mobilenet_v3_large':
        dependency=RUNS/f'leaf_mask_seed{seed}'/'best.pt'
        if not dependency.exists():return 'waiting_dependency'
        pretrained=net;net=MaskClassifier();net.classifier=pretrained
        net.mask.load_state_dict(torch.load(dependency,map_location='cpu',weights_only=False)['model'])
        net.mask.requires_grad_(False)
    if kind=='distilled_mobilenet_v3_large':
        dependency=RUNS/f'teacher_efficientnet_v2_s_seed{seed}'/'best.pt'
        if not dependency.exists():return 'waiting_dependency'
        teacher=Classifier('teacher_efficientnet_v2_s');teacher.load_state_dict(torch.load(dependency,map_location='cpu',weights_only=False)['model'])
        teacher.to(device).eval().requires_grad_(False)
    net.to(device);loader,val,val_rows=loaders(data,kind,seed,16 if 'teacher' in kind or ismask else 32)
    optimizer=torch.optim.AdamW([p for p in net.parameters() if p.requires_grad],lr=.0003,weight_decay=.0001)
    epochs=20 if ismask else 30
    scheduler=torch.optim.lr_scheduler.CosineAnnealingLR(optimizer,T_max=epochs)
    start=0;best=-1.;data_sha=checksum(DATA/'manifest.json');last=run/'last.pt'
    if last.exists():
        checkpoint=torch.load(last,map_location=device,weights_only=False)
        assert checkpoint['dataset_sha256']==data_sha and checkpoint['kind']==kind
        net.load_state_dict(checkpoint['model']);optimizer.load_state_dict(checkpoint['optimizer']);scheduler.load_state_dict(checkpoint['scheduler'])
        start=checkpoint['epoch'];best=checkpoint['best'];torch.set_rng_state(checkpoint['torch_rng'].cpu())
        torch.cuda.set_rng_state(checkpoint['gpu_rng'].cpu());random.setstate(checkpoint['python_rng']);np.random.set_state(checkpoint['numpy_rng'])
        loader.sampler.generator.set_state(checkpoint['sampler_rng'].cpu())
    save(run/'manifest.json',dict(job,status='training',started_utc=stamp(),epochs=epochs,seed=seed,kind=kind,
                               data_sha256=data_sha,amp=False,optimizer='AdamW',lr=.0003,batch=loader.batch_size,
                               params=sum(p.numel() for p in net.parameters()),dependency=str(dependency) if dependency else None,
                               code_sha256=checksum(__file__),selection='primary GT-crop macro-F1; lab IoU for masker',
                               limitations='Same source split; end-to-end disease AP and fresh field control pending'))
    history=read(run/'history.json') if (run/'history.json').exists() else []
    for epoch in range(start,epochs):
        began=time.monotonic();net.train()
        if isinstance(net,MaskClassifier):net.mask.eval()
        loss_sum=0;seen=0
        for x,y in loader:
            x=x.to(device);y=y.to(device);optimizer.zero_grad(set_to_none=True);out=net(x)
            if ismask:
                bce=nn.functional.binary_cross_entropy_with_logits(out,y)
                p=out.sigmoid();dice=1-(2*(p*y).sum((1,2,3))+1)/(p.sum((1,2,3))+y.sum((1,2,3))+1)
                loss=bce+dice.mean()
            else:
                loss=nn.functional.cross_entropy(out,y,label_smoothing=.03)
                if teacher is not None:
                    with torch.no_grad():target=teacher(x)
                    temp=3.
                    kl=nn.functional.kl_div(nn.functional.log_softmax(out/temp,1),nn.functional.softmax(target/temp,1),reduction='batchmean')*temp**2
                    loss=.5*loss+.5*kl
            if not torch.isfinite(loss).item():raise RuntimeError(f'Non-finite {kind} loss at epoch {epoch+1}')
            loss.backward();nn.utils.clip_grad_norm_(net.parameters(),5,error_if_nonfinite=True);optimizer.step()
            loss_sum+=float(loss.detach())*len(x);seen+=len(x)
        scheduler.step();metrics=evaluate(net,val,device,ismask);score=metrics['mask_iou' if ismask else 'macro_f1']
        record={'epoch':epoch+1,'loss':loss_sum/seen,'validation':metrics,'seconds':time.monotonic()-began}
        history.append(record);save(run/'history.json',history)
        best=max(best,score)
        checkpoint={'model':net.state_dict(),'optimizer':optimizer.state_dict(),'scheduler':scheduler.state_dict(),
                    'epoch':epoch+1,'best':best,'seed':seed,'kind':kind,'dataset_sha256':data_sha,'metrics':metrics,
                    'torch_rng':torch.get_rng_state(),'gpu_rng':torch.cuda.get_rng_state(),
                    'python_rng':random.getstate(),'numpy_rng':np.random.get_state(),
                    'sampler_rng':loader.sampler.generator.get_state(),'dependency':str(dependency) if dependency else None}
        atomic_torch(last,checkpoint)
        if score>=best:atomic_torch(run/'best.pt',checkpoint)
        save(REPORT/'progress.json',{'job':job['id'],'epoch':epoch+1,'epochs':epochs,'best_score':best,'updated_utc':stamp()})
        print(f'{job["id"]}: epoch {epoch+1}/{epochs}, score={score:.4f}, loss={loss_sum/seen:.4f}, seconds={record["seconds"]:.1f}',flush=True)
        if args.stop_file and args.stop_file.exists():return 'stopped'
    export(net,run,kind,val_rows,device)
    manifest=read(run/'manifest.json');manifest.update(status='completed',ended_utc=stamp(),best_validation_score=best)
    save(run/'manifest.json',manifest);return 'completed'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--stop-file',type=Path);parser.add_argument('--limit-jobs',type=int)
    args=parser.parse_args();REPORT.mkdir(parents=True,exist_ok=True);RUNS.mkdir(parents=True,exist_ok=True)
    assert torch.cuda.is_available(),'ROCm GPU unavailable; do not silently use CPU'
    data=read(DATA/'manifest.json')
    seeds=list(range(20261003,20261013))
    families=['mobilenet_v3_large','efficientnet_b0','leaf_mask','teacher_efficientnet_v2_s','masked_mobilenet_v3_large','distilled_mobilenet_v3_large']
    jobs=[{'id':f'{kind}_seed{s}','kind':kind,'seed':s} for kind in families for s in seeds]
    plan_path=REPORT/'classification_plan.json'
    plan={'jobs':jobs,'data_sha256':checksum(DATA/'manifest.json'),'seeds':seeds,'input':224,'classification_epochs':30,'mask_epochs':20,
          'scope':'Six components covering classifier, leaf segmentation and teacher/distillation; detector adapters handled separately'}
    if plan_path.exists():assert read(plan_path)==plan,'Plan differs; create a new version instead of resuming'
    else:save(plan_path,plan)
    state=read(REPORT/'status.json') if (REPORT/'status.json').exists() else {'jobs':{},'started_utc':stamp()}
    state.update(status='running',requested=len(jobs),pid=os.getpid(),gpu=torch.cuda.get_device_name(0));save(REPORT/'status.json',state)
    count=0
    for job in jobs:
        if args.stop_file and args.stop_file.exists():state['status']='stopped';break
        if state['jobs'].get(job['id'],{}).get('status')=='completed':continue
        state['current']=job;save(REPORT/'status.json',state)
        print('Starting '+job['id'],flush=True)
        try:
            result=train(job,data,args)
            state['jobs'][job['id']]={'status':'completed' if result=='already_completed' else result,'updated_utc':stamp()}
            if result=='stopped':state['status']='stopped';break
        except Exception as exc:
            state['jobs'][job['id']]={'status':'failed','error':repr(exc),'updated_utc':stamp()}
            state['status']='failed';save(REPORT/'status.json',state);raise
        state['completed']=sum(v['status']=='completed' for v in state['jobs'].values());save(REPORT/'status.json',state)
        count+=1
        if args.limit_jobs and count>=args.limit_jobs:state['status']='batch_finished';break
    else:state['status']='completed' if all(v.get('status')=='completed' for v in state['jobs'].values()) else 'waiting_dependencies'
    state['updated_utc']=stamp();save(REPORT/'status.json',state)

if __name__=='__main__':main()
