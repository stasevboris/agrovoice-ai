"""Benchmark ONNX on the desktop CPU in isolated processes, never as a phone estimate."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import platform
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def postprocess(output, confidence=0.25, iou_threshold=0.7, max_detections=300):
    import numpy as np
    if output.shape[-1] == 6:  # YOLO26 one-to-one: xyxy, score, class
        return output[0][output[0, :, 4] >= confidence][:max_detections]
    candidates = output[0].T
    classes = candidates[:, 4:].argmax(axis=1)
    scores = candidates[np.arange(len(candidates)), classes+4]
    selected = scores >= confidence
    boxes = candidates[selected, :4].copy()
    scores, classes = scores[selected], classes[selected]
    boxes[:, :2] -= boxes[:, 2:]/2
    boxes[:, 2:] += boxes[:, :2]
    order = scores.argsort()[::-1][:30000]
    area = np.maximum(0, boxes[:, 2]-boxes[:, 0])*np.maximum(0, boxes[:, 3]-boxes[:, 1])
    keep = []
    while len(order) and len(keep) < max_detections:
        index, order = order[0], order[1:]
        keep.append(index)
        if not len(order):
            break
        top_left = np.maximum(boxes[index, :2], boxes[order, :2])
        bottom_right = np.minimum(boxes[index, 2:], boxes[order, 2:])
        wh = np.maximum(0, bottom_right-top_left)
        intersection = wh[:, 0]*wh[:, 1]
        iou = intersection/(area[index]+area[order]-intersection+1e-9)
        order = order[(classes[order] != classes[index]) | (iou <= iou_threshold)]
    return np.column_stack((boxes[keep], scores[keep], classes[keep])).astype(np.float32)


def worker(args):
    import cv2
    import numpy as np
    import onnxruntime as ort
    import psutil
    process = psutil.Process()
    baseline = process.memory_info().rss
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    session = ort.InferenceSession(str(args.worker), options, providers=['CPUExecutionProvider'])
    input_info = session.get_inputs()[0]
    height, width = input_info.shape[-2:]
    images = sorted(args.images.glob('*.jpg'))[:5]
    if not images:
        raise RuntimeError('No benchmark images.')
    inputs = []
    for image_path in images:
        image = cv2.imread(str(image_path))
        scale = min(width/image.shape[1], height/image.shape[0])
        resized = cv2.resize(image, (round(image.shape[1]*scale), round(image.shape[0]*scale)))
        frame = np.full((height, width, 3), 114, dtype=np.uint8)
        y, x = (height-resized.shape[0])//2, (width-resized.shape[1])//2
        frame[y:y+resized.shape[0], x:x+resized.shape[1]] = resized
        inputs.append(np.ascontiguousarray(frame.transpose(2, 0, 1)[::-1][None], dtype=np.float32)/255)
    for i in range(args.warmup):
        output = session.run(None, {input_info.name: inputs[i % len(inputs)]})[0]
        postprocess(output)
    inference, pipeline = [], []
    for i in range(args.iterations):
        start = time.perf_counter()
        output = session.run(None, {input_info.name: inputs[i % len(inputs)]})[0]
        inferred = time.perf_counter()
        postprocess(output)
        end = time.perf_counter()
        inference.append((inferred-start)*1000)
        pipeline.append((end-start)*1000)
    memory = process.memory_info()
    peak = getattr(memory, 'peak_wset', None)
    if peak is None:
        import resource
        peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024
    def latency(samples):
        return dict(median_ms=float(np.median(samples)), p95_ms=float(np.percentile(samples, 95)))
    return dict(model=str(args.worker), model_bytes=args.worker.stat().st_size,
                input_shape=input_info.shape, output_shape=list(output.shape),
                onnxruntime=ort.__version__, platform=platform.platform(),
                threads=args.threads, warmup=args.warmup, iterations=args.iterations,
                inference=latency(inference), inference_and_postprocess=latency(pipeline),
                postprocess=dict(confidence=0.25, iou=0.7, class_aware=True, max_detections=300),
                memory=dict(baseline_process_rss_bytes=baseline, final_process_rss_bytes=memory.rss,
                            peak_process_rss_bytes=peak, peak_minus_imported_runtime_bytes=max(0, peak-baseline)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', action='append', help='LABEL=path.onnx, repeat for every model')
    parser.add_argument('--output', type=Path, default=ROOT/'docs/experiments/onnx_desktop_benchmark.json')
    parser.add_argument('--images', type=Path, default=ROOT/'ml-pipeline/dataset_clean_v1/images/val')
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--warmup', type=int, default=20)
    parser.add_argument('--iterations', type=int, default=100)
    parser.add_argument('--worker', type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        print(json.dumps(worker(args)))
        return
    if not args.model:
        parser.error('Specify at least one --model LABEL=path.onnx')
    result = dict(hardware='Ryzen 7 5700X, 32 GB DDR4 2666', device='desktop CPU',
                  limitation='No phone measurements. Prepared tensors; excludes camera, decoding, resize and rendering.',
                  memory_note='Absolute worker process memory includes Python/OpenCV/ONNX Runtime; this is not Android app RAM.',
                  models=[])
    for item in args.model:
        label, path = item.split('=', 1)
        command = [sys.executable, str(Path(__file__).resolve()), '--worker', str(Path(path).resolve()),
                   '--images', str(args.images.resolve()), '--threads', str(args.threads),
                   '--warmup', str(args.warmup), '--iterations', str(args.iterations)]
        completed = subprocess.run(command, check=True, capture_output=True, text=True)
        value = json.loads(completed.stdout)
        value['label'] = label
        result['models'].append(value)
        print(json.dumps(value), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
