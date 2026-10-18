"""Prepare COCO detection and whole-leaf crops from allcrops_v1 for fair pilots.

Preserves taxonomy IDs and source splits. Unreviewed geometry and repeated samples
are excluded. No synthetic diagnosis labels and no full-image disease propagation.
"""

import argparse
import hashlib
import json
import math
import os
import shutil
from collections import Counter, defaultdict
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
TRUSTED_GEOMETRY = {"publisher_mask_bbox", "plantdoc_source_box",
                    "adjudicated_whole_leaf", "source_leaf_boxes"}


def json_write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def read_boxes(path, width, height, class_count):
    boxes = []
    for line in path.read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) != 5:
            raise ValueError(f"Invalid YOLO row: {path}")
        cls, cx, cy, bw, bh = map(float, fields)
        if not all(math.isfinite(v) for v in (cls, cx, cy, bw, bh)):
            raise ValueError(f"Non-finite box: {path}")
        if cls != int(cls) or not 0 <= cls < class_count or bw <= 0 or bh <= 0:
            raise ValueError(f"Invalid class/box: {path}")
        x1, y1 = (cx - bw / 2) * width, (cy - bh / 2) * height
        x2, y2 = (cx + bw / 2) * width, (cy + bh / 2) * height
        # Accommodate only sub-pixel text rounding, never repair a bad annotation.
        if min(x1, y1) < -0.01 or x2 > width + 0.01 or y2 > height + 0.01:
            raise ValueError(f"Out-of-bounds box: {path}")
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(width, x2), min(height, y2)
        boxes.append((int(cls), [x1, y1, x2 - x1, y2 - y1]))
    if not boxes:
        raise ValueError(f"Unexpected empty positive annotation: {path}")
    return boxes


def select_rows(rows):
    groups = defaultdict(set)
    content_labels = defaultdict(set)
    for row in rows:
        for key in ("image_sha256", "component_id"):
            groups[(key, row[key])].add(row["split"])
        content_labels[row["image_sha256"]].add(row["label_sha256"])
    seen = set()
    kept = []
    rejected = []
    for row in rows:
        reason = None
        if row["geometry"] not in TRUSTED_GEOMETRY:
            reason = "unreviewed_or_fallback_geometry"
        elif any(len(groups[(key, row[key])]) > 1 for key in ("image_sha256", "component_id")):
            reason = "cross_split_group"
        elif len(content_labels[row["image_sha256"]]) > 1:
            reason = "conflicting_labels_same_image"
        elif row.get("repeat", 0) or row["image_sha256"] in seen:
            reason = "repeat_or_exact_duplicate"
        if reason:
            rejected.append({"image": row["image"], "split": row["split"], "reason": reason})
        else:
            kept.append(row)
            seen.add(row["image_sha256"])
    return kept, rejected


