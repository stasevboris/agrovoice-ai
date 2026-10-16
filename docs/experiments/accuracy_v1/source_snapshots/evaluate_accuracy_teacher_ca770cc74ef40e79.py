"""CPU-only teacher ceiling under the same photo protocol; no teacher APK mode."""
import argparse
import copy
import json
from pathlib import Path

from evaluate_accuracy_pipeline import ASSETS, REPORT, ROOT, evaluate, read, sha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--seeds', nargs='+', type=int, default=[20261003])
    args = parser.parse_args()
    manifest = read(ASSETS / 'models/manifest.json')
    baseline = next(row for row in manifest['models'] if row['id'] == 'yolo26n')
    core_sha = sha(ROOT / 'ml-pipeline/evaluate_accuracy_pipeline.py')
    for seed in args.seeds:
        kind = 'teacher_efficientnet_v2_s'
        run = ROOT / f'runs/accuracy_v1/{kind}_seed{seed}'
        if not (run / 'export.json').exists():
            print(f'SKIP unfinished teacher seed{seed}', flush=True)
            continue
        info, export = read(run / 'manifest.json'), read(run / 'export.json')
        assert info['status'] == 'completed' and export['status'] == 'passed'
        assert info['seed'] == seed and info['data_sha256'] == sha(ROOT / 'ml-pipeline/dataset_accuracy_v1/manifest.json')
        assert sha(run / 'model.onnx') == export['sha256']
        detector = ROOT / f'runs/detect/study10_v2_yolo26n_seed{seed}/weights/best.onnx'
        model = copy.deepcopy(baseline)
        model.update(id=kind, name='Teacher auxiliary: YOLO26n + EfficientNetV2-S', family='two_stage',
            seed=seed, detector_seed=seed, asset=str(detector), sha256=sha(detector), bytes=detector.stat().st_size,
            source='Independent same-seed detector and trained teacher; internal research auxiliary',
            provisional=True, auxiliary_role='Teacher ceiling; not packaged into APK',
            dataset='clean v2 detector + accuracy_v1 inherited GT crops')
        model['classifier'] = {'id': kind+'_classifier', 'kind': kind, 'asset': str(run/'model.onnx'),
            'sha256': export['sha256'], 'bytes': export['bytes'], 'side': 224, 'seed': seed,
            'source': str(run), 'data_sha256': info['data_sha256'], 'dependency': None,
            'input_shape': [1,3,224,224], 'output_shape': [1,30],
            'normalization': 'ImageNet normalization embedded in graph'}
        target = REPORT / 'pipeline' / f'{kind}_seed{seed}_val.json'
        if target.exists():
            previous = read(target)
            if previous.get('code_sha256') == core_sha and previous.get('launcher_code_sha256') == sha(__file__) and previous['model']['sha256'] == model['sha256'] and previous['model']['classifier']['sha256'] == export['sha256']:
                print('REUSE '+target.name, flush=True)
                continue
        result = evaluate(model, 'val')
        result.update(code_sha256=core_sha, launcher_code_sha256=sha(__file__),
            manifest_sha256=sha(ASSETS/'models/manifest.json'), auxiliary_role='Teacher photo quality, same shared evaluator; not an APK variant')
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'teacher_seed': seed, 'AP': result['disease_detection'],
            'correctly_diagnosed_gt_leaves': result['diagnosis_at_fixed_threshold']['correctly_diagnosed_gt_leaves']}), flush=True)


if __name__ == '__main__':
    main()
