"""Validate DEIM's visible detection set and AP while retaining all raw TopK diagnostics."""
import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from scipy.optimize import linear_sum_assignment
from faster_coco_eval import COCO, COCOeval_faster


def match(reference, candidate, threshold=None):
    a, b = reference, candidate
    if threshold is not None:
        a, b = a[a[:, 4] >= threshold], b[b[:, 4] >= threshold]
    if len(a) != len(b):
        return {'passed': False, 'reference_count': len(a), 'candidate_count': len(b), 'reason': 'Different detection counts'}
    if not len(a):
        return {'passed': True, 'reference_count': 0, 'candidate_count': 0, 'max_absolute_error': 0., 'class_mismatches': 0}
    cost = np.abs(a[:, None, :5] - b[None, :, :5]).max(2)
    cost += (a[:, None, 5] != b[None, :, 5]) * 1e6
    _, columns = linear_sum_assignment(cost)
    aligned = b[columns]
    error = np.abs(a - aligned)
    return {'passed': bool(np.allclose(a, aligned, atol=.003, rtol=1e-4)),
            'reference_count': len(a), 'candidate_count': len(b),
            'max_absolute_error': float(error.max()), 'max_box_error_px': float(error[:, :4].max()),
            'max_score_error': float(error[:, 4].max()), 'class_mismatches': int((a[:, 5] != aligned[:, 5]).sum())}


def validate(native, post, deploy, frames, graph, coco_file, geometry, side):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(graph), sess_options=options, providers=['CPUExecutionProvider'])
    all_predictions = {name: [] for name in ['native', 'deployed_torch', 'onnx']}
    records = []
    sizes = torch.tensor([[side, side]], dtype=torch.int64)
    for index, item in enumerate(frames.images):
        value = frames[index][0][None]
        with torch.no_grad():
            result = post(native(value), sizes)[0]
            reference = torch.cat((result['boxes'], result['scores'][:, None], result['labels'][:, None]), 1).numpy()
            deployed = deploy(value).numpy()[0]
        actual = session.run(None, {'images': value.numpy()})[0][0]
        assert all(np.isfinite(rows).all() for rows in [reference, deployed, actual]), 'Non-finite DEIM output'
        records.append({'file': item['file_name'],
            'visible_native_vs_deployed': match(reference, deployed, .1),
            'visible_deployed_vs_onnx': match(deployed, actual, .1),
            'raw_native_vs_deployed': match(reference, deployed),
            'raw_deployed_vs_onnx': match(deployed, actual)})
        w, h = item['width'], item['height']
        rw, rh, px, py = geometry(w, h)
        for name, rows in [('native', reference), ('deployed_torch', deployed), ('onnx', actual)]:
            rows = rows.copy()
            rows[:, [0, 2]] = ((rows[:, [0, 2]] - px) / (rw / w)).clip(0, w)
            rows[:, [1, 3]] = ((rows[:, [1, 3]] - py) / (rh / h)).clip(0, h)
            all_predictions[name].extend({'image_id': item['id'], 'category_id': int(r[5]), 'score': float(r[4]),
                 'bbox': [float(r[0]), float(r[1]), float(r[2]-r[0]), float(r[3]-r[1])]}
                for r in rows if r[4] >= .001 and r[2] > r[0] and r[3] > r[1])
        if (index + 1) % 50 == 0:
            print(f'DEIM export parity {index+1}/{len(frames)}', flush=True)
    metrics = {}
    for name, predictions in all_predictions.items():
        gt = COCO(str(coco_file))
        if not predictions:
            metrics[name] = {'map50_95': 0., 'map50': 0.}
            continue
        ev = COCOeval_faster(gt, gt.loadRes(predictions), 'bbox')
        ev.params.maxDets = [1, 10, 300]
        ev.evaluate()
        ev.accumulate()
        p = ev.eval['precision'][:, :, :, 0, -1]
        metrics[name] = {'map50_95': float(p[p >= 0].mean()), 'map50': float(p[0][p[0] >= 0].mean())}
    delta = max(abs(metrics[name][key] - metrics['native'][key])
                for name in ['deployed_torch', 'onnx'] for key in ['map50_95', 'map50'])
    failed = [r['file'] for r in records if not r['visible_native_vs_deployed']['passed']
              or not r['visible_deployed_vs_onnx']['passed']]
    return {'status': 'passed' if not failed and delta <= 1e-4 else 'failed',
            'images': len(frames), 'metrics': metrics, 'maximum_ap_delta': delta,
            'visible_failed_images': failed, 'samples': records,
            'policy': {'minimum_visible_score': .1, 'visible_atol': .003, 'visible_rtol': 1e-4,
                       'maximum_ap_delta': 1e-4, 'ap_minimum_score': .001, 'max_detections': 300,
                       'scope': 'All val images, unchanged raw graph. Strict unordered class/box/score parity at APK minimum threshold; complete AP preserves low-score rows. Raw background TopK deviations retained, not claimed to be identical.'},
            'graph_sha256': hashlib.sha256(Path(graph).read_bytes()).hexdigest(),
            'validation_code_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
