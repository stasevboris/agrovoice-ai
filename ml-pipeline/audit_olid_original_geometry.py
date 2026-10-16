"""Verify OLID originals and propose source-sample boxes; never accept labels.

The proposal is measured on a lossless 1024px resize of the verified original,
not the earlier JPEG preview. All changed/multiple-component cases need review.
EXIF capture metadata is evidence, not a claimed physical-plant identifier.
"""
from __future__ import annotations
from collections import Counter
from datetime import datetime, timezone
import hashlib, io, json, os, zipfile
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT/'docs/experiments/yolo26s_data_v1'
SOURCE = ROOT/'ml-pipeline/source_candidates/research_2026-10-03/olid'
OUT = BASE/'olid_original_geometry_r3'


def save(state):
    state['updated_utc'] = datetime.now(timezone.utc).isoformat()
    p = OUT/'status.tmp'
    p.write_text(json.dumps(state, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    p.replace(OUT/'status.json')


def propose(rgb):
    a = np.asarray(rgb)
    lab = cv2.cvtColor(a, cv2.COLOR_RGB2LAB).astype(np.float32)
    chroma = np.linalg.norm(lab[:, :, 1:]-128, axis=2)
    mask = ((chroma>12) & (lab[:, :, 0]>25)).astype(np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5,5), np.uint8))
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3,3), np.uint8))
    n, components, stats, _ = cv2.connectedComponentsWithStats(mask)
    if n<2:
        return None, {'flags':['no_foreground']}
    largest = 1+int(np.argmax(stats[1:,cv2.CC_STAT_AREA]))
    threshold = max(24, int(stats[largest,cv2.CC_STAT_AREA]*.002))
    keep = [i for i in range(1,n) if stats[i,cv2.CC_STAT_AREA]>=threshold]
    # Union remains a proposal: independent laminae must not be silently merged.
    xs=[int(stats[i,cv2.CC_STAT_LEFT]) for i in keep]
    ys=[int(stats[i,cv2.CC_STAT_TOP]) for i in keep]
    x1=min(xs);y1=min(ys)
    x2=max(int(stats[i,cv2.CC_STAT_LEFT]+stats[i,cv2.CC_STAT_WIDTH]) for i in keep)
    y2=max(int(stats[i,cv2.CC_STAT_TOP]+stats[i,cv2.CC_STAT_HEIGHT]) for i in keep)
    iw,ih=rgb.size
    fraction=sum(int(stats[i,cv2.CC_STAT_AREA]) for i in keep)/(iw*ih)
    flags=[]
    if len(keep)>1: flags.append('multiple_components_review')
    if fraction<.03 or fraction>.9: flags.append('area_extreme_review')
    if x1==0 or y1==0 or x2==iw or y2==ih: flags.append('visible_frame_truncation_review')
    return [(x1+x2)/(2*iw),(y1+y2)/(2*ih),(x2-x1)/iw,(y2-y1)/ih], {
        'flags':flags,'retained_components':len(keep),'foreground_fraction':fraction,
        'component_area_threshold_pixels':threshold,'working_size':[iw,ih]}


def main():
    cv2.setNumThreads(2)
    parent=BASE/'olid_review_r2_chroma/inventory.json'
    inv=json.loads(parent.read_text(encoding='utf-8-sig'))
    review=BASE/'olid_full_review_r2/visual_review.json'
    seen=json.loads(review.read_text(encoding='utf-8'))
    if len(seen['visually_seen_ids'])!=1047 or set(seen['visually_seen_ids'])!={r['id'] for r in inv['images']}:
        raise ValueError('All previous proposals must actually be visually inspected first')
    OUT.mkdir(parents=True,exist_ok=False)
    (OUT/'previews').mkdir()
    state=dict(status='running',pid=os.getpid(),gpu=False,checked=0,planned=1047,
               training_accepted=0,parent_sha256=hashlib.sha256(parent.read_bytes()).hexdigest(),
               visual_review_sha256=hashlib.sha256(review.read_bytes()).hexdigest(),
               helper_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    archives={};counts=Counter();flags=Counter();save(state)
    try:
        for info in inv['archives']:
            path=SOURCE/info['file']
            with path.open('rb') as f: actual=hashlib.file_digest(f,'md5').hexdigest()
            if actual!=info['md5']:raise ValueError('Archive changed: '+info['file'])
            archives[info['file']]=zipfile.ZipFile(path)
        with (OUT/'records.jsonl').open('w',encoding='utf-8') as output:
            for number,row in enumerate(inv['images'],1):
                raw=archives[row['archive']].read(row['image_member'])
                if hashlib.sha256(raw).hexdigest()!=row['source_sha256']:
                    raise ValueError('Original changed: '+row['id'])
                with Image.open(io.BytesIO(raw)) as image:
                    exif=image.getexif();dates={}
                    for key,name in [(306,'DateTime'),(36867,'DateTimeOriginal'),(36868,'DateTimeDigitized')]:
                        if exif.get(key):dates[name]=str(exif.get(key))
                    nested=exif.get_ifd(34665) if 34665 in exif else {}
                    for key,name in [(36867,'DateTimeOriginal'),(36868,'DateTimeDigitized')]:
                        if nested.get(key):dates[name]=str(nested[key])
                    rgb=ImageOps.exif_transpose(image).convert('RGB')
                    if list(rgb.size)!=row['upright_size']:raise ValueError('Geometry changed: '+row['id'])
                    original_size=list(rgb.size);rgb.thumbnail((1024,1024),Image.Resampling.LANCZOS)
                    box,checks=propose(rgb)
                    previous=row['proposed_box_xywh']
                    delta=max(abs(a-b) for a,b in zip(box,previous)) if box and previous else None
                    if delta is None or delta>.01:checks['flags'].append('changed_box_review')
                    preview=OUT/'previews'/(row['id']+'.png');rgb.save(preview)
                record=dict(row,proposed_box_xywh=box,geometry_checks=checks,
                    previous_box_xywh=previous,max_normalized_box_delta=delta,
                    geometry_basis='Lossless1024px resize of SHA-verified original, normalized to upright original',
                    upright_size=original_size,capture_exif=dates,physical_plant_id=None,
                    review_preview=str(preview.relative_to(ROOT)),annotation_acceptance='pending_r3_review_grouping')
                output.write(json.dumps(record,ensure_ascii=False)+'\n')
                counts['images']+=1;counts['with_capture_time']+=bool(dates)
                counts['flagged']+=bool(checks['flags']);flags.update(checks['flags'])
                state.update(checked=number,counts=dict(counts),flags=dict(flags))
                if number%32==0:output.flush();save(state)
        state['status']='completed_original_audit_pending_geometry_review';save(state)
    except Exception as e:
        state.update(status='failed',error=str(e));save(state);raise
    finally:
        for archive in archives.values():archive.close()
    print(json.dumps(state,ensure_ascii=False))


if __name__=='__main__':main()
