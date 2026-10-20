"""Fixed validation cutoffs from completed compare_mobile_detectors predictions.

No inference, test split, threshold selection or deployment. Filtering retained
confidence-ordered predictions at >= the original cutoff preserves higher-cutoff
YOLO NMS results; native DETR rows remain without NMS. Matching is recomputed.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path

from compare_mobile_detectors import COUNTS, ROOT, score_frame, sha256

THRESHOLDS = (.25, .5, .75, .9, .95, .99)
YOLO_POLICY = {"native_class_nms": True, "output": "cxcywh_class_scores", "box_mapping": "letterbox_scale"}
DETR_POLICY = {"native_class_nms": False, "output": "xyxy_score_class", "box_mapping": "rounded_axis_scales"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def json_sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode("utf-8")).hexdigest()


def sweep(rows, ground_truth, common):
    points = []
    for threshold in THRESHOLDS:
        counts = Counter(dict.fromkeys(COUNTS, 0))
        acceptance = defaultdict(lambda: {"images": 0, "accepted": 0})
        for row in rows:
            predictions = [p for p in row["predictions"] if p["score"] >= threshold]
            if row["source"] == "field":
                frame = score_frame(ground_truth[int(row["id"])], predictions, common)
                counts.update({key: frame[key] for key in COUNTS})
            else:
                source = acceptance[row["source"]]
                source["images"] += 1
                source["accepted"] += bool(predictions)
        field = dict(counts)
        answered = counts["tp"] + counts["fp"]
        field.update(precision=counts["tp"] / answered if answered else None,
                     recall=counts["tp"] / counts["gt"],
                     f1=2 * counts["tp"] / max(1, 2 * counts["tp"] + counts["fp"] + counts["fn"]),
                     localization_recall=counts["localized"] / counts["gt"])
        for source in acceptance.values():
            source["rate"] = source["accepted"] / source["images"]
        points.append({"threshold": threshold, "field": field, "acceptance": dict(acceptance)})
    return points


def analyze(directories, taxonomy_path):
    require(bool(directories), "At least one completed comparison is required")
    taxonomy = read_json(taxonomy_path)
    crops = [item["crop"] for item in taxonomy["classes"]]
    first = read_json(Path(directories[0]) / "report.json")
    field_path = Path(first["field_annotations"])
    require(sha256(field_path) == first["field_annotations_sha256"], "Field annotations SHA mismatch")
    require(sha256(taxonomy_path) == first["taxonomy_sha256"], "Taxonomy SHA mismatch")
    field = read_json(field_path)
    common = set(first["common_crops"])
    images = {str(row["id"]): row for row in field["images"]}
    require(images and len(images) == len(field["images"]) and all(
        row.get("domain") == "field" and row["file_name"].startswith("images/val/")
        for row in images.values()), "Expected unique field validation images")
    ground_truth = defaultdict(list)
    for row in field["annotations"]:
        require(str(row["image_id"]) in images, "Annotation refers to an absent image")
        x, y, width, height = row["bbox"]
        ground_truth[row["image_id"]].append({"crop": crops[row["category_id"]], "box": [x, y, x + width, y + height]})
    require(any(item["crop"] in common for rows in ground_truth.values() for item in rows),
            "Empty common-crop GT denominator")
    models, inputs, frame_index = {}, [], None
    for directory in map(Path, directories):
        report_path, raw_path = directory / "report.json", directory / "predictions.jsonl"
        report = read_json(report_path)
        require(report["status"] == "complete" and report["test_evaluated"] is False,
                "Only completed validation runs are supported")
        for key in ("field_annotations_sha256", "taxonomy_sha256", "common_crops", "planned_images"):
            require(report[key] == first[key], f"Comparison protocols differ: {key}")
        protocol = report["protocol"]
        per_model_decode = protocol["native_class_nms"] == "see each model decode_policy"
        require(protocol["confidence"] == THRESHOLDS[0] and protocol["nms_iou"] == .7 and
                protocol["matching_iou"] == .5 and (protocol["native_class_nms"] is True or per_model_decode) and
                protocol["crop_mapping_after_native_winner"] is True, "Unsupported saved decode/matching protocol")
        planned = report["planned_images"]
        require(planned["field"] == len(images) and all(type(n) is int and n > 0 for n in planned.values()),
                "Invalid image denominators")
        catalog = {model["name"]: model for model in report["models"]}
        require(catalog and len(catalog) == len(report["models"]) and all(
            model["complete"] is True for model in catalog.values()), "Incomplete or duplicate model entries")
        rows_by_model = defaultdict(list)
        with raw_path.open(encoding="utf-8") as stream:
            for line in stream:
                row = json.loads(line)
                require(row["model"] in catalog and row["source"] in planned, "Unexpected model/source")
                path = "/" + row["image"].replace("\\", "/")
                require("/images/val/" in path or "/images/validation/" in path, "Non-validation prediction image")
                if row["source"] == "field":
                    require(str(row["id"]) in images and
                            path.endswith("/" + images[str(row["id"])]["file_name"]),
                            "Field image identity mismatch")
                for prediction in row["predictions"]:
                    require(math.isfinite(prediction["score"]) and .25 <= prediction["score"] <= 1 and
                            len(prediction["box"]) == 4 and all(math.isfinite(v) for v in prediction["box"]) and
                            prediction["crop"] in catalog[row["model"]]["class_crops"], "Invalid saved prediction")
                rows_by_model[row["model"]].append(row)
        inputs.append({"directory": str(directory), "report_sha256": sha256(report_path),
                       "predictions_sha256": sha256(raw_path)})
        for name, model in catalog.items():
            policy = model.get("decode_policy", None if per_model_decode else YOLO_POLICY)
            require(policy in (YOLO_POLICY, DETR_POLICY), "Unsupported model decode policy")
            rows = rows_by_model[name]
            identities = [(r["source"], str(r["id"]), r["image"].replace("\\", "/")) for r in rows]
            require(len({identity[:2] for identity in identities}) == len(rows) and
                    Counter(r["source"] for r in rows) == planned, "Missing or duplicate image predictions")
            index = sorted(identities)
            if frame_index is None:
                frame_index = index
            require(index == frame_index, "Comparisons use different validation images")
            points = sweep(rows, ground_truth, common)
            require(all(points[0]["field"][key] == model["field"][key] for key in COUNTS),
                    "Saved field baseline does not reproduce")
            require(set(points[0]["acceptance"]) == set(model["acceptance"]) and all(
                all(source[k] == model["acceptance"][name][k] for k in ("images", "accepted"))
                for name, source in points[0]["acceptance"].items()), "Saved unknown baseline does not reproduce")
            identity = (name, model["sha256"])
            prediction_sha = json_sha(sorted(rows, key=lambda row: (row["source"], str(row["id"]))))
            if identity not in models:
                models[identity] = {"name": name, "sha256": model["sha256"], "onnx": model.get("onnx"),
                                    "decode_policy": policy,
                                    "source_runs": [], "predictions_sha256": prediction_sha, "thresholds": points}
            require(models[identity]["decode_policy"] == policy, "Repeated model decode policies disagree")
            require(models[identity]["predictions_sha256"] == prediction_sha, "Repeated model predictions disagree")
            models[identity]["source_runs"].append(str(directory))
    return {"status": "complete", "test_evaluated": False, "threshold_selected": None,
            "thresholds": list(THRESHOLDS), "common_crops": sorted(common), "planned_images": first["planned_images"],
            "field_annotations_sha256": first["field_annotations_sha256"], "taxonomy_sha256": first["taxonomy_sha256"],
            "validation_image_index_sha256": json_sha(frame_index), "inputs": inputs, "models": list(models.values()),
            "protocol": {"matching": "compare_mobile_detectors.score_frame; IoU>=0.5, common-crop GT only",
                         "precision": "TP/(TP+FP); null when there are no scored predictions",
                         "acceptance": "At least one retained prediction per image, reported separately by source",
                         "filter": "Inclusive cutoff on saved post-NMS YOLO / native DETR predictions; no new NMS"},
            "limitations": ["Same validation images, not independent test; no automatic cutoff selection or deployment.",
                            "Unknown acceptance is a source-level proxy, not calibrated refusal or a disease diagnosis.",
                            "Training budgets and data differ; threshold curves do not isolate their effects.",
                            "Unknown image bytes were not rehashed; index SHA pins identities, raw SHA pins saved predictions."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="+", type=Path)
    parser.add_argument("--taxonomy", type=Path, default=ROOT / "ml-pipeline/allcrops/taxonomy_v1.json")
    parser.add_argument("--out", required=True, type=Path,
                        help="New diagnostic report.json; existing results are not overwritten")
    args = parser.parse_args()
    report = analyze(args.runs, args.taxonomy)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, indent=2, ensure_ascii=False)
        stream.write("\n")
    print(args.out)


if __name__ == "__main__":
    main()
