"""Compare saved native/deployed DEIM and ONNX on the complete val split, CPU only."""
import copy
import json

import numpy as np
import onnxruntime as ort
import torch
from scipy.optimize import linear_sum_assignment
from faster_coco_eval import COCO, COCOeval_faster

from deim_accuracy import Deploy, Frames, DATA, REPORT, RUNS, SIDE, geometry, make_config, save


def matches(a, b):
    cost = np.abs(a[:, None, :5] - b[None, :, :5]).max(2)
    cost += (a[:, None, 5] != b[None, :, 5]) * 1e6
    rows, cols = linear_sum_assignment(cost)
    aligned = b[cols]
    error = np.abs(a - aligned)
    high = np.maximum(a[:, 4], aligned[:, 4]) >= .1
    return {
        'class_mismatches': int((a[:, 5] != aligned[:, 5]).sum()),
        'max_box_error_px': float(error[:, :4].max()),
        'max_score_error': float(error[:, 4].max()),
        'max_high_score_box_error_px': float(error[high, :4].max()) if high.any() else 0.,
        'strict_pass': bool(np.allclose(a, aligned, atol=.003, rtol=1e-4)),
        'geometry_half_pixel_pass': bool(np.allclose(a[:, :4], aligned[:, :4], atol=.5, rtol=1e-4)),
        'score_pass': bool(np.allclose(a[:, 4], aligned[:, 4], atol=1e-4, rtol=1e-4)),
        'confident_strict_pass': bool(np.allclose(a[high, :5], aligned[high, :5], atol=.003, rtol=1e-4)),
    }


def predictions(rows, item):
    w, h = item['width'], item['height']
    rw, rh, px, py = geometry(w, h)
    rows = rows.copy()
    rows[:, [0, 2]] = (rows[:, [0, 2]] - px) / (rw / w)
    rows[:, [1, 3]] = (rows[:, [1, 3]] - py) / (rh / h)
    rows[:, [0, 2]] = rows[:, [0, 2]].clip(0, w)
    rows[:, [1, 3]] = rows[:, [1, 3]].clip(0, h)
    return [{'image_id': item['id'], 'category_id': int(r[5]), 'score': float(r[4]),
             'bbox': [float(r[0]), float(r[1]), float(r[2]-r[0]), float(r[3]-r[1])]}
            for r in rows if r[4] >= .001 and r[2] > r[0] and r[3] > r[1]]


def ap(results):
    gt = COCO(str(DATA / 'coco_val.json'))
    ev = COCOeval_faster(gt, gt.loadRes(results), 'bbox')
    ev.params.maxDets = [1, 10, 300]
    ev.evaluate()
    ev.accumulate()
    precision = ev.eval['precision'][:, :, :, 0, -1]
    return {'map50_95': float(precision[precision >= 0].mean()),
            'map50': float(precision[0][precision[0] >= 0].mean())}


def main():
    torch.set_num_threads(2)
    run = RUNS / 'deimv2_n_seed20261003'
    cfg = make_config()
    net = cfg.model
    state = torch.load(run / 'best.pt', map_location='cpu', weights_only=False)
    net.load_state_dict(state['model'])
    net.eval()
    post = cfg.postprocessor.eval()
    deploy = Deploy(copy.deepcopy(net), copy.deepcopy(post)).eval()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(run / 'model.onnx'), sess_options=options, providers=['CPUExecutionProvider'])
    frames = Frames('val')
    records = []
    all_predictions = {'native': [], 'deployed_torch': [], 'onnx': []}
    for index, item in enumerate(frames.images):
        value = frames[index][0][None]
        with torch.no_grad():
            result = post(net(value), torch.tensor([[SIDE, SIDE]], dtype=torch.int64))[0]
            native = torch.cat((result['boxes'], result['scores'][:, None], result['labels'][:, None]), 1).numpy()
            deployed = deploy(value).numpy()[0]
        actual = session.run(None, {'images': value.numpy()})[0][0]
        records.append({'file': item['file_name'], 'native_vs_deployed': matches(native, deployed),
                        'deployed_vs_onnx': matches(deployed, actual)})
        for name, rows in [('native', native), ('deployed_torch', deployed), ('onnx', actual)]:
            all_predictions[name].extend(predictions(rows, item))
        if (index + 1) % 50 == 0:
            print(f'DEIM complete val numerical check {index+1}/{len(frames)}', flush=True)
    metrics = {name: ap(rows) for name, rows in all_predictions.items()}
    report = {'scope': 'Complete val, identical PIL RGB inputs, CPU only; compatibility analysis, no test tuning',
              'images': len(frames), 'metrics': metrics, 'samples': records,
              'declared_before_run': {'all_box_atol_px': .5, 'scores_atol': 1e-4,
                  'confident_threshold': .1, 'confident_atol': .003, 'rtol': 1e-4,
                  'note': 'Half input pixel for low-confidence geometry; strict high-confidence check; classes exact'}}
    save(REPORT / 'deim_full_export_diagnostic.json', report)
    for relation in ['native_vs_deployed', 'deployed_vs_onnx']:
        rows = [r[relation] for r in records]
        print(json.dumps({'comparison': relation,
                          'max_box_error_px': max(r['max_box_error_px'] for r in rows),
                          'max_score_error': max(r['max_score_error'] for r in rows),
                          'max_high_score_box_error_px': max(r['max_high_score_box_error_px'] for r in rows),
                          'class_mismatches': sum(r['class_mismatches'] for r in rows),
                          'strict_failed_images': sum(not r['strict_pass'] for r in rows),
                          'half_pixel_failed_images': sum(not r['geometry_half_pixel_pass'] for r in rows),
                          'score_failed_images': sum(not r['score_pass'] for r in rows),
                          'confident_strict_failed_images': sum(not r['confident_strict_pass'] for r in rows)}), flush=True)
    print(json.dumps(metrics), flush=True)


if __name__ == '__main__':
    main()
