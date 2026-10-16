"""Collect Android timings and verify its first raw output against desktop ONNX."""
from pathlib import Path
import argparse
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    import numpy as np
    import onnxruntime as ort
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--model', action='append', required=True, help='LABEL=local.onnx')
    args = parser.parse_args()
    adb = Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'
    output = ROOT/'ml-pipeline/.cache/phone_benchmark/results'
    output.mkdir(parents=True, exist_ok=True)
    records = []
    for mapping in args.model:
        label, model_path = mapping.split('=', 1)
        remote = '/sdcard/Android/data/com.agronomy.phytomonitor/files/benchmark/'
        for filename in (label+'.json', label+'_output.bin'):
            subprocess.run([str(adb), '-s', args.serial, 'pull', remote+filename, str(output/filename)], check=True)
        record = json.loads((output/(label+'.json')).read_text(encoding='utf-8'))
        if record['status'] != 'passed':
            raise RuntimeError(record)
        shape = record['input_shape']
        batch = np.fromfile(ROOT/f'ml-pipeline/.cache/phone_benchmark/input_{shape[-1]}_0.bin', dtype='<f4').reshape(shape)
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        session = ort.InferenceSession(model_path, options, providers=['CPUExecutionProvider'])
        reference = session.run(None, {session.get_inputs()[0].name: batch})[0]
        actual = np.fromfile(output/(label+'_output.bin'), dtype='<f4').reshape(record['output_shape'])
        if not np.isfinite(actual).all() or actual.shape != reference.shape:
            raise RuntimeError('Android output shape or finite-value check failed.')
        if actual.shape[-1] == 6:
            ref = reference[0][reference[0, :, 4] >= 0.001]
            act = actual[0][actual[0, :, 4] >= 0.001]
            if len(ref) != len(act):
                raise RuntimeError('Android NMS-free detection counts differ.')
            remaining = list(range(len(act)))
            errors = []
            for detection in ref:
                matches = [i for i in remaining if act[i, 5] == detection[5]
                           and np.allclose(act[i, :5], detection[:5], rtol=1e-4, atol=2e-3)]
                if not matches:
                    raise RuntimeError('Android NMS-free detection differs from desktop.')
                errors.append(np.abs(act[matches[0], :5]-detection[:5]))
                remaining.remove(matches[0])
            comparison = dict(matched_detections_above_0_001=len(ref),
                              matched_max_absolute_error=float(np.max(errors)) if errors else 0.0)
        else:
            if not np.allclose(actual, reference, rtol=1e-4, atol=2e-3):
                raise RuntimeError(f'Android differs from desktop: {np.abs(actual-reference).max()}')
            comparison = dict(max_absolute_error=float(np.abs(actual-reference).max()),
                              mean_absolute_error=float(np.abs(actual-reference).mean()))
        record['desktop_output_check'] = dict(status='passed', samples=1, **comparison)
        records.append(record)
    report = dict(device='Redmi Note 7, 6/64, Android 13', backend='CPU FP32, 4 threads, no NNAPI',
                  limitation='Five public validation tensors cycled. 10 warmups, 40 measurements. Camera/resize excluded; brief benchmark, not sustained field use.',
                  models=records)
    destination = ROOT/'docs/experiments/redmi_note_7_benchmark.json'
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(destination)


if __name__ == '__main__':
    main()
