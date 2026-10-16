"""Derive grouped leaf crops, source leaf masks and COCO without altering clean v2."""
from __future__ import annotations
import hashlib
import json
import math
from collections import Counter
from pathlib import Path
import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'ml-pipeline/dataset_clean_v2'
DEST = ROOT / 'ml-pipeline/dataset_accuracy_v1'
FINGERPRINT = '7306a09a94cfccb1854707d86e45de654a403145dffc59d92d38cbc5b694d5bc'

def digest(p):
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def main():
    text=(SOURCE/'manifest.json').read_text(encoding='utf-8')
    assert hashlib.sha256(text.encode()).hexdigest()==FINGERPRINT
    if (DEST/'manifest.json').exists():
        data=json.loads((DEST/'manifest.json').read_text(encoding='utf-8'))
        assert data['source_fingerprint']==FINGERPRINT
        print(json.dumps({'status':'already_prepared','counts':data['counts']}));return
    records=json.loads(text);classes=json.loads((SOURCE/'classes.json').read_text(encoding='utf-8'))
    DEST.mkdir(parents=True,exist_ok=True)
    rows=[];masks=[];coco={};warnings=[]
    for split in ('train','val','test','val_aux'):
        (DEST/'crops'/split).mkdir(parents=True,exist_ok=True)
        (DEST/'masks'/split).mkdir(parents=True,exist_ok=True)
        coco[split]={'images':[],'annotations':[],'categories':[{'id':i,'name':n} for i,n in enumerate(classes)]}
    for image_id,r in enumerate(records,1):
        split=r['split'];path=SOURCE/'images'/split/r['filename']
        assert digest(path)==r['prepared_sha256']
        with Image.open(path) as opened: image=opened.convert('RGB')
        w,h=image.size
        coco[split]['images'].append({'id':image_id,'file_name':r['filename'],'width':w,'height':h})
        source_mask=None
        if r['source_dataset']=='PlantVillage':
            mask_path=ROOT/'ml-pipeline/source_candidates/plantvillage_subset'/r['sources'][1]
            with Image.open(mask_path) as src: arr=(np.asarray(src.convert('RGB')).max(axis=2)>25).astype(np.uint8)
            components,labels,stats,_=cv2.connectedComponentsWithStats(arr,8)
            if components>=2:
                largest=1+int(np.argmax(stats[1:,cv2.CC_STAT_AREA]));source_mask=Image.fromarray((labels==largest).astype(np.uint8)*255)
                assert source_mask.size==image.size
        for box_id,(cls,cx,cy,bw,bh) in enumerate(r['boxes']):
            x0=max(0,(cx-bw/2)*w);y0=max(0,(cy-bh/2)*h)
            x1=min(w,(cx+bw/2)*w);y1=min(h,(cy+bh/2)*h)
            if x1-x0<2 or y1-y0<2:
                warnings.append({'filename':r['filename'],'box':box_id,'reason':'smaller_than_2px'});continue
            c=coco[split]
            c['annotations'].append({'id':len(c['annotations'])+1,'image_id':image_id,'category_id':int(cls),
                                     'bbox':[x0,y0,x1-x0,y1-y0],'area':(x1-x0)*(y1-y0),'iscrowd':0})
            # 10% on each side, inherited split/group before any derived crop.
            pad_x=(x1-x0)*.1;pad_y=(y1-y0)*.1
            bounds=(max(0,math.floor(x0-pad_x)),max(0,math.floor(y0-pad_y)),
                    min(w,math.ceil(x1+pad_x)),min(h,math.ceil(y1+pad_y)))
            crop=image.crop(bounds);crop.thumbnail((512,512),Image.Resampling.LANCZOS)
            name=f'{Path(r["filename"]).stem}_b{box_id:03}.jpg'
            target=DEST/'crops'/split/name
            crop.save(target,quality=95,subsampling=0)
            row={'file':target.relative_to(DEST).as_posix(),'class_id':int(cls),'split':split,
                 'parent':r['filename'],'parent_sha256':r['prepared_sha256'],'group_id':r['group_id'],
                 'source_dataset':r['source_dataset'],'crop_xyxy':bounds,'sha256':digest(target)}
            rows.append(row)
            if source_mask is not None:
                mask=source_mask.crop(bounds).resize(crop.size,Image.Resampling.NEAREST)
                mask_target=DEST/'masks'/split/(Path(name).stem+'.png');mask.save(mask_target)
                masks.append(dict(row,mask=mask_target.relative_to(DEST).as_posix(),mask_sha256=digest(mask_target),
                                  mask_target='whole_leaf_largest_component_from_source_mask_not_lesion'))
        if image_id%400==0:print(f'Prepared {image_id}/{len(records)} source photos',flush=True)
    group_splits={}
    for r in rows:
        existing=group_splits.setdefault(r['group_id'],r['split'])
        assert existing==r['split'],f'Group crosses splits: {r["group_id"]}'
    for split,c in coco.items():
        (DEST/f'coco_{split}.json').write_text(json.dumps(c,ensure_ascii=False),encoding='utf-8')
    manifest={'version':1,'source_fingerprint':FINGERPRINT,'source':'dataset_clean_v2','classes':classes,
              'margin_each_side':.1,'max_crop_side':512,'counts':dict(Counter(r['split'] for r in rows)),
              'mask_counts':dict(Counter(r['split'] for r in masks)),
              'split_policy':'Inherit original image/physical groups before cropping; test never used in model selection',
              'new_external_training_data':False,'rows':rows,'masks':masks,'warnings':warnings,
              'limitations':['Original per-class field coverage and source label uncertainty remain',
                             'Leaf masks are only from accepted laboratory PlantVillage; no lesion masks or field mask ground truth']}
    (DEST/'classes.json').write_text(json.dumps(classes,ensure_ascii=False,indent=2),encoding='utf-8')
    (DEST/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    (ROOT/'docs/experiments/accuracy_v1').mkdir(parents=True,exist_ok=True)
    record={k:v for k,v in manifest.items() if k not in ('rows','masks')}
    record['manifest_sha256']=digest(DEST/'manifest.json')
    (ROOT/'docs/experiments/accuracy_v1/data_summary.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(record,ensure_ascii=False),flush=True)

if __name__=='__main__':main()
