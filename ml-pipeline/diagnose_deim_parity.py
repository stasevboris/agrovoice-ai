"""CPU-only numerical diagnostics for a saved DEIM checkpoint; no training changes."""
import copy
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from scipy.optimize import linear_sum_assignment

from deim_accuracy import Deploy, Frames, REPORT, RUNS, make_config, save


def compare(reference, candidate):
    a, b = reference[0], candidate[0]
    distance = np.abs(a[:, None, :5] - b[None, :, :5]).max(2)
    cost = distance + (a[:, None, 5] != b[None, :, 5]) * 1e6
    rows, cols = linear_sum_assignment(cost)
    aligned = b[cols]
    error = np.abs(a - aligned)
    high = a[:, 4] >= .1
    return {
        'strict_pass': bool(np.allclose(a, aligned, atol=.003, rtol=1e-4)),
        'max_columns': error.max(0).tolist(),
        'max_high_score_columns': error[high].max(0).tolist() if high.any() else None,
        'high_score_rows': int(high.sum()),
        'class_mismatches': int((a[:, 5] != aligned[:, 5]).sum()),
        'rows_outside_tolerance': int((~np.isclose(a, aligned, atol=.003, rtol=1e-4)).any(1).sum()),
    }


def main():
    torch.set_num_threads(2)
    run = RUNS / 'deimv2_n_seed20261003'
    state = torch.load(run / 'best.pt', map_location='cpu', weights_only=False)
    cfg = make_config()
    net = cfg.model
    net.load_state_dict(state['model'])
    deploy = Deploy(net.eval(), cfg.postprocessor.eval()).eval()
    frames = Frames('val')
    report = {'scope': 'CPU only; saved best checkpoint; diagnostics without altered tolerances', 'samples': []}
    sessions = {}
    for label, level in [('disabled', ort.GraphOptimizationLevel.ORT_DISABLE_ALL),
                         ('basic', ort.GraphOptimizationLevel.ORT_ENABLE_BASIC),
                         ('all', ort.GraphOptimizationLevel.ORT_ENABLE_ALL)]:
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.graph_optimization_level = level
        sessions[label] = ort.InferenceSession(str(run / 'model.onnx'), sess_options=options,
                                               providers=['CPUExecutionProvider'])
    for index in range(3):
        value = frames[index][0][None]
        torch.backends.mkldnn.enabled = True
        with torch.no_grad():
            expected = deploy(value).numpy()
        torch.backends.mkldnn.enabled = False
        with torch.no_grad():
            unfused = deploy(value).numpy()
        sample = {'file': frames.images[index]['file_name'], 'torch_mkldnn_vs_native': compare(expected, unfused)}
        for label, session in sessions.items():
            actual = session.run(None, {'images': value.numpy()})[0]
            sample[label] = compare(expected, actual)
            sample[label + '_vs_torch_native'] = compare(unfused, actual)
        report['samples'].append(sample)
    torch.backends.mkldnn.enabled = True
    save(REPORT / 'deim_export_failure_diagnostic.json', report)
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
