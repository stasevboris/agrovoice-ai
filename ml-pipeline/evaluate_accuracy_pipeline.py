"""Shared CPU-only end-to-end assessment for direct and leaf/classifier model choices."""
from __future__ import annotations
import argparse,json,math,time
from pathlib import Path
import cv2,numpy as np,onnxruntime as ort
from PIL import Image
from faster_coco_eval import COCO,COCOeval_faster

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'ml-pipeline/dataset_clean_v2'
REPORT=ROOT/'docs/experiments/accuracy_v1'
ASSETS=ROOT/'android-app/app/src/main/assets'

def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def sha(p):
    import hashlib
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def session(asset,digest):
    file=ASSETS/asset;assert sha(file)==digest
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    return ort.InferenceSession(str(file),sess_options=options,providers=['CPUExecutionProvider'])
def tensor(rgb):return np.ascontiguousarray(np.asarray(rgb,dtype=np.float32).transpose(2,0,1)[None]/255)
def letterbox(rgb,side=416):
    w,h=rgb.size;r=min(side/w,side/h);rw=max(1,round(w*r));rh=max(1,round(h*r));px=(side-rw)//2;py=(side-rh)//2
    canvas=np.full((side,side,3),114,np.uint8);canvas[py:py+rh,px:px+rw]=cv2.resize(np.asarray(rgb),(rw,rh),interpolation=cv2.INTER_LINEAR)
    return tensor(canvas),(w,h,r,px,py,rw,rh)
def iou(a,b):
    a=np.asarray(a);b=np.asarray(b);lo=np.maximum(a[:2],b[:,:2]);hi=np.minimum(a[2:4],b[:,2:4])
    inter=np.maximum(hi-lo,0).prod(1);area=max(0,(a[2]-a[0])*(a[3]-a[1]));other=np.maximum(b[:,2:4]-b[:,:2],0).prod(1)
    return inter/(area+other-inter+1e-9)
def decode(output,g,threshold,agnostic=False,detr=False):
    w,h,r,px,py,rw,rh=g
    if detr:rows=output[0].copy()
    else:
        scores=output[0,4:];cls=scores.argmax(0);confidence=scores[cls,np.arange(scores.shape[1])]
        xywh=output[0,:4].T;boxes=np.concatenate((xywh[:,:2]-xywh[:,2:]/2,xywh[:,:2]+xywh[:,2:]/2),1)
        rows=np.column_stack((boxes,confidence,cls))
    rows=rows[np.isfinite(rows).all(1)&(rows[:,4]>=threshold)];rows=rows[np.argsort(-rows[:,4],kind='stable')]
    if not detr:
        kept=[]
        for candidate in rows[:30000]:
            previous=np.asarray(kept)
            if len(kept) and ((iou(candidate,previous)>.7)&((previous[:,5]==candidate[5])|agnostic)).any():continue
            kept.append(candidate)
            if len(kept)==300:break
        rows=np.asarray(kept,dtype=np.float32).reshape(-1,6)
    sx=rw/w if detr else r;sy=rh/h if detr else r
    rows[:,[0,2]]=(rows[:,[0,2]]-px)/sx;rows[:,[1,3]]=(rows[:,[1,3]]-py)/sy
    rows[:,[0,2]]=rows[:,[0,2]].clip(0,w);rows[:,[1,3]]=rows[:,[1,3]].clip(0,h)
    return rows[(rows[:,2]>rows[:,0])&(rows[:,3]>rows[:,1])]
