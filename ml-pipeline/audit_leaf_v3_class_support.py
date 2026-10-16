"""Report candidate support without converting draft labels into accepted data."""
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT/'docs/experiments/yolo26s_data_v1'
OUT = BASE/'class_support_r1'

def main():
    OUT.mkdir(exist_ok=False)
    hashes = {}
    def read(path, lines=False):
        raw = path.read_bytes()
        hashes[str(path.relative_to(ROOT))] = hashlib.sha256(raw).hexdigest()
        return [json.loads(x) for x in raw.splitlines()] if lines else json.loads(raw)
    mapping = read(BASE/'source_class_mapping.draft.json')
    classes = read(ROOT/'ml-pipeline/dataset_clean_v2/classes.json')
    proposed = {x['source_class']: x for x in mapping['PlantVillage_mapping']}
    assert len(proposed) == 29
    assert sorted(x['proposed_existing_id'] for x in proposed.values()) == list(range(29))
    for row in proposed.values():
        assert classes[row['proposed_existing_id']] == row['proposed_existing_name']
    constraints = {(r['source'], r['id']): r for r in read(
        BASE/'group_closure_r1/candidate_split_constraints.jsonl', True)}
    records = []
    for source, folder in [('PlantVillage','plantvillage_full_geometry_r1'),
                           ('PlantVillage','plantvillage_extra_conditions_r1')]:
        for r in read(BASE/folder/'records.jsonl', True):
            records.append((source, r))
    by_class = defaultdict(list)
    for source, r in records: by_class[r['source_class']].append((source, r))
    rows = []
    for name, items in sorted(by_class.items()):
        restrictions = Counter(); flagged = 0; groups = set(); missing = 0
        for source, r in items:
            c = constraints.get((source, r['id']))
            if c is None: missing += 1
            else: restrictions[c['split_rule']] += 1
            flagged += bool(r.get('flags'))
            group = r.get('physical_leaf_group_candidate')
            if group: groups.add(group)
        m = proposed.get(name)
        rows.append(dict(source_class=name, candidate_originals=len(items),
                         proposed_existing_id=m['proposed_existing_id'] if m else None,
                         proposed_existing_name=m['proposed_existing_name'] if m else None,
                         semantics_status='draft_mapping_requires_source_confirmation' if m else 'new_condition_requires_semantic_review',
                         geometry_flagged=flagged, publisher_group_labels=len(groups),
                         group_labels_are_not_verified_independent_plants=True,
                         split_constraints=dict(restrictions), missing_constraint_records=missing,
                         accepted_train=0, accepted_val=0, accepted_test=0))
    assert len(rows) == 38 and sum(r['candidate_originals'] for r in rows) == 54305
    assert all(r['missing_constraint_records'] == 0 for r in rows)
    report = dict(status='candidate_support_only_not_training_ready',
                  updated_utc=datetime.now(timezone.utc).isoformat(), input_sha256=hashes,
                  preserved_ids=list(range(29)), candidate_originals=54305, rows=rows,
                  limitations=['Counts are audited originals, not independent plants or accepted labels.',
                               'Draft name correspondence is not proof of diagnostic equivalence.',
                               'No model, frozen source, label, or split was modified.'])
    (OUT/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    text = ['# PlantVillage candidate class support', '',
            'All 29 original IDs/name positions verified against frozen classes. No training acceptance.', '',
            '| Publisher category | Originals | Draft old ID | Geometry flags |',
            '|---|---:|---:|---:|']
    for r in rows:
        old = r['proposed_existing_id']
        text.append(f"| {r['source_class']} | {r['candidate_originals']} | {old if old is not None else 'new/pending'} | {r['geometry_flagged']} |")
    text += ['', 'See report.json for per-class split restrictions and input SHA-256. Publisher groups are not verified independent plants; diagnoses and whole-leaf annotations remain under review.']
    (OUT/'README.md').write_text('\n'.join(text)+'\n', encoding='utf-8')
    print(json.dumps(dict(categories=len(rows), candidate_originals=54305,
                         preserved_ids=29, training_accepted=0)))

if __name__ == '__main__': main()