def build(source, out, limit=None):
    taxonomy = json.loads((source / "taxonomy_v1.json").read_text(encoding="utf-8"))
    classes = taxonomy["classes"]
    with (source / "manifest.jsonl").open(encoding="utf-8") as stream:
        rows = [json.loads(line) for line in stream]
    selected, rejected = select_rows(rows)
    if limit is not None:
        selected = [row for split in ("train", "val", "test")
                    for row in [r for r in selected if r["split"] == split][:limit]]
    out.mkdir(parents=True, exist_ok=False)
    shutil.copy2(source / "taxonomy_v1.json", out / "taxonomy_v1.json")
    (out / "annotations").mkdir()
    counts = Counter()
    per_class = Counter()
    categories = [{"id": c["id"], "name": c["code"], "supercategory": c["crop"]} for c in classes]
    annotation_id = 0
    image_id = 0
    with (out / "crops.jsonl").open("w", encoding="utf-8") as crop_file:
        with (out / "provenance.jsonl").open("w", encoding="utf-8") as provenance:
            for split in ("train", "val", "test"):
                coco = {"images": [], "annotations": [], "categories": categories}
                for row in (r for r in selected if r["split"] == split):
                    image_id += 1
                    original = source / "images" / split / row["image"]
                    label = source / "labels" / split / Path(row["image"]).with_suffix(".txt")
                    with original.open("rb") as stream:
                        if hashlib.file_digest(stream, "sha256").hexdigest() != row["image_sha256"]:
                            raise ValueError(f"Source image changed: {original}")
                    label_text = label.read_text(encoding="utf-8")
                    if hashlib.sha256(label_text.encode("utf-8")).hexdigest() != row["label_sha256"]:
                        raise ValueError(f"Source label changed: {label}")
                    with Image.open(original) as image:
                        image.load()
                        width, height = image.size
                        boxes = read_boxes(label, width, height, len(classes))
                        if len(boxes) != row["boxes"]:
                            raise ValueError(f"Box count differs from manifest: {original}")
                        relative = Path("images") / split / original.name
                        target = out / relative
                        target.parent.mkdir(parents=True, exist_ok=True)
                        # Hard links are read-only inputs for training; original files are not edited.
                        try:
                            os.link(original, target)
                        except OSError:
                            shutil.copy2(original, target)
                        target_label = out / "labels" / split / label.name
                        target_label.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(label, target_label)
                        domain = "field" if row["source"] == "PlantDoc" else "lab"
                        coco["images"].append({"id": image_id, "file_name": relative.as_posix(),
                                               "width": width, "height": height,
                                               "source": row["source"], "domain": domain})
                        for number, (cls, box) in enumerate(boxes):
                            annotation_id += 1
                            coco["annotations"].append({"id": annotation_id, "image_id": image_id,
                                "category_id": cls, "bbox": box, "area": box[2] * box[3], "iscrowd": 0})
                            x, y, w, h = box
                            crop_path = Path("crops") / split / classes[cls]["code"] / f"{original.stem}_{number}.jpg"
                            (out / crop_path).parent.mkdir(parents=True, exist_ok=True)
                            crop = image.convert("RGB").crop((math.floor(x), math.floor(y),
                                                             math.ceil(x + w), math.ceil(y + h)))
                            crop.save(out / crop_path, quality=95)
                            crop_row = {"image": crop_path.as_posix(), "class_id": cls,
                                        "class_code": classes[cls]["code"], "split": split,
                                        "source_image": relative.as_posix(), "source": row["source"],
                                        "domain": domain, "group": row["component_id"],
                                        "geometry": row["geometry"], "bbox_xywh": box}
                            crop_file.write(json.dumps(crop_row, ensure_ascii=False) + "\n")
                            counts[f"{split}_crops"] += 1
                            per_class[(split, cls)] += 1
                        counts[f"{split}_images"] += 1
                        provenance.write(json.dumps(row, ensure_ascii=False) + "\n")
                json_write(out / "annotations" / f"{split}.json", coco)
                field_ids = {im["id"] for im in coco["images"] if im["domain"] == "field"}
                field = dict(coco, images=[im for im in coco["images"] if im["id"] in field_ids],
                             annotations=[a for a in coco["annotations"] if a["image_id"] in field_ids])
                json_write(out / "annotations" / f"{split}_field.json", field)
                print(split, counts[f"{split}_images"], "images", flush=True)
    json_write(out / "excluded.json", rejected)
    # JSON flow values are valid YAML and preserve zero-based taxonomy ordering.
    yaml_text = "train: images/train\nval: images/val\ntest: images/test\nnames: "
    yaml_text += json.dumps([c["code"] for c in classes], ensure_ascii=False) + "\n"
    (out / "data.yaml").write_text(yaml_text, encoding="utf-8")
    report = {"source": str(source), "counts": dict(counts),
              "excluded": dict(Counter(row["reason"] for row in rejected)),
              "category_ids": "Unchanged taxonomy_v1 IDs, zero-based; do not enable COCO-80 remap.",
              "per_class": [{"id": c["id"], "code": c["code"],
                             **{s: per_class[(s, c["id"])] for s in ("train", "val", "test")}} for c in classes],
              "limitations": ["Trusted refers to geometry provenance, not new expert diagnosis verification.",
                               "Original near-duplicate/history limitations remain; not a new independent field test.",
                               "GT crops are for representation training, not end-to-end model ranking.",
                               "Images may be hard links; training must never modify source images in place.",
                               "Source licenses retained; no blanket license granted by this conversion."],
              "smoke_limit": limit}
    json_write(out / "report.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / "ml-pipeline/dataset_allcrops_v1")
    parser.add_argument("--out", type=Path, default=ROOT / "ml-pipeline/dataset_vision_training_v1")
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    build(args.source, args.out, args.limit)


if __name__ == "__main__":
    main()
