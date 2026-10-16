"""Carry completed OLID geometry decisions forward without accepting diagnoses/splits."""
import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT/'docs/experiments/yolo26s_data_v1'

def main():
    src = BASE/'annotation_review_queue_r1/queue.jsonl'
    geometry = BASE/'olid_original_geometry_r3/geometry_adjudication.json'
    out = BASE/'annotation_review_queue_r2'
    out.mkdir(exist_ok=False)
    g = json.loads(geometry.read_bytes())
    assert g['status']=='geometry_review_complete_training_not_ready'
    decisions = {r['id']:r for r in g['decisions']}
    assert len(decisions)==1047
    counts=Counter(); joined=0
    with (out/'queue.jsonl').open('w',encoding='utf-8') as f:
        for line in src.read_bytes().splitlines():
            r=json.loads(line)
            if r['source']=='OLID':
                d=decisions[r['id']]
                assert r['source_sha256']==d['source_sha256']
                assert r['source_class']==d['source_class']
                box=d['box_xywh']
                assert len(box)==4 and all(0<=x<=1 for x in box) and box[2]>0 and box[3]>0
                r['whole_leaf_geometry']=dict(box_xywh=box,status=d['geometry_status'],
                    basis=d['geometry_basis'],bbox_choice=d['bbox_choice'],override_reason=d.get('override_reason'),
                    evidence='docs/experiments/yolo26s_data_v1/olid_original_geometry_r3/geometry_adjudication.json')
                r['pending'].remove('join_completed_original_geometry_decisions')
                counts[d['geometry_status']]+=1; joined+=1
            assert r['accepted_train'] is False and r['assigned_split'] is None
            f.write(json.dumps(r,ensure_ascii=False)+'\n')
    assert joined==1047
    report=dict(status='review_queue_only_not_training_manifest',
        updated_utc=datetime.now(timezone.utc).isoformat(),records=55992,olid_geometry_joined=joined,
        geometry_status_counts=dict(counts),accepted_train=0,gpu=False,
        input_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [src,geometry]},
        queue_sha256=hashlib.sha256((out/'queue.jsonl').read_bytes()).hexdigest(),
        limitations=['Geometry acceptance does not accept disease mapping or independent physical groups.',
                    'All prior quarantine and historical split restrictions preserved.'])
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))

if __name__=='__main__':main()
