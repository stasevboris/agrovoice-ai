"""Render every pending OLID proposal for individual geometry review, CPU only."""
from datetime import datetime,timezone
import hashlib,json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'docs/experiments/yolo26s_data_v1/olid_review_r2_chroma/inventory.json'
OUT=ROOT/'docs/experiments/yolo26s_data_v1/olid_full_review_r2'


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    if (OUT/'index.json').exists():raise FileExistsError('Preserve completed review rendering')
    rows=sorted(json.loads(SOURCE.read_text())['images'],key=lambda r:(r['source_class'],r['source_sha256']))
    sheets=[]
    for start in range(0,len(rows),16):
        fig,axes=plt.subplots(4,4,figsize=(14,12))
        for axis in axes.flat:axis.axis('off')
        for axis,row in zip(axes.flat,rows[start:start+16]):
            with Image.open(ROOT/row['review_preview']) as image:
                axis.imshow(image)
                if row['proposed_box_xywh']:
                    x,y,w,h=row['proposed_box_xywh']
                    axis.add_patch(Rectangle(((x-w/2)*image.width,(y-h/2)*image.height),w*image.width,h*image.height,fill=False,edgecolor='red',linewidth=1.2))
            axis.set_title(row['source_class']+'\n'+row['id']+'\n'+','.join(row['geometry_checks']['flags']),fontsize=7)
        name=f'leaves_{start//16+1:02}.jpg'
        fig.suptitle('OLID all originals: pending proposals, not accepted labels; IDs below each photo')
        fig.tight_layout();fig.savefig(OUT/name,dpi=125);plt.close(fig)
        sheets.append(dict(path=str((OUT/name).relative_to(ROOT)),ids=[r['id'] for r in rows[start:start+16]],visually_seen=False))
    (OUT/'index.json').write_text(json.dumps(dict(status='rendered_pending_individual_review',
        updated_utc=datetime.now(timezone.utc).isoformat(),source_sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        helper_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),images=len(rows),
        previews='Same verified-source1024px previews as r2; not a new original-resolution geometry acceptance',
        training_accepted=0,sheets=sheets),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'images':len(rows),'sheets':len(sheets),'accepted':0}))


if __name__=='__main__':main()
