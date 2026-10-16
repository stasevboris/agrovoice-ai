"""Persist actual completed visual geometry decisions; no training acceptance."""
from datetime import datetime,timezone
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/experiments/yolo26s_data_v1/olid_original_geometry_r3'
OVERRIDES={
    '256c35b52b681c442354':'Union captures isolated cloth glint at right; previous box follows actual leaf.',
    'd7de1d4f5f436c916c43':'Union captures detached white fleck above leaf; previous box follows lamina.',
    '5e27567ce17be8d5fce8':'Union captures colored cloth/dust at right; individual lossless preview inspected, use previous main-lamina box.',
    '65abf1aca642f4046fb2':'Union captures detached cloth/thread at left; previous box follows actual leaf.',
}

def main():
    target=OUT/'geometry_adjudication.json'
    if target.exists():raise FileExistsError('Preserve adjudication; use another revision after a new review')
    index=json.loads((OUT/'flags_index.json').read_text())
    rows=[json.loads(x) for x in (OUT/'records.jsonl').read_text().splitlines()]
    previous=json.loads((ROOT/'docs/experiments/yolo26s_data_v1/olid_full_review_r2/visual_review.json').read_text())
    flagged=[r for r in rows if r['geometry_checks']['flags']]
    assert len(rows)==1047 and len(flagged)==42
    assert {i for sheet in index['sheets'] for i in sheet['ids']}=={r['id'] for r in flagged}
    assert set(OVERRIDES)<={r['id'] for r in flagged}
    decisions=[]
    for row in rows:
        override=row['id'] in OVERRIDES
        box=row['previous_box_xywh'] if override else row['proposed_box_xywh']
        assert box and all(0<=x<=1 for x in box) and box[2]>0 and box[3]>0
        if not row['geometry_checks']['flags']:
            assert row['max_normalized_box_delta'] is not None and row['max_normalized_box_delta']<=.01
        decisions.append(dict(id=row['id'],source_sha256=row['source_sha256'],
            source_class=row['source_class'],box_xywh=box,
            geometry_status='visually_reviewed_source_sample_box',
            geometry_basis='Verified original resized losslessly to1024px; previous all1047 previews plus all42 flagged proposals reviewed',
            bbox_choice='previous_reviewed_box' if override else 'original_derived_box',
            override_reason=OVERRIDES.get(row['id']),flags=row['geometry_checks']['flags'],
            capture_exif=row['capture_exif'],physical_plant_id=None,
            source_diagnosis_preserved=True,training_acceptance='pending_class_mapping_grouped_splits'))
    result=dict(status='geometry_review_complete_training_not_ready',updated_utc=datetime.now(timezone.utc).isoformat(),
        original_records_sha256=hashlib.sha256((OUT/'records.jsonl').read_bytes()).hexdigest(),
        previous_visual_review_sha256=hashlib.sha256((ROOT/'docs/experiments/yolo26s_data_v1/olid_full_review_r2/visual_review.json').read_bytes()).hexdigest(),
        flag_render_index_sha256=hashlib.sha256((OUT/'flags_index.json').read_bytes()).hexdigest(),
        actually_seen_flag_sheets=[1,2,3],actually_seen_flag_ids=[r['id'] for r in flagged],
        individual_lossless_previews_seen=['8c6eb0df93e4ae3663cd','5e27567ce17be8d5fce8'],
        geometry_decisions=len(decisions),manual_overrides=OVERRIDES,training_accepted=0,
        observations=['27 frame flags are real crop/petiole contacts; visible extents only, no invented unseen tissue.',
            'Eight area flags show actual small leaves, not automatic corruption.',
            '8c6eb0df93e4ae3663cd original-derived box correctly includes small attached upper lamina.',
            'Compound samples may be one author-labeled connected leaf unit under annotation contract.',
            'Source diagnostic labels were not reassigned visually; physical plants remain unknown.'],
        decisions=decisions)
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'geometry_decisions':len(decisions),'manual_overrides':len(OVERRIDES),'new_train_accepted':0}))

if __name__=='__main__':main()
