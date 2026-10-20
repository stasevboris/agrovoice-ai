"""Same-frame CPU ONNX comparison: crop/localization and source-unknown acceptance.

Only field-val and unknown/nonplant val are read. YOLO uses its native winner and
class-aware NMS; bundled DETR uses native decoded rows without NMS. Both map to crops.
This does not evaluate diagnoses or publish model weights.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import hashlib
import json
from pathlib import Path
import time

import numpy as np
from PIL import Image

from evaluate_home_pilot import validation_sources

ROOT = Path(__file__).resolve().parents[3]
SIDE = 416
THRESHOLD = .25
COUNTS = ("images", "gt", "localized", "tp", "fp", "fn", "ignored_gt", "ignored_predictions")


def preprocess(path):
    with Image.open(path) as source:
        image = source.convert("RGB")
    width, height = image.size
    scale = min(SIDE / width, SIDE / height)
    resized = (max(1, round(width * scale)), max(1, round(height * scale)))
    pad_x, pad_y = (SIDE - resized[0]) // 2, (SIDE - resized[1]) // 2
    canvas = Image.new("RGB", (SIDE, SIDE), (114, 114, 114))
    canvas.paste(image.resize(resized, Image.Resampling.BILINEAR), (pad_x, pad_y))
    batch = np.asarray(canvas, dtype=np.float32).transpose(2, 0, 1)[None] / 255
    return np.ascontiguousarray(batch), (width, height, scale, pad_x, pad_y)


def overlaps(box, others):
    others = np.asarray(others).reshape(-1, 4)
    intersection = np.maximum(0, np.minimum(box[2:], others[:, 2:]) -
                              np.maximum(box[:2], others[:, :2])).prod(1)
    area = np.maximum(0, np.asarray(box[2:]) - box[:2]).prod()
    areas = np.maximum(0, others[:, 2:] - others[:, :2]).prod(1)
    return intersection / np.maximum(area + areas - intersection, 1e-9)


def decode(output, geometry):
    """Android single winner, native class-aware NMS on unclipped boxes, then clipping."""
    if output.ndim != 3 or output.shape[0] != 1 or output.shape[1] <= 4:
        raise ValueError("Expected ordinary YOLO [1, 4 + classes, anchors]")
    scores = output[0, 4:]
    classes = np.where(np.isnan(scores), -np.inf, scores).argmax(0)
    confidence = scores[classes, np.arange(scores.shape[1])].copy()
    confidence[np.isnan(scores[0])] = np.nan
    xywh = output[0, :4].T
    boxes = np.concatenate((xywh[:, :2] - xywh[:, 2:] / 2, xywh[:, :2] + xywh[:, 2:] / 2), 1)
    valid = np.isfinite(xywh).all(1) & (xywh[:, 2:] > 0).all(1)
    valid &= np.isfinite(confidence) & (confidence >= THRESHOLD)
    rows = np.column_stack((boxes, confidence, classes))[valid]
    rows = rows[np.argsort(-rows[:, 4], kind="stable")][:30000]
    kept = []
    for row in rows:
        same_class = [previous[:4] for previous in kept if previous[5] == row[5]]
        if same_class and (overlaps(row[:4], same_class) > .7).any():
            continue
        kept.append(row)
        if len(kept) == 300:
            break
    rows = np.asarray(kept, dtype=np.float32).reshape(-1, 6)
    width, height, scale, pad_x, pad_y = geometry
    rows[:, [0, 2]] = ((rows[:, [0, 2]] - pad_x) / scale).clip(0, width)
    rows[:, [1, 3]] = ((rows[:, [1, 3]] - pad_y) / scale).clip(0, height)
    return rows[(rows[:, 2] > rows[:, 0]) & (rows[:, 3] > rows[:, 1])]


def decode_detr(output, geometry, class_count):
    """FieldDetector.decodeDetr: native xyxy/score/class, rounded per-axis scales, no NMS."""
    if output.shape != (1, 300, 6) or not np.isfinite(output).all():
        raise ValueError("Expected finite DETR output [1, 300, 6]")
    rows = output[0].copy()
    # Android Float.toInt truncates towards zero; no class is inferred from other scores.
    classes = np.trunc(rows[:, 5])
    rows[:, 5] = classes
    rows = rows[(classes >= 0) & (classes < class_count) & (rows[:, 4] >= THRESHOLD)]
    width, height, scale, pad_x, pad_y = geometry
    scale_x = max(1, round(width * scale)) / width
    scale_y = max(1, round(height * scale)) / height
    rows[:, [0, 2]] = ((rows[:, [0, 2]] - pad_x) / scale_x).clip(0, width)
    rows[:, [1, 3]] = ((rows[:, [1, 3]] - pad_y) / scale_y).clip(0, height)
    rows = rows[(rows[:, 2] > rows[:, 0]) & (rows[:, 3] > rows[:, 1])]
    return rows[np.argsort(-rows[:, 4], kind="stable")]


def legacy_crops(classes, taxonomy):
    by_code = {item["code"]: item["crop"] for item in taxonomy["classes"]}
    mapping = taxonomy["source_mapping"]["PlantDocLegacy"]
    if set(mapping) != {str(index) for index in range(len(classes))}:
        raise ValueError("APK classes must use the complete PlantDocLegacy order")
    return [by_code[mapping[str(index)]] for index in range(len(classes))]


def crop_predictions(rows, crops):
    return [{"box": row[:4].tolist(), "score": float(row[4]), "native_class": int(row[5]),
             "crop": crops[int(row[5])]} for row in rows]


def score_frame(ground_truth, predictions, common_crops):
    result = dict.fromkeys(COUNTS, 0)
    result["images"] = 1
    result["gt"] = sum(item["crop"] in common_crops for item in ground_truth)
    result["ignored_gt"] = len(ground_truth) - result["gt"]
    confusion = Counter()
    used = set()
    for prediction in sorted(predictions, key=lambda item: -item["score"]):
        ious = overlaps(prediction["box"], [item["box"] for item in ground_truth])
        matches = [int(index) for index in np.argsort(-ious, kind="stable")
                   if index not in used and ious[index] >= .5]
        if not matches:
            result["fp"] += 1
            confusion[f"__background__->{prediction['crop']}"] += 1
            continue
        index = matches[0]
        used.add(index)
        true_crop = ground_truth[index]["crop"]
        if true_crop not in common_crops:
            result["ignored_predictions"] += 1
            continue
        result["localized"] += 1
        confusion[f"{true_crop}->{prediction['crop']}"] += 1
        if true_crop == prediction["crop"]:
            result["tp"] += 1
        else:
            result["fp"] += 1
            result["fn"] += 1
    for index, item in enumerate(ground_truth):
        if index not in used and item["crop"] in common_crops:
            result["fn"] += 1
            confusion[f"{item['crop']}->__missed__"] += 1
    return dict(result, confusion=dict(confusion))


def sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def run(args):
    import onnxruntime as ort

    started = time.monotonic()
    deadline = started + args.max_seconds
    if args.deadline:
        wall_deadline = datetime.fromisoformat(args.deadline)
        if wall_deadline.tzinfo is None:
            raise ValueError("--deadline requires a timezone")
        deadline = min(deadline, started + wall_deadline.timestamp() - time.time())

    def check_time():
        if time.monotonic() >= deadline:
            raise TimeoutError("CPU comparison time budget exhausted; partial results retained")

    taxonomy = json.loads(args.taxonomy.read_text(encoding="utf-8"))
    manifest = json.loads(args.app_manifest.read_text(encoding="utf-8"))
    models = []
    for identity in [args.app_model, *getattr(args, "app_alternative", [])]:
        if identity in {model[0] for model in models}:
            raise ValueError("Model names must be unique")
        app = next((item for item in manifest["models"] if item["id"] == identity), None)
        if (app is None or app.get("family") not in ("yolo", "detr") or app.get("classifier") or
                app.get("side", SIDE) != SIDE):
            raise ValueError(f"Expected a bundled direct 416 YOLO/DETR detector: {identity}")
        app_path = args.app_manifest.parent.parent / app["asset"]
        if sha256(app_path) != app["sha256"]:
            raise ValueError(f"APK detector does not match its manifest SHA256: {identity}")
        crops = legacy_crops(app.get("classes", manifest["classes"]), taxonomy)
        models.append((identity, app_path, crops, app["family"], app))
    candidate_crops = [item["crop"] for item in taxonomy["classes"]]
    for candidate in args.candidate:
        name, path = candidate.split("=", 1)
        if not name or name in {model[0] for model in models}:
            raise ValueError("Candidate names must be nonempty and unique")
        if not Path(path).is_file():
            raise FileNotFoundError(path)
        models.append((name, Path(path), candidate_crops, "yolo", {}))
    common = set.intersection(*(set(crops) for _, _, crops, _, _ in models))
    if not common:
        raise ValueError("No common crops")
    field_path = args.data / "annotations/val_field.json"
    field = json.loads(field_path.read_text(encoding="utf-8"))
    if not field["images"] or any(row.get("domain") != "field" or
                                  not row["file_name"].startswith("images/val/") for row in field["images"]):
        raise ValueError("Expected nonempty field-only validation images")
    ground_truth = defaultdict(list)
    for annotation in field["annotations"]:
        x, y, width, height = annotation["bbox"]
        ground_truth[annotation["image_id"]].append({"crop": candidate_crops[annotation["category_id"]],
                                                    "box": [x, y, x + width, y + height]})
    unknown = validation_sources(args.unknown_root)
    args.out.mkdir(parents=True, exist_ok=False)
    report = {"status": "running", "test_evaluated": False, "common_crops": sorted(common), "models": [],
              "field_annotations": str(field_path), "field_annotations_sha256": sha256(field_path),
              "manifest_sha256": sha256(args.app_manifest), "taxonomy_sha256": sha256(args.taxonomy),
              "planned_images": {"field": len(field["images"]), **{key: len(paths) for key, paths in unknown.items()}},
              "protocol": {"confidence": THRESHOLD, "nms_iou": .7, "matching_iou": .5,
                           "nms_applies_to": "yolo only; DETR native rows have no NMS",
                           "matching": "Confidence-ordered class-agnostic IoU>=0.5 matching; wrong crop consumes GT",
                           "native_class_nms": "see each model decode_policy", "crop_mapping_after_native_winner": True,
                           "device": "CPU", "threads": args.threads, "max_seconds": args.max_seconds},
              "limitations": ["Crop and localization only; generic leaf labels do not establish healthy diagnoses.",
                              "TP/FP/FN score the first localized crop response, not standard class-aware detection AP.",
                              "Historical source/split overlap limitations remain; not an independent external benchmark.",
                              "Unknown scenes can contain unannotated plants; acceptance is a source-level proxy.",
                              "PIL and Android share geometry/RGB normalization, not guaranteed identical resize pixels.",
                              "Budget checked between ONNX calls; an in-flight call cannot be interrupted here."]}
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    options.inter_op_num_threads = 1
    try:
        with (args.out / "predictions.jsonl").open("w", encoding="utf-8") as output:
            for name, path, crops, family, metadata in models:
                check_time()
                session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
                inputs, outputs = session.get_inputs(), session.get_outputs()
                expected_output = [1, 300, 6] if family == "detr" else [1, 4 + len(crops), 3549]
                if (len(inputs) != 1 or inputs[0].type != "tensor(float)" or inputs[0].shape != [1, 3, SIDE, SIDE] or
                        len(outputs) != 1 or outputs[0].type != "tensor(float)" or outputs[0].shape != expected_output):
                    raise ValueError(f"Unsupported detector shape/type: {name}")
                stats = dict.fromkeys(COUNTS, 0)
                stats["confusion"] = Counter()
                item = {"name": name, "onnx": str(path), "sha256": sha256(path), "class_crops": crops,
                        "model_name": metadata.get("name", name), "family": family,
                        "provisional": metadata.get("provisional", True), "license": metadata.get("license", "not specified here"),
                        "decode_policy": {"native_class_nms": family == "yolo",
                                          "output": "xyxy_score_class" if family == "detr" else "cxcywh_class_scores",
                                          "box_mapping": "rounded_axis_scales" if family == "detr" else "letterbox_scale"},
                        "field": stats, "acceptance": {}, "complete": False}
                report["models"].append(item)
                sources = {"field": [(str(row["id"]), args.data / row["file_name"]) for row in field["images"]],
                           **{key: [(str(Path(path)), Path(path)) for path in paths] for key, paths in unknown.items()}}
                for source, images in sources.items():
                    acceptance = {"images": 0, "accepted": 0}
                    if source != "field":
                        item["acceptance"][source] = acceptance
                    for identity, image in images:
                        check_time()
                        batch, geometry = preprocess(image)
                        raw = session.run(None, {inputs[0].name: batch})[0]
                        rows = decode_detr(raw, geometry, len(crops)) if family == "detr" else decode(raw, geometry)
                        predictions = crop_predictions(rows, crops)
                        output.write(json.dumps({"model": name, "source": source, "id": identity,
                                                 "image": str(image), "predictions": predictions}) + "\n")
                        if source == "field":
                            frame = score_frame(ground_truth[int(identity)], predictions, common)
                            for key in COUNTS:
                                stats[key] += frame[key]
                            stats["confusion"].update(frame["confusion"])
                        else:
                            acceptance["images"] += 1
                            acceptance["accepted"] += bool(predictions)
                        check_time()
                stats["precision"] = stats["tp"] / max(1, stats["tp"] + stats["fp"])
                stats["recall"] = stats["tp"] / max(1, stats["gt"])
                stats["f1"] = 2 * stats["tp"] / max(1, 2 * stats["tp"] + stats["fp"] + stats["fn"])
                stats["localization_recall"] = stats["localized"] / max(1, stats["gt"])
                item["complete"] = True
                del session
        report["status"] = "complete"
    except TimeoutError as error:
        report.update(status="partial", error=str(error))
    except Exception as error:
        report.update(status="failed", error=str(error))
        raise
    finally:
        report["elapsed_seconds"] = time.monotonic() - started
        (args.out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-manifest", type=Path, default=ROOT / "android-app/app/src/main/assets/models/manifest.json")
    parser.add_argument("--app-model", default="yolo26s")
    parser.add_argument("--candidate", action="append", default=[], metavar="NAME=ONNX")
    parser.add_argument("--app-alternative", action="append", default=[], metavar="ID",
                        help="Compare another bundled direct YOLO/DETR model from the same manifest")
    parser.add_argument("--taxonomy", type=Path, default=ROOT / "ml-pipeline/allcrops/taxonomy_v1.json")
    parser.add_argument("--data", type=Path, default=ROOT / "ml-pipeline/dataset_vision_training_v1")
    parser.add_argument("--unknown-root", type=Path, default=ROOT / "ml-pipeline")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--max-seconds", type=float, default=600)
    parser.add_argument("--deadline", help="Optional ISO timestamp with timezone; earliest time limit wins")
    parser.add_argument("--threads", type=int, default=2)
    args = parser.parse_args()
    if not args.candidate and not args.app_alternative:
        parser.error("At least one --candidate or --app-alternative is required")
    if (not np.isfinite(args.max_seconds) or args.max_seconds <= 0 or args.threads <= 0 or
            any("=" not in item for item in args.candidate)):
        parser.error("A finite positive max-seconds, positive threads and NAME=ONNX candidates are required")
    report = run(args)
    print(json.dumps({"status": report["status"], "report": str(args.out / "report.json")}))
    return 0 if report["status"] == "complete" else 2


if __name__ == "__main__":
    raise SystemExit(main())
