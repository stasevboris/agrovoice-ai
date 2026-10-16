"""Validate a completed run and its export; use --test only after model selection."""
from __future__ import annotations
import argparse
import ast
import csv
import json
import os
from pathlib import Path
import platform
import shutil

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault('YOLO_CONFIG_DIR', str(ROOT/'ml-pipeline/.cache/ultralytics'/platform.system().lower()))
(Path(os.environ['YOLO_CONFIG_DIR'])/'Ultralytics').mkdir(parents=True, exist_ok=True)


def serialise_metrics(metrics, names):
    by_class = {int(cls): dict(precision=float(metrics.box.p[i]), recall=float(metrics.box.r[i]),
                              map50=float(metrics.box.ap50[i]), map50_95=float(metrics.box.ap[i]))
                for i, cls in enumerate(metrics.box.ap_class_index)}
    return dict(overall={key: float(value) for key, value in metrics.results_dict.items()},
                classes=[dict(id=cls, name=name, metrics=by_class.get(cls)) for cls, name in names.items()])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, default=ROOT/'runs/detect/plantdoc_clean_v1_yolo26n_416')
    parser.add_argument('--device', default='cpu')
    parser.add_argument('--data', type=Path)
    parser.add_argument('--imgsz', type=int, help='Try a different inference resolution without retraining')
    parser.add_argument('--nms-free', action='store_true', help='Validate/export the YOLO26 one-to-one head')
    parser.add_argument('--test', action='store_true', help='Evaluate the held-out test after selection')
    parser.add_argument('--aux', action='store_true', help='Evaluate supplemental held-out groups separately')
    args = parser.parse_args()
    if args.aux and args.test:
        parser.error('Auxiliary validation is separate from the primary held-out test.')
    import cv2
    import numpy as np
    import onnx
    import onnxruntime as ort
    import torch
    import yaml
    from ultralytics import YOLO, settings
    from ultralytics.data.augment import LetterBox
    torch.set_num_threads(8)
    settings.update({'sync': False})
    run = args.run.resolve()
    manifest = json.loads((run/'run_manifest.json').read_text(encoding='utf-8'))
    if manifest['status'] != 'trained':
        raise SystemExit('Wait for completed training before final evaluation.')
    data = (args.data or ROOT/'ml-pipeline'/manifest['dataset_name']).resolve()
    report = json.loads((data/'report.json').read_text(encoding='utf-8'))
    if report['fingerprint'] != manifest['dataset_fingerprint']:
        raise SystemExit('Dataset fingerprint differs from the trained run.')
    config = yaml.safe_load((data/'data.yaml').read_text(encoding='utf-8'))
    if args.aux:
        if 'val_aux' not in report['splits']:
            parser.error('Dataset has no supplementary validation split.')
        config['val'] = 'images/val_aux'
    config['path'] = str(data)
    runtime = ROOT/'ml-pipeline/.cache'/('evaluation_'+report['fingerprint'][:16]+('_aux' if args.aux else '')+'_'+platform.system().lower()+'.yaml')
    runtime.parent.mkdir(parents=True, exist_ok=True)
    runtime.write_text(yaml.safe_dump(config), encoding='utf-8')
    model = YOLO(str(run/'weights/best.pt'))
    trained_size = int(manifest['image_size'])
    size = args.imgsz or trained_size
    if size < 32 or size % 32:
        parser.error('--imgsz must be a positive multiple of 32.')
    resolution_suffix = f'_{size}' if size != trained_size else ''
    mode = 'nms_free' if args.nms_free else 'external_nms'
    validation_options = {'nms': False} if args.nms_free else {}
    onnx_path = run/'weights/best.onnx'
    if args.nms_free or size != trained_size:
        if args.nms_free and not hasattr(model.model.model[-1], 'one2one_cv2'):
            raise SystemExit('This checkpoint has no one-to-one detection head.')
        export_dir = run/'weights'/('nms_free' if args.nms_free and size == trained_size else f'export_{size}_{mode}')
        export_dir.mkdir(parents=True, exist_ok=True)
        export_weights = export_dir/'best.pt'
        shutil.copyfile(run/'weights/best.pt', export_weights)
        onnx_path = export_dir/'best.onnx'
        if not onnx_path.exists():
            YOLO(str(export_weights)).export(format='onnx', imgsz=size, dynamic=False,
                                           opset=17, device='cpu', **validation_options)
    model.model.end2end = args.nms_free
    if not onnx_path.exists():
        raise SystemExit('The final ONNX export is missing.')
    onnx.checker.check_model(str(onnx_path))
    session = ort.InferenceSession(str(onnx_path), providers=['CPUExecutionProvider'])
    metadata = session.get_modelmeta().custom_metadata_map
    if ast.literal_eval(metadata.get('names', '{}')) != model.names:
        raise SystemExit('ONNX class order differs from PyTorch.')
    model.model.to('cpu').float().eval()
    comparisons = []
    for path in sorted((data/'images/val').glob('*.jpg'))[:3]:
        frame = LetterBox(new_shape=(size, size), auto=False)(image=cv2.imread(str(path)))
        batch = np.ascontiguousarray(frame.transpose(2, 0, 1)[::-1][None]).astype(np.float32)/255
        with torch.inference_mode():
            reference = model.model(torch.from_numpy(batch))
            if isinstance(reference, tuple):
                reference = reference[0]
            reference = reference.cpu().numpy()
        actual = session.run(None, {session.get_inputs()[0].name: batch})[0]
        if actual.shape != reference.shape or not np.isfinite(actual).all():
            raise RuntimeError('ONNX output shape or finite-value check failed.')
        difference = np.abs(reference-actual)
        comparisons.append(dict(image=path.name, shape=list(actual.shape),
                                max_absolute_error=float(difference.max()),
                                mean_absolute_error=float(difference.mean())))
        if args.nms_free:
            # Top-k ordering can differ for ties. Match each meaningful detection by
            # class and coordinates rather than comparing its row position.
            ref = reference[0][reference[0, :, 4] >= 0.001]
            act = actual[0][actual[0, :, 4] >= 0.001]
            if len(ref) != len(act):
                raise RuntimeError('NMS-free exports disagree on the number of detections above 0.001.')
            remaining = list(range(len(act)))
            matched_errors = []
            for detection in ref:
                matches = [i for i in remaining if act[i, 5] == detection[5]
                           and np.allclose(act[i, :5], detection[:5], rtol=1e-4, atol=2e-3)]
                if not matches:
                    raise RuntimeError(f'NMS-free ONNX detection differs for {path.name}.')
                matched_errors.append(np.abs(act[matches[0], :5]-detection[:5]))
                remaining.remove(matches[0])
            comparisons[-1]['raw_rowwise_max_absolute_error'] = comparisons[-1].pop('max_absolute_error')
            comparisons[-1]['raw_rowwise_mean_absolute_error'] = comparisons[-1].pop('mean_absolute_error')
            comparisons[-1]['matched_max_absolute_error'] = float(np.max(matched_errors)) if matched_errors else 0.0
            comparisons[-1]['matched_detections_above_0_001'] = len(ref)
        elif not np.allclose(actual, reference, rtol=1e-4, atol=2e-3):
            raise RuntimeError(f'ONNX differs from PyTorch for {path.name}: {difference.max()}')
    val = model.val(data=str(runtime), split='val', device=args.device, imgsz=size,
                    project=str(run), name='final_val_'+mode+resolution_suffix+('_aux' if args.aux else ''), exist_ok=True, **validation_options)
    result = dict(dataset_fingerprint=report['fingerprint'], run=run.name,
                  annotation_target=report['annotation_target'], image_size=size,
                  trained_image_size=trained_size,
                  onnx_bytes=onnx_path.stat().st_size, pytorch_bytes=(run/'weights/best.pt').stat().st_size,
                  mode=mode, onnx_path=str(onnx_path.relative_to(run)),
                  model_architecture=manifest.get('model_architecture'),
                  validation_split='val_aux' if args.aux else 'val', seed=manifest.get('seed'),
                  onnx_comparison=comparisons, validation=serialise_metrics(val, model.names))
    suffix = resolution_suffix+('_nms_free' if args.nms_free else '')+('_aux' if args.aux else '')
    test_path = run/f'test_metrics{suffix}.json'
    if test_path.exists():
        # The held-out test is evaluated once for the final chosen run.
        result['test'] = json.loads(test_path.read_text(encoding='utf-8'))
    elif args.test:
        test = model.val(data=str(runtime), split='test', device=args.device, imgsz=size,
                         project=str(run), name='held_out_test_'+mode, exist_ok=True, **validation_options)
        result['test'] = serialise_metrics(test, model.names)
        result['test']['absent_classes'] = report['splits']['test']['absent_classes']
        test_path.write_text(json.dumps(result['test'], ensure_ascii=False, indent=2), encoding='utf-8')
    with (run/'results.csv').open(encoding='utf-8') as handle:
        result['completed_epochs'] = len(list(csv.DictReader(handle)))
    target = run/f'evaluation{suffix}.json'
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    destination = ROOT/'docs/experiments'/f'{run.name}{suffix}.json'
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(target, destination)
    print(json.dumps(dict(report=str(destination), validation=result['validation']['overall'],
                          test=result.get('test', {}).get('overall', result.get('test', {}).get('metrics')),
                          onnx_comparison=comparisons), indent=2))


if __name__ == '__main__':
    main()
