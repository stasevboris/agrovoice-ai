"""Check the benchmark's class-aware NMS against Ultralytics on real model outputs."""
from pathlib import Path
import json
import os

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault('YOLO_CONFIG_DIR', str(ROOT/'ml-pipeline/.cache/ultralytics/windows'))


def main():
    import cv2
    import numpy as np
    import onnxruntime as ort
    import torch
    from ultralytics.data.augment import LetterBox
    from ultralytics.utils.nms import non_max_suppression
    from benchmark_onnx import postprocess
    weights = ROOT/'runs/detect/plantdoc_clean_v1_yolo11n_416/weights/best.onnx'
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(str(weights), options, providers=['CPUExecutionProvider'])
    checks = []
    for image_path in sorted((ROOT/'ml-pipeline/dataset_clean_v1/images/val').glob('*.jpg'))[:5]:
        image = LetterBox(new_shape=(416, 416), auto=False)(image=cv2.imread(str(image_path)))
        batch = np.ascontiguousarray(image.transpose(2, 0, 1)[::-1][None], dtype=np.float32)/255
        output = session.run(None, {session.get_inputs()[0].name: batch})[0]
        expected = non_max_suppression(torch.from_numpy(output.copy()), conf_thres=0.25,
                                       iou_thres=0.7, max_det=300)[0].numpy()
        actual = postprocess(output)
        if actual.shape != expected.shape or not np.allclose(actual, expected, atol=1e-4):
            raise RuntimeError(f'NMS differs for {image_path.name}: {actual.shape}, {expected.shape}')
        checks.append(dict(image=image_path.name, detections=len(actual)))
    destination = ROOT/'docs/experiments/postprocessing_check.json'
    destination.write_text(json.dumps(dict(status='passed', reference='Ultralytics class-aware NMS',
                                           confidence=0.25, iou=0.7, checks=checks), indent=2), encoding='utf-8')
    print(destination)


if __name__ == '__main__':
    main()