def leaf_tensor(image,b):
    w,h=image.size;mx=(b[2]-b[0])*.1;my=(b[3]-b[1])*.1
    bounds=(max(0,math.floor(b[0]-mx)),max(0,math.floor(b[1]-my)),min(w,math.ceil(b[2]+mx)),min(h,math.ceil(b[3]+my)))
    crop=image.crop(bounds);side=max(crop.size);frame=Image.new('RGB',(side,side),(114,114,114))
    frame.paste(crop,((side-crop.width)//2,(side-crop.height)//2));return tensor(frame.resize((224,224),Image.Resampling.BILINEAR))
def assessment(gt_boxes,predictions):
    tp=np.zeros(30,np.int64);fn=tp.copy();fp=tp.copy();matched=correct=total=0
    for image_id,gt in gt_boxes.items():
        pred=[p for p in predictions if p['image_id']==image_id and p['score']>=.25]
        pred.sort(key=lambda p:-p['score']);used=set();total+=len(gt)
        for p in pred:
            cls=p['category_id'];xy=p['bbox'];box=[xy[0],xy[1],xy[0]+xy[2],xy[1]+xy[3]]
            overlaps=iou(box,np.asarray([g[:4] for g in gt])) if gt else np.empty(0)
            choices=[i for i in np.argsort(-overlaps) if i not in used and overlaps[i]>=.5]
            if not choices:fp[cls]+=1;continue
            idx=choices[0];used.add(idx);matched+=1;true=gt[idx][4]
            if true==cls:tp[cls]+=1;correct+=1
            else:fn[true]+=1;fp[cls]+=1
        for i,g in enumerate(gt):
            if i not in used:fn[g[4]]+=1
    classes=[]
    for c in range(30):
        present=bool(tp[c]+fn[c]);p=tp[c]/max(1,tp[c]+fp[c]);r=tp[c]/max(1,tp[c]+fn[c]);f=2*p*r/max(1e-9,p+r)
        classes.append({'id':c,'tp':int(tp[c]),'fn':int(fn[c]),'fp':int(fp[c]),'precision':p if present else None,
                        'recall':r if present else None,'f1':f if present else None})
    return {'threshold':.25,'matching_iou':.5,'gt_leaves':total,'matched_leaves':matched,'correctly_diagnosed_gt_leaves':correct,
            'leaf_localization_recall':matched/max(1,total),'correct_class_and_box_recall':correct/max(1,total),
            'classification_accuracy_on_matched_boxes':correct/max(1,matched),'macro_f1':float(np.mean([c['f1'] for c in classes if c['f1'] is not None])),
            'classes':classes,'matching':'Score-ordered class-agnostic IoU matching, one prediction per GT; unmatched leaves penalized'}

def evaluate(model,split,limit=None):
    coco_file=ROOT/f'ml-pipeline/dataset_accuracy_v1/coco_{split}.json';annotations=read(coco_file)
    images=annotations['images'][:limit] if limit else annotations['images'];ids={r['id'] for r in images}
    detector=session(model['asset'],model['sha256']);c=model.get('classifier');classifier=session(c['asset'],c['sha256']) if c else None
    results=[];times=[];proposal_count=skipped=0;gt={r['id']:[] for r in images}
    for a in annotations['annotations']:
        if a['image_id'] in gt:
            x,y,w,h=a['bbox'];gt[a['image_id']].append([x,y,x+w,y+h,a['category_id']])
    for index,row in enumerate(images):
        with Image.open(DATA/'images'/split/row['file_name']) as f:image=f.convert('RGB')
        started=time.monotonic();x,g=letterbox(image,model.get('side',416))
        raw=detector.run(None,{detector.get_inputs()[0].name:x})[0]
        boxes=decode(raw,g,.1 if classifier else .001,agnostic=bool(classifier),detr=model.get('family')=='detr')
        if classifier:
            proposal_count+=len(boxes);skipped+=max(0,len(boxes)-32);selected=[]
            for b in boxes[:32]:
                logits=classifier.run(None,{classifier.get_inputs()[0].name:leaf_tensor(image,b)})[0][0]
                probability=np.exp(logits-logits.max());probability/=probability.sum();cls=int(probability.argmax())
                b=b.copy();b[4]*=float(probability[cls]);b[5]=cls
                if b[4]>=.001:selected.append(b)
            boxes=np.asarray(selected).reshape(-1,6)
        times.append(time.monotonic()-started)
        for b in boxes:
            results.append({'image_id':row['id'],'category_id':int(b[5]),'score':float(b[4]),'bbox':[float(b[0]),float(b[1]),float(b[2]-b[0]),float(b[3]-b[1])]})
        if (index+1)%50==0:print(f'{model["id"]} {index+1}/{len(images)}',flush=True)
    reference=COCO(str(coco_file));ap={'map50_95':0.,'map50':0.}
    if results:
        ev=COCOeval_faster(reference,reference.loadRes(results),'bbox');ev.params.imgIds=sorted(ids);ev.params.maxDets=[1,10,300]
        ev.evaluate();ev.accumulate();p=ev.eval['precision'][:,:,:,0,-1]
        ap={'map50_95':float(p[p>=0].mean()),'map50':float(p[0][p[0]>=0].mean())}
    return {'model':model,'split':split,'images':len(images),'partial':limit is not None,'disease_detection':ap,
            'diagnosis_at_fixed_threshold':assessment(gt,results),'proposed_leaves':proposal_count,'skipped_leaves':skipped,
            'desktop_pipeline_ms':{'median':float(np.median(times)*1000),'p95':float(np.percentile(times,95)*1000)},
            'note':'Shared desktop ONNX protocol. Desktop timing is not Android timing. Seed03 prototype is not ten-run result.',
            'protocol':{'detector_nms_iou':.7,'proposal_threshold':.1,'max_classified':32,'max_detections':300,
                        'field_matching_iou':.5,'confidence_for_f1':.25,'score':'detector score * classifier softmax for two-stage',
                        'preprocessing':'cv2 detector letterbox; PIL square crop for classifier, unresized original photo',
                        'original_study_comparability':'Original Ultralytics multi-label evaluator differs; compare all modes under this shared protocol'}}

def at_seed(model,seed):
    model=dict(model);kind=model['id'];model['seed']=seed;model['detector_seed']=seed
    if kind in ('yolo11n','yolo26n'):
        run=ROOT/f'runs/detect/study10_v2_{kind}_seed{seed}'
        file=run/'weights/best.onnx'
        if not file.exists():return None
        assert read(run/'run_manifest.json')['seed']==seed
        model.update(asset=str(file),sha256=sha(file),source=str(run))
    elif model.get('classifier'):
        run=ROOT/f'runs/accuracy_v1/{kind}_seed{seed}';file=run/'model.onnx'
        if not (run/'export.json').exists():return None
        info=read(run/'manifest.json');export=read(run/'export.json')
        if info['status']!='completed' or export['status']!='passed':return None
        assert info['seed']==seed and sha(file)==export['sha256']
        detector=ROOT/f'runs/detect/study10_v2_yolo26n_seed{seed}/weights/best.onnx'
        assert detector.is_file()
        classifier=dict(model['classifier']);classifier.update(asset=str(file),sha256=export['sha256'],seed=seed,source=str(run),
            bytes=export['bytes'],dependency=export.get('dependency'),data_sha256=info['data_sha256'])
        model.update(asset=str(detector),sha256=sha(detector),classifier=classifier,
                     source='Paired YOLO26n detector and classifier with the same seed')
    else:
        run=ROOT/f'runs/accuracy_v1/{kind}_seed{seed}';file=run/'model.onnx'
        if not (run/'export.json').exists():return None
        info=read(run/'manifest.json');export=read(run/'export.json')
        if info['status']!='completed' or export['status']!='passed':return None
        assert info['seed']==seed and sha(file)==export['sha256']
        model.update(asset=str(file),sha256=export['sha256'],source=str(run))
    model.update(provisional=True,evaluation_pairing='same seed across detector and classifier, independently repeated training; shared val data')
    return model

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--models',nargs='*');parser.add_argument('--split',choices=['val','val_aux'],default='val')
    parser.add_argument('--limit',type=int);parser.add_argument('--seeds',nargs='*',type=int)
    parser.add_argument('--require-full',action='store_true');args=parser.parse_args();manifest=read(ASSETS/'models/manifest.json')
    for model in manifest['models']:
        if args.models and model['id'] not in args.models:continue
        for seed in args.seeds or [None]:
            selected=at_seed(model,seed) if seed is not None else model
            if selected is None:
                if args.require_full:raise RuntimeError(f'Missing trained/exported {model["id"]} seed{seed}')
                print(f'SKIP not complete: {model["id"]} seed{seed}',flush=True);continue
            suffix=f'_seed{seed}' if seed is not None else ''
            path=REPORT/'pipeline'/f'{model["id"]}{suffix}_{args.split}{"_partial" if args.limit else ""}.json';path.parent.mkdir(parents=True,exist_ok=True)
            if path.exists():
                old=read(path)
                if old['model']['sha256']==selected['sha256'] and old['model'].get('classifier',{}).get('sha256')==selected.get('classifier',{}).get('sha256') and old.get('code_sha256')==sha(__file__):
                    print('REUSE '+path.name,flush=True);continue
            report=evaluate(selected,args.split,args.limit);report['manifest_sha256']=sha(ASSETS/'models/manifest.json')
            report['code_sha256']=sha(__file__);path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
            print(json.dumps({'model':model['id'],'seed':seed,'AP':report['disease_detection'],'diagnosis':report['diagnosis_at_fixed_threshold']['correct_class_and_box_recall']}))

if __name__=='__main__':main()
