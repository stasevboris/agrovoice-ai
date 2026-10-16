"""Render flagged original-derived proposals without setting review/acceptance."""
from pathlib import Path
import hashlib,json
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/experiments/yolo26s_data_v1/olid_original_geometry_r3'

def main():
    status=json.loads((OUT/'status.json').read_text())
    if status['status']!='completed_original_audit_pending_geometry_review':raise ValueError('Wait for actual source audit completion')
    rows=[json.loads(x) for x in (OUT/'records.jsonl').read_text().splitlines()]
    selected=[r for r in rows if r['geometry_checks']['flags']]
    index=[]
    if (OUT/'flags_index.json').exists():raise FileExistsError('Preserve existing rendering')
    for start in range(0,len(selected),16):
        fig,axes=plt.subplots(4,4,figsize=(14,12))
        for ax in axes.flat:ax.axis('off')
        for ax,row in zip(axes.flat,selected[start:start+16]):
            with Image.open(ROOT/row['review_preview']) as image:
                ax.imshow(image)
                for key,color in [('previous_box_xywh','cyan'),('proposed_box_xywh','red')]:
                    x,y,w,h=row[key];ax.add_patch(Rectangle(((x-w/2)*image.width,(y-h/2)*image.height),w*image.width,h*image.height,fill=False,edgecolor=color,linewidth=1.2))
            ax.set_title(row['source_class']+'\n'+row['id']+'\n'+','.join(row['geometry_checks']['flags']),fontsize=7)
        fig.suptitle('OLID original-derived flags: previous cyan / union red; proposals only')
        fig.tight_layout();name=f'flags_{start//16+1:02}.jpg';fig.savefig(OUT/name,dpi=125);plt.close(fig)
        index.append(dict(path=str((OUT/name).relative_to(ROOT)),ids=[r['id'] for r in selected[start:start+16]],visually_seen=False))
    (OUT/'flags_index.json').write_text(json.dumps(dict(status='rendered_pending_visual_review',records_sha256=hashlib.sha256((OUT/'records.jsonl').read_bytes()).hexdigest(),sheets=index,training_accepted=0),indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'flagged':len(selected),'sheets':len(index),'accepted':0}))

if __name__=='__main__':main()
