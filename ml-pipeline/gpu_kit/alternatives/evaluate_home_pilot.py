"""CPU validation of the Android-compatible detector head, source-unknown acceptance and ONNX parity.

Validation only: no test split, release, or phone installation. Run through the bounded supervisor.
"""
import hashlib
import json
from pathlib import Path
import time


def batches(items, size=8):
    """Ultralytics treats a Python image list as one batch; bound the list itself."""
    for offset in range(0, len(items), size):
        yield items[offset:offset + size]


def validation_sources(root):
    output = {}
    for name, filename in (("dataset_nonplant_openimages_v2", "manifest.jsonl"),
                           ("dataset_unknown_beans_screened_v1", "val.jsonl"),
                           ("dataset_unknown_deepweeds_v1", "val.jsonl")):
        folder = root / name
        rows = [json.loads(line) for line in (folder / filename).read_text(encoding="utf-8").splitlines()]
        output[name] = [str(folder / row["image"]) for row in rows if row["split"] == "val"]
        if not output[name]:
            raise ValueError(f"No validation images for {name}")
    return output


def evaluate(args):
    from train_home_pilot import deadlines, write_json
    soft, _ = deadlines(args.deadline)
    import numpy as np
    import torch
    import onnxruntime as ort
    from ultralytics import YOLO

    torch.set_num_threads(4)
    if args.after is None:
        raise ValueError("Evaluation requires --after <completed pilot>")
    trained = json.loads((args.after / "report.json").read_text(encoding="utf-8"))["runs"]
    if len(trained) != 2:
        raise ValueError("A completed baseline/negative pair is required")
    unknown = validation_sources(args.data.parent)
    thresholds = (0.1, 0.25, 0.5, 0.75)
    reports = []

    def ordinary(path):
        model = YOLO(str(path))
        model.model.end2end = False
        for layer in model.model.modules():
            if hasattr(layer, "end2end"):
                layer.end2end = False
        return model

    for run in trained:
        if time.time() >= soft:
            raise RuntimeError("Validation/export deferred: checkpoint reserve reached")
        weights = Path(run["best"])
        name = run["variant"]
        model = ordinary(weights)
        validation = model.val(data=str(args.after / "config/train.yaml"), split="val", device="cpu",
            imgsz=416, batch=8, workers=0, rect=False, iou=0.7, max_det=300,
            plots=False, verbose=False, project=str(args.out), name=f"{name}_field")
        item = {"variant": name, "ordinary_head_field_val": validation.results_dict, "acceptance": {}}
        for source, paths in unknown.items():
            scores = []
            raw = []
            for batch in batches(paths):
                for prediction in model.predict(batch, stream=True, device="cpu", imgsz=416, batch=8,
                    rect=False, conf=0.1, iou=0.7, max_det=300, verbose=False, save=False):
                    if time.time() >= soft:
                        raise RuntimeError("Validation interrupted before the deadline")
                    boxes = prediction.boxes
                    confidence = boxes.conf.cpu().numpy() if boxes is not None else np.array([])
                    highest = float(confidence.max()) if len(confidence) else 0.0
                    scores.append(highest)
                    raw.append({"image": Path(prediction.path).name, "maximum_known_class_score": highest})
            item["acceptance"][source] = {"images": len(scores),
                "accepted_at_threshold": {str(value): sum(score >= value for score in scores) for value in thresholds}}
            write_json(args.out / f"{name}_{source}.json", raw)
        exported_model = ordinary(weights)
        exported = Path(exported_model.export(format="onnx", imgsz=416, batch=1, device="cpu", half=False,
            dynamic=False, opset=17, nms=None, simplify=False))
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        options.inter_op_num_threads = 1
        session = ort.InferenceSession(str(exported), sess_options=options, providers=["CPUExecutionProvider"])
        shape = session.get_outputs()[0].shape
        if shape != [1, 53, 3549]:
            raise ValueError(f"Incompatible Android output: {shape}")
        reference = ordinary(weights).model.float().eval()
        generator = np.random.default_rng(20261019)
        differences = []
        for _ in range(3):
            sample = generator.random((1, 3, 416, 416), dtype=np.float32)
            with torch.no_grad():
                expected = reference(torch.from_numpy(sample))
            expected = (expected[0] if isinstance(expected, (tuple, list)) else expected).numpy()
            actual = session.run(None, {session.get_inputs()[0].name: sample})[0]
            differences.append(float(np.max(np.abs(expected - actual))))
            if not np.allclose(expected, actual, rtol=1e-3, atol=0.002):
                raise ValueError("ONNX parity check failed")
        item["onnx"] = {"path": str(exported), "bytes": exported.stat().st_size,
            "sha256": hashlib.sha256(exported.read_bytes()).hexdigest(), "output_shape": shape,
            "parity_max_abs_diff": differences, "rtol": 1e-3, "atol": 0.002}
        reports.append(item)
        write_json(args.out / "report.json", {"runs": reports, "test_evaluated": False,
            "execution_device": "CPU", "ordinary_head": True,
            "limitations": ["Three train classes are absent; not a complete taxonomy release.",
                            "Unknown-source scenes may contain unannotated background plants; acceptance is a proxy, not expert-confirmed error.",
                            "Reviewed nonplant validation is tiny; no claim of field reliability.",
                            "PyTorch ordinary head and matching NMS settings; real phone performance not measured.",
                            "Private ONNX artifacts only; release still requires prepare_model_release.py and a model decision."]})
