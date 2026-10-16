"""Join source audits into an explicit review queue, never a training manifest."""
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT/'docs/experiments/yolo26s_data_v1'
OUT = BASE/'annotation_review_queue_r1'

def main():
    OUT.mkdir(exist_ok=False)
    hashes = {}
    def read(name, lines=False):
        p = BASE/name; raw = p.read_bytes()
        hashes[name] = hashlib.sha256(raw).hexdigest()
        return [json.loads(x) for x in raw.splitlines()] if lines else json.loads(raw)
    constraints = {(r['source'],r['id']):r for r in read('group_closure_r1/candidate_split_constraints.jsonl',True)}
    disposition = {(r['source'],r['id']):r for r in read('candidate_overlap_full_r3/candidate_disposition.jsonl',True)}
    mapping = {r['source_class']:r for r in read('source_class_mapping.draft.json')['PlantVillage_mapping']}
    sources = [
        ('PlantVillage',read('plantvillage_full_geometry_r1/records.jsonl',True)),
        ('PlantVillage',read('plantvillage_extra_conditions_r1/records.jsonl',True)),
        ('OLID',read('olid_review_r2_chroma/inventory.json')['images']),
        ('CucumberZenodo',read('cucumber_review/inventory.json')['images'])]
    seen=set(); counts=Counter(); blockers=Counter()
    with (OUT/'queue.jsonl').open('w',encoding='utf-8') as f:
        for source, records in sources:
            for r in records:
                key=(source,r['id'])
                assert key not in seen, key
                seen.add(key)
                c=constraints[key]; d=disposition[key]
                pending=['source_semantic_confirmation','physical_capture_group_review','independent_split_policy']
                if source=='PlantVillage':
                    pending.append('whole_leaf_geometry_flags_review' if r.get('flags') else 'whole_leaf_annotation_acceptance')
                elif source=='OLID': pending.append('join_completed_original_geometry_decisions')
                else: pending.append('whole_leaf_reannotation_or_verified_box_acceptance')
                m=mapping.get(r['source_class']) if source=='PlantVillage' else None
                if m is None: pending.append('new_class_contract_and_support')
                out=dict(source=source,id=r['id'],source_class=r['source_class'],
                         source_sha256=r['source_sha256'],
                         proposed_existing_id=m['proposed_existing_id'] if m else None,
                         proposed_existing_name=m['proposed_existing_name'] if m else None,
                         overlap_decision=d['decision'], split_rule=c['split_rule'],
                         component_id=c['component_id'], historical_splits=c['historical_splits'],
                         publisher_leaf_group=c.get('publisher_leaf_group'),
                         geometry_flags=r.get('flags'), pending=pending,
                         accepted_train=False,assigned_split=None)
                f.write(json.dumps(out,ensure_ascii=False)+'\n')
                counts[source]+=1; blockers.update(pending)
    assert len(seen)==55992 and len(seen)==len(disposition)
    report=dict(status='review_queue_only_not_training_manifest',
                updated_utc=datetime.now(timezone.utc).isoformat(),input_sha256=hashes,
                source_counts=dict(counts),records=len(seen),pending_counts=dict(blockers),
                accepted_train=0,gpu=False,
                queue_sha256=hashlib.sha256((OUT/'queue.jsonl').read_bytes()).hexdigest(),
                limitations=['Draft mappings and geometric proposals remain unaccepted.',
                             'Quarantine and historical split restrictions must survive any later acceptance.',
                             'OLID geometry is already reviewed separately; join its decisions rather than repeat visual work.'])
    (OUT/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(records=len(seen),source_counts=dict(counts),accepted_train=0)))

if __name__=='__main__': main()
