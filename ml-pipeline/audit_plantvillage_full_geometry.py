"""Check every staged pair on CPU; geometry eligibility is not final acceptance."""
from collections import Counter,defaultdict
from datetime import datetime,timezone
import hashlib,json,os
from pathlib import Path
import cv2
from PIL import Image
from prepare_supplemented import leaf_group,mask_box,signature

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'ml-pipeline/source_candidates/yolo26s_data_v1/plantvillage_all_crops'
OUT=ROOT/'docs/experiments/yolo26s_data_v1/plantvillage_full_geometry_r1'


def save(status):
    status['updated_utc']=datetime.now(timezone.utc).isoformat()
    p=OUT/'status.tmp';p.write_text(json.dumps(status,indent=2)+'\n',encoding='utf-8');p.replace(OUT/'status.json')


def main():
    cv2.setNumThreads(2)
    stage=json.loads((SOURCE/'archive_stage_status.json').read_text())
    if stage['status']!='all_indexed_source_candidates_staged_not_training_ready':
        raise ValueError('Complete pinned-source staging is required')
    OUT.mkdir(parents=True,exist_ok=False)
    index=json.loads((SOURCE/'source_index.json').read_text())
    mapping=json.loads((ROOT/'ml-pipeline/source_candidates/plantvillage_subset/leaf-map.json').read_text())
    pairs=defaultdict(dict)
    for item in index['files']:pairs[(item['category'],item['color_filename'])][item['variant']]=item
    status=dict(status='running',pid=os.getpid(),source_revision=index['revision'],
        source_index_sha256=hashlib.sha256((SOURCE/'source_index.json').read_bytes()).hexdigest(),
        source_archive_sha256=stage['archive_sha256'],helper_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        planned_pairs=len(pairs),checked_pairs=0,training_accepted=0,gpu=False)
    counts=Counter();flags_count=Counter();class_counts=defaultdict(Counter);save(status)
    try:
        with (OUT/'records.jsonl').open('w',encoding='utf-8') as output:
            for number,((category,filename),pair) in enumerate(sorted(pairs.items()),1):
                flags=[]; shas={}
                for variant in ('color','segmented'):
                    item=pair[variant];payload=(ROOT/item['candidate_path']).read_bytes()
                    if hashlib.sha1(f'blob {len(payload)}\0'.encode()+payload).hexdigest()!=item['git_blob']:
                        raise ValueError('Publisher blob changed: '+item['candidate_path'])
                    shas[variant]=hashlib.sha256(payload).hexdigest()
                with Image.open(ROOT/pair['color']['candidate_path']) as rgb,Image.open(ROOT/pair['segmented']['candidate_path']) as segmented:
                    if rgb.size!=segmented.size or rgb.getexif().get(274,1)!=1:
                        box=None;flags.append('mask_rgb_geometry_mismatch')
                    else:
                        box,reason=mask_box(segmented)
                        if reason:flags.append(reason)
                    sig=signature(rgb)
                group=leaf_group(filename,category,mapping)
                if group is None:flags.append('physical_leaf_group_unknown_or_ambiguous')
                record=dict(id=hashlib.sha256((category+'/'+filename).encode()).hexdigest()[:16],
                    source_class=category,source_filename=filename,source_files=pair,source_sha256=shas,
                    proposed_box_xywh=box,source_signature=sig,physical_leaf_group_candidate=group,
                    flags=flags,training_acceptance='pending_final_annotation_group_domain_policy')
                output.write(json.dumps(record,ensure_ascii=False)+'\n')
                counts['pairs']+=1;counts['no_flags']+=int(not flags);counts['flagged']+=int(bool(flags))
                flags_count.update(flags);class_counts[category]['pairs']+=1;class_counts[category]['no_flags']+=int(not flags)
                status.update(checked_pairs=number,counts=dict(counts),flags=dict(flags_count))
                if number%128==0:output.flush();save(status)
        status.update(status='completed_geometry_audit_not_training_ready',class_counts={k:dict(v) for k,v in class_counts.items()})
        save(status)
    except Exception as e:
        status.update(status='failed',error=str(e));save(status);raise


if __name__=='__main__':main()
