"""Fail-closed data/plan validation for a future S series; never starts training."""
from __future__ import annotations
import argparse, hashlib, json, math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUDY = ROOT/'docs/experiments/yolo26s_data_v1'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def validate():
    problems = []
    lock_path = STUDY/'protocol_lock.json'
    plan_path = STUDY/'plan.json'
    if not lock_path.is_file() or not plan_path.is_file():
        return ['Final audited dataset and frozen plan are not ready; draft is not a training permit.']
    lock, plan = read(lock_path), read(plan_path)
    if lock.get('status') != 'locked_before_training' or lock.get('plan_sha256') != sha(plan_path):
        problems.append('Plan lock missing or changed')
    data = ROOT/plan['dataset_directory']
    required = ('manifest.json', 'report.json', 'classes.json', 'data.yaml')
    if any(not (data/n).is_file() for n in required):
        return problems + ['Prepared dataset files are missing']
    report, records, classes = read(data/'report.json'), read(data/'manifest.json'), read(data/'classes.json')
    old_classes = read(ROOT/'ml-pipeline/dataset_clean_v2/classes.json')
    # IDs 0..28 are the original PlantDoc classes. Legacy ID29 only says
    # cucumber/crop unknown, not a disease: forcing it into a new diagnosis
    # head would contradict correctly reviewed cucumber healthy labels.
    original_classes = old_classes[:29]
    if classes[:29] != original_classes:
        problems.append('All 29 original PlantDoc classes and IDs must remain intact')
    if len(set(classes)) != len(classes) or any(not isinstance(c, str) or not c for c in classes):
        problems.append('Class names must be nonempty and unique')
    if old_classes[29] in classes:
        problems.append('Legacy cucumber crop-only unknown must not compete with diagnosis classes')
    if not report.get('legacy_cucumber_migration_review_passed'):
        problems.append('Legacy cucumber labels need reviewed migration or explicit exclusion')
    if report.get('status') != 'audited_ready' or report.get('fingerprint') != sha(data/'manifest.json'):
        problems.append('Dataset audit/fingerprint invalid')
    if report.get('fingerprint') != lock.get('dataset_fingerprint'):
        problems.append('Dataset differs from locked recipe')
    if not report.get('new_source_annotation_review_passed'):
        problems.append('Whole-leaf review of new sources not passed')
    names, groups = set(), {'train': set(), 'val': set(), 'test': set()}
    train_classes = set()
    for row in records:
        split, name = row['split'], row['filename']
        if split not in groups:
            continue
        if Path(name).name != name or (split, name) in names:
            problems.append('Unsafe or duplicate prepared filename')
            continue
        names.add((split, name))
        image, label = data/'images'/split/name, data/'labels'/split/(Path(name).stem+'.txt')
        if not image.is_file() or not label.is_file():
            problems.append(f'Missing image/label: {split}/{name}')
            continue
        if sha(image) != row['prepared_sha256'] or sha(label) != row['label_sha256']:
            problems.append(f'File changed: {split}/{name}')
        group = row.get('group_id')
        if not group:
            problems.append(f'Missing grouped provenance: {name}')
        else:
            groups[split].add(group)
        for line in label.read_text(encoding='utf-8-sig').splitlines():
            parts = line.split()
            if len(parts) != 5:
                problems.append(f'Invalid label: {name}')
                continue
            try:
                cls, x, y, w, h = map(float, parts)
            except ValueError:
                problems.append(f'Invalid numeric label: {name}')
                continue
            if (not all(math.isfinite(v) for v in (cls, x, y, w, h))
                    or not cls.is_integer() or not 0<=cls<len(classes) or not 0<w<=1 or not 0<h<=1
                    or x-w/2<-.00001 or y-h/2<-.00001 or x+w/2>1.00001 or y+h/2>1.00001):
                problems.append(f'Invalid box or class: {name}')
                continue
            if split == 'train':
                train_classes.add(int(cls))
    if set(range(len(original_classes))) - train_classes:
        problems.append('One or more original PlantDoc classes lost all training examples')
    if any(groups[a] & groups[b] for a,b in [('train','val'),('train','test'),('val','test')]):
        problems.append('Physical/duplicate groups overlap splits')
    if not report.get('new_condition_validation_support_passed'):
        problems.append('New-condition validation coverage not passed')
    if not report.get('test_sealed_before_model_selection'):
        problems.append('Test split not sealed before model selection')
    for name, digest in lock.get('protected_sources', {}).items():
        if sha(ROOT/'ml-pipeline'/name) != digest:
            problems.append(f'Protected source changed: {name}')
    return sorted(set(problems))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence', type=Path)
    args = p.parse_args()
    problems = validate()
    result = {'status': 'blocked' if problems else 'passed', 'training_started': False, 'problems': problems}
    if args.evidence:
        args.evidence.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result))
    raise SystemExit(2 if problems else 0)


if __name__ == '__main__':
    main()
