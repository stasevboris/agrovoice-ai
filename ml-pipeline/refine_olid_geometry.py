"""Recompute OLID review proposals from existing thumbnails; accepts no labels."""
from __future__ import annotations
from datetime import datetime,timezone
import hashlib,json
from pathlib import Path
import cv2
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
PARENT=ROOT/'docs/experiments/yolo26s_data_v1/olid_review/inventory.json'
OUT=ROOT/'docs/experiments/yolo26s_data_v1/olid_review_r2_chroma'


def main():
    cv2.setNumThreads(2)
    OUT.mkdir(parents=True,exist_ok=True)
    inventory=json.loads(PARENT.read_text(encoding='utf-8-sig'))
    rows=inventory['images']
    for row in rows:
        with Image.open(ROOT/row['review_preview']) as image:
            rgb=np.asarray(image.convert('RGB'))
        lab=cv2.cvtColor(rgb,cv2.COLOR_RGB2LAB).astype(np.float32)
        chroma=np.linalg.norm(lab[:,:,1:]-128,axis=2)
        mask=((chroma>12)&(lab[:,:,0]>25)).astype(np.uint8)
        mask=cv2.morphologyEx(mask,cv2.MORPH_CLOSE,np.ones((5,5),np.uint8))
        mask=cv2.morphologyEx(mask,cv2.MORPH_OPEN,np.ones((3,3),np.uint8))
        n,_,stats,_=cv2.connectedComponentsWithStats(mask)
        checks={'flags':[]}
        if n<2:
            box=None;checks['flags'].append('no_colored_foreground')
        else:
            largest=1+int(np.argmax(stats[1:,cv2.CC_STAT_AREA]))
            x,y,w,h,area=map(int,stats[largest]);ih,iw=mask.shape
            checks.update(foreground_fraction=area/(iw*ih),largest_component_coverage=area/max(1,int(mask.sum())),touches_frame_edge=bool(x==0 or y==0 or x+w==iw or y+h==ih))
            if checks['foreground_fraction']<.03 or checks['foreground_fraction']>.9:checks['flags'].append('area_extreme')
            if checks['largest_component_coverage']<.95:checks['flags'].append('multiple_components')
            if checks['touches_frame_edge']:checks['flags'].append('frame_edge_review')
            box=[(x+w/2)/iw,(y+h/2)/ih,w/iw,h/ih]
        row.update(proposed_box_xywh=box,geometry_checks=checks,annotation_acceptance='pending_visual_review')
    selected=[]
    for folder in sorted(inventory['source_class_counts']):
        selected.extend(sorted((r for r in rows if r['source_class']==folder),key=lambda r:r['source_sha256'])[:12])
    for start in range(0,len(selected),16):
        fig,axes=plt.subplots(4,4,figsize=(14,12))
        for axis in axes.flat:axis.axis('off')
        for axis,row in zip(axes.flat,selected[start:start+16]):
            with Image.open(ROOT/row['review_preview']) as rgb:
                axis.imshow(rgb)
                if row['proposed_box_xywh']:
                    x,y,w,h=row['proposed_box_xywh']
                    axis.add_patch(Rectangle(((x-w/2)*rgb.width,(y-h/2)*rgb.height),w*rgb.width,h*rgb.height,fill=False,edgecolor='red',linewidth=1.2))
            axis.set_title(row['source_class']+'\n'+row['id'],fontsize=8)
        fig.suptitle('OLID r2 chroma: geometry PROPOSALS, no accepted labels')
        fig.tight_layout();fig.savefig(OUT/f'geometry_sample_{start//16+1:02}.jpg',dpi=125);plt.close(fig)
    inventory.update(status='geometry_proposals_only',updated_utc=datetime.now(timezone.utc).isoformat(),
        parent_inventory=str(PARENT.relative_to(ROOT)),parent_inventory_sha256=hashlib.sha256(PARENT.read_bytes()).hexdigest(),
        algorithm={'kind':'CPU Lab chroma from neutral cloth, largest component','chroma_min':12,'lightness_min':25,'basis':'Existing lossy1024px review previews, not new original-file verification','script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},
        geometry_samples_seen=False,training_images_accepted=0,sample_ids_sha256=hashlib.sha256('\n'.join(r['id'] for r in selected).encode()).hexdigest())
    (OUT/'inventory.json').write_text(json.dumps(inventory,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'images':len(rows),'sample_sheets':9,'accepted':0,'flagged':sum(bool(r['geometry_checks']['flags']) for r in rows)}))


if __name__=='__main__':main()
