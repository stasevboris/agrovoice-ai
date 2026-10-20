"""Static INT8 (QDQ) copy of an Android detector ONNX and a field-val comparison with the FP32 original.

Calibration uses train images only; validation is the same field-val list as training. No test split.
The output is a private experiment: release still goes through prepare_model_release.py.

Example (CPU):
  python -X utf8 quantize_detector_int8.py --onnx runs/<run>/train/weights/best.onnx \
      --config runs/<run>/config --out runs/<run>/int8 --calibration 200
"""
import argparse
import hashlib
import json
from pathlib import Path
import random

import numpy as np
from PIL import Image

SIDE = 416
PAD = 114


def letterbox(path, side=SIDE):
    """Same geometry as the app: keep aspect ratio, pad with gray, RGB / 255, NCHW."""
    with Image.open(path) as source:
        image = source.convert("RGB")
    scale = min(side / image.width, side / image.height)
    width = max(1, round(image.width * scale))
    height = max(1, round(image.height * scale))
    canvas = Image.new("RGB", (side, side), (PAD, PAD, PAD))
    canvas.paste(image.resize((width, height), Image.BILINEAR), ((side - width) // 2, (side - height) // 2))
    array = np.asarray(canvas, dtype=np.float32) / 255.0
    return array.transpose(2, 0, 1)[None]


class Calibration:
    """onnxruntime CalibrationDataReader over a fixed, seeded sample of train images."""

    def __init__(self, paths, name):
        self.paths = iter(paths)
        self.name = name

    def get_next(self):
        path = next(self.paths, None)
        return None if path is None else {self.name: letterbox(path)}

    def rewind(self):
        raise NotImplementedError


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def detector_graph(model_path):
    """Reject layouts that fixed 416 calibration and class-score comparison cannot handle."""
    import onnx

    graph = onnx.load(str(model_path)).graph
    if len(graph.input) != 1 or len(graph.output) != 1:
        raise ValueError("Expected one detector input and one output")
    input_type = graph.input[0].type.tensor_type
    input_shape = [dim.dim_value for dim in input_type.shape.dim]
    if input_type.elem_type != onnx.TensorProto.FLOAT or input_shape != [1, 3, SIDE, SIDE]:
        raise ValueError("Expected a float32 detector input [1, 3, 416, 416]")
    output_type = graph.output[0].type.tensor_type
    output_shape = [dim.dim_value for dim in output_type.shape.dim]
    if (output_type.elem_type != onnx.TensorProto.FLOAT or len(output_shape) != 3 or
            output_shape[0] != 1 or output_shape[1] <= 4 or output_shape[2] != 3549):
        raise ValueError("Expected ordinary YOLO output [1, 4 + classes, 3549]")
    return graph


def float_head(model_path):
    """Nodes of the detection head that stay FP32: box decoding, sigmoid/concat and the last 1x1 convs.

    Box pixels (0..416) and class scores (0..1) share the output tensor; one INT8 scale for both
    can erase the class scores. Experiment: runs/home_20261020_int8_n2/report.json.
    """
    import re
    graph = detector_graph(model_path)
    producer = next((node for node in graph.node if graph.output[0].name in node.output), None)
    match = re.fullmatch(r"(/model\.\d+/)Concat(?:_\d+)?", producer.name) if producer else None
    if not match or producer.op_type != "Concat":
        raise ValueError("Unrecognized detection head; cannot safely preserve float predictions")
    prefix = match.group(1)
    head = [node for node in graph.node if node.name.startswith(prefix)]
    predictions = [node.name for node in head if node.op_type == "Conv" and
                   re.search(r"cv[23]\.\d+\.2/Conv$", node.name)]
    if len(predictions) != 6:
        raise ValueError("Unrecognized detection head: expected six final prediction convolutions")
    return [node.name for node in head if node.op_type != "Conv" or node.name in predictions]


def quantize(source, target, calibration_paths, keep_head_float=True):
    import onnxruntime as ort
    from onnxruntime.quantization import CalibrationMethod, QuantFormat, QuantType, quantize_static
    from onnxruntime.quantization.shape_inference import quant_pre_process

    detector_graph(source)
    prepared = target.with_name(target.stem + "_prep.onnx")
    quant_pre_process(str(source), str(prepared), skip_symbolic_shape=True)
    name = ort.InferenceSession(str(prepared), providers=["CPUExecutionProvider"]).get_inputs()[0].name
    excluded = float_head(prepared) if keep_head_float else []
    quantize_static(str(prepared), str(target), Calibration(calibration_paths, name), nodes_to_exclude=excluded,
                    quant_format=QuantFormat.QDQ, per_channel=True,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8,
                    calibrate_method=CalibrationMethod.MinMax)
    prepared.unlink()
    return excluded


def validate(model, config, out, name):
    from ultralytics import YOLO
    metrics = YOLO(str(model), task="detect").val(
        data=str(config / "train.yaml"), split="val", device="cpu", imgsz=SIDE, batch=1, workers=0,
        rect=False, iou=0.7, max_det=300, plots=False, verbose=False, project=str(out), name=name)
    return {key: float(value) for key, value in metrics.results_dict.items()}


def parity(fp32, int8, paths):
    import onnxruntime as ort
    sessions = [ort.InferenceSession(str(path), providers=["CPUExecutionProvider"]) for path in (fp32, int8)]
    name = sessions[0].get_inputs()[0].name
    differences = []
    for path in paths:
        batch = letterbox(path)
        reference, quantized = (session.run(None, {name: batch})[0] for session in sessions)
        # Rows 4.. are class scores; compare the best score per anchor, which drives acceptance.
        differences.append(float(np.abs(reference[0, 4:].max(0) - quantized[0, 4:].max(0)).max()))
    return {"images": len(paths), "max_abs_best_score_diff": max(differences),
            "median_max_abs_best_score_diff": float(np.median(differences))}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True, help="Pilot config directory with train.txt and train.yaml")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--calibration", type=int, default=200)
    parser.add_argument("--skip-validation", action="store_true")
    parser.add_argument("--quantize-head", action="store_true", help="Also quantize the decoding head (loses class scores)")
    args = parser.parse_args()
    if args.calibration <= 0:
        parser.error("--calibration must be positive")
    args.out.mkdir(parents=True, exist_ok=False)
    train = [line for line in (args.config / "train.txt").read_text(encoding="utf-8").splitlines() if line]
    calibration = random.Random(20261020).sample(train, min(args.calibration, len(train)))
    target = args.out / (args.onnx.stem + "_int8_qdq.onnx")
    excluded = quantize(args.onnx, target, calibration, keep_head_float=not args.quantize_head)
    report = {"source": str(args.onnx), "source_sha256": sha256(args.onnx), "source_bytes": args.onnx.stat().st_size,
              "int8": str(target), "int8_sha256": sha256(target), "int8_bytes": target.stat().st_size,
              "method": "onnxruntime quantize_static QDQ, per-channel QInt8 weights, QUInt8 activations, MinMax",
              "calibration": {"split": "train", "images": len(calibration), "seed": 20261020},
              "float_head_nodes": len(excluded),
              "parity_on_calibration_sample": parity(args.onnx, target, calibration[:20]),
              "test_evaluated": False}
    if not args.skip_validation:
        report["field_val"] = {"fp32": validate(args.onnx, args.config, args.out, "fp32"),
                               "int8": validate(target, args.config, args.out, "int8")}
    (args.out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
