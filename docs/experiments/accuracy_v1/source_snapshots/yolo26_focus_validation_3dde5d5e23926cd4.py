"""Read-only guards for the explicitly authorized ten-S study, never the old 80 jobs."""
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FOCUS = ROOT / 'docs/experiments/yolo26s_focus_v1'
REPORT = ROOT / 'docs/experiments/accuracy_v1'
ASSETS = ROOT / 'android-app/app/src/main/assets'
SEEDS = list(range(20261003, 20261013))
PLAN_SHA = 'bc0d5491639b6b1ec54f8fad95e1d25a1c6f53df38b9d534391768bd271426a0'
CORE_SHA = '84d1b8cb13094aac55baaf23ae81f252e8f10828f3662415254a5dcc82a0127a'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def plan_and_examples():
    assert sha(FOCUS / 'plan.json') == PLAN_SHA, 'Frozen focus plan changed'
    plan = read(FOCUS / 'plan.json')
    assert plan['seeds'] == SEEDS
    assert plan['jobs'] == [{'id': f'yolo26s_seed{s}', 'kind': 'yolo26s', 'seed': s} for s in SEEDS]
    assert sha(ROOT / 'ml-pipeline/dataset_accuracy_v1/manifest.json') == plan['data_sha256']
    assert sha(REPORT / 'architecture_plan.json') == plan['superseded_plan_sha256']
    for key in ['trainer', 'adapter']:
        assert sha(ROOT / plan['recipe'][key]) == plan['recipe'][key + '_sha256']
    assert sha(ROOT / 'ml-pipeline/evaluate_accuracy_pipeline.py') == CORE_SHA
    examples_file = FOCUS / 'pilot_apk_manifest_0.8.json'
    assert sha(examples_file) == plan['preserve_example_manifest_sha256']
    examples = read(examples_file)
    assert len(examples['models']) == 8
    assert next(m for m in examples['models'] if m['id'] == 'yolo26n') == plan['baseline']
    return plan, examples


def full_val(kind, seed, digest):
    file = REPORT / 'pipeline' / f'{kind}_seed{seed}_val.json'
    assert file.is_file(), f'Missing full shared val: {file.name}'
    row = read(file)
    assert not row['partial'] and row['split'] == 'val' and row['images'] == 349
    assert row['diagnosis_at_fixed_threshold']['gt_leaves'] == 1309
    assert row['code_sha256'] == CORE_SHA, 'Stale evaluation code'
    assert row['model']['id'] == kind and row['model']['seed'] == seed
    assert row['model']['sha256'] == digest, 'Evaluation belongs to different weights'
    return row, file


def cohort_selection():
    plan, examples = plan_and_examples()
    queue = read(FOCUS / 'status.json')
    assert queue['status'] == 'completed' and queue['completed'] == 10, 'Finish all ten YOLO26s exports before selection/test'
    assert queue['plan_sha256'] == PLAN_SHA
    assert set(queue['jobs']) == {j['id'] for j in plan['jobs']}
    source_fp = read(ROOT / 'ml-pipeline/dataset_accuracy_v1/manifest.json')['source_fingerprint']
    old = read(ROOT / 'docs/experiments/study10_v2/status.json')
    assert old['status'] == 'completed' and old['completed'] == 20
    assert old['selected'] == 'study10_v2_yolo26n_seed20261012'
    assert old['dataset_fingerprint'] == source_fp
    assessed = []
    for seed in SEEDS:
        run = ROOT / f'runs/accuracy_v1/yolo26s_seed{seed}'
        info, export = read(run / 'manifest.json'), read(run / 'export.json')
        assert queue['jobs'][run.name]['status'] == 'completed'
        assert info['status'] == 'completed' and info['kind'] == 'yolo26s' and info['seed'] == seed
        assert info['code_sha256'] == plan['recipe']['adapter_sha256']
        assert info['epochs'] == 60 and info['input'] == 416 and info['batch'] == 16
        source = info['source_manifest']
        assert source['status'] == 'trained' and source['seed'] == seed
        assert source['dataset_fingerprint'] == source_fp
        assert source['amp_effective'] is False and source['optimizer'] == 'AdamW' and source['lr0'] == .001
        assert export['status'] == 'passed' and sha(run / 'model.onnx') == export['sha256']
        assert export['input_shape'] == [1, 3, 416, 416] and export['output_shape'] == [1, 34, 3549]
        native = read(ROOT / f'runs/detect/accuracy_v1_yolo26s_seed{seed}/evaluation.json')
        assert native['completed_epochs'] == 60 and len(native['onnx_comparison']) == 3
        row, file = full_val('yolo26s', seed, export['sha256'])
        n_run = ROOT / f'runs/detect/study10_v2_yolo26n_seed{seed}'
        n_info = read(n_run / 'run_manifest.json')
        assert n_info['seed'] == seed and n_info['dataset_fingerprint'] == source_fp and n_info['status'] == 'trained'
        n_row, n_file = full_val('yolo26n', seed, sha(n_run / 'weights/best.onnx'))
        assessed.append({'seed': seed, 'ap': row['disease_detection']['map50_95'],
                         'graph_sha256': export['sha256'], 'bytes': export['bytes'],
                         'val_sha256': sha(file), 'paired_n_val_sha256': sha(n_file),
                         'paired_ap_delta': row['disease_detection']['map50_95'] - n_row['disease_detection']['map50_95']})
    # Frozen tie rule: higher shared AP, then lower seed. No test-based selection.
    best = max(assessed, key=lambda r: (r['ap'], -r['seed']))
    selected = copy.deepcopy(next(m for m in examples['models'] if m['id'] == 'yolo26s'))
    selected.update(seed=best['seed'], detector_seed=best['seed'], sha256=best['graph_sha256'],
                    bytes=best['bytes'], provisional=False, completed_runs=10, paired_evaluations=10,
                    selection='shared full-val end-to-end AP50-95; ten seeds; lower seed breaks ties',
                    source=f'runs/accuracy_v1/yolo26s_seed{best["seed"]}')
    return {'study': 'yolo26s_focus_v1', 'plan_sha256': PLAN_SHA, 'data_sha256': plan['data_sha256'],
            'validation_code_sha256': CORE_SHA, 'preserved_examples_sha256': plan['preserve_example_manifest_sha256'],
            'models': {'yolo26n': plan['baseline'], 'yolo26s': selected}, 'cohort': assessed,
            'rule': 'Fixed historical YOLO26n12; select YOLO26s on all ten shared val reports before reading test; no resumed DETR or other example cohorts'}


def check_preserved_models(manifest, examples, selected=None):
    assert [m['id'] for m in manifest['models']] == [m['id'] for m in examples['models']]
    for model, original in zip(manifest['models'], examples['models']):
        if model['id'] == 'yolo26s':
            if selected is not None:
                assert model == selected
            continue
        assert model == original, f'Example/fixed baseline changed: {model["id"]}'
        for item in [model, model.get('classifier')]:
            if item:
                assert sha(ASSETS / item['asset']) == item['sha256']
    for key in ['classes', 'labels_ru', 'dataset_fingerprint', 'two_stage_policy']:
        assert manifest[key] == examples[key]
