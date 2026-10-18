"""Prepare the pinned OPTIMA v1 RGB subset as separate source-task training data.

Source boxes indicate disease regions, not AgroVoice whole-leaf boxes. Every
record is train-only: v1 has insufficient independent sessions for a new test.
"""
import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import zipfile

import numpy as np
import requests
from PIL import Image, ImageDraw, ImageOps

from build_external_v2 import dihedral_hashes, min_distance

ROOT = Path(__file__).resolve().parents[2]
RECORD = "https://zenodo.org/api/records/6778647"
CATEGORIES = ["apple_applescab", "carrot_alternaria", "grape_downymildew"]


def parse_boxes(text):
    boxes = []
    for line in text.splitlines():
        fields = line.split()
        if not fields:
            continue
        if len(fields) != 5 or fields[0] != "0":
            raise ValueError("Unexpected native class or annotation format")
        x, y, width, height = map(float, fields[1:])
        if not all(np.isfinite([x, y, width, height])) or width <= 0 or height <= 0:
            raise ValueError("Invalid source geometry")
        if min(x - width / 2, y - height / 2) < -1e-5 or max(x + width / 2, y + height / 2) > 1 + 1e-5:
            raise ValueError("Source box outside image")
        boxes.append([x, y, width, height])
    return boxes


def main(out):
    if out.exists():
        raise FileExistsError("Choose a new output directory")
    response = requests.get(RECORD, timeout=45)
    response.raise_for_status()
    metadata = response.json()
    if metadata["metadata"]["license"]["id"] != "cc-by-4.0":
        raise ValueError("Source license changed; review before use")
    file = next(item for item in metadata["files"] if item["key"].endswith(".zip"))
    cache = ROOT / "ml-pipeline/.cache/external/optima_6778647.zip"
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists():
        with requests.get(file["links"]["self"], stream=True, timeout=(20, 90)) as response:
            response.raise_for_status()
            with cache.with_suffix(".part").open("wb") as stream:
                for block in response.iter_content(1024 * 1024):
                    stream.write(block)
        cache.with_suffix(".part").rename(cache)
    if "md5:" + hashlib.md5(cache.read_bytes()).hexdigest() != file["checksum"]:
        raise ValueError("Source archive checksum mismatch")
    out.mkdir(parents=True)
    (out / "source_metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    records, excluded = [], []
    with zipfile.ZipFile(cache) as archive:
        if archive.testzip() is not None:
            raise ValueError("Invalid archive CRC")
        names = set(archive.namelist())
        for name in sorted(names):
            if "/rgb/" not in name or not name.endswith(".png"):
                continue
            category = name.split("/")[1]
            label_name = name[:-4] + ".txt"
            if label_name not in names:
                excluded.append({"source": name, "reason": "missing_annotation"})
                continue
            text = archive.read(label_name).decode("utf-8-sig")
            boxes = parse_boxes(text)
            relative = Path("images") / category / Path(name).name
            path = out / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(name))
            label = out / "native_labels" / category / Path(label_name).name
            label.parent.mkdir(parents=True, exist_ok=True)
            label.write_text(text, encoding="utf-8")
            with Image.open(path) as image:
                width, height = image.size
            records.append({"image": relative.as_posix(), "width": width, "height": height,
                            "source_label": category, "split": "train", "source_member": name,
                            "session_group": category + ":" + Path(name).stem.split("T")[0],
                            "license": "CC-BY-4.0", "source_url": "https://doi.org/10.5281/zenodo.6778647",
                            "boxes_xywh_normalized": boxes, "annotation_scope": "source_disease_regions",
                            "taxonomy_v1_class_id": None,
                            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    hashes = np.array([dihedral_hashes(out / row["image"]) for row in records], dtype=np.uint64)
    reference = np.load(ROOT / "ml-pipeline/.cache/external/hash_index/current_sources.npy")
    distances = min_distance(hashes, reference, block=8)
    accepted, seen = [], []
    for row, variants, distance in zip(records, hashes, distances):
        if distance <= 6 or any((int(a) ^ int(b)).bit_count() <= 6 for a in variants for b in seen):
            excluded.append({"source": row["source_member"], "reason": "near_duplicate"})
            continue
        accepted.append(dict(row, reference_phash_distance=int(distance)))
        seen.extend(variants)
    (out / "train.jsonl").write_text("".join(json.dumps(row) + "\n" for row in accepted), encoding="utf-8")
    coco = {"images": [], "annotations": [], "categories": [{"id": i, "name": name} for i, name in enumerate(CATEGORIES)]}
    for i, row in enumerate(accepted):
        coco["images"].append({"id": i, "file_name": row["image"], "width": row["width"], "height": row["height"]})
        for x, y, width, height in row["boxes_xywh_normalized"]:
            bbox = [(x - width / 2) * row["width"], (y - height / 2) * row["height"], width * row["width"], height * row["height"]]
            coco["annotations"].append({"id": len(coco["annotations"]), "image_id": i,
                "category_id": CATEGORIES.index(row["source_label"]), "bbox": bbox,
                "area": bbox[2] * bbox[3], "iscrowd": 0})
    (out / "source_task_train_coco.json").write_text(json.dumps(coco), encoding="utf-8")
    (out / "excluded.json").write_text(json.dumps(excluded, indent=2), encoding="utf-8")
    report = {"source": RECORD, "pinned_version": "1", "latest_known_record": 8339572,
              "license": "CC-BY-4.0", "archive_bytes": cache.stat().st_size,
              "archive_sha256": hashlib.sha256(cache.read_bytes()).hexdigest(),
              "counts": {"rgb_candidates": len(records), "accepted_train_images": len(accepted),
                         "boxes": len(coco["annotations"]), "excluded": len(excluded)},
              "classes": dict(Counter(row["source_label"] for row in accepted)),
              "sessions": dict(Counter(row["session_group"] for row in accepted)),
              "training_ready_for": ["auxiliary_representation_train", "separate_source_lesion_task_train"],
              "training_ready_for_taxonomy_v1_leaf_detection": False,
              "limitations": ["Historical v1 pilot; latest v2 is a larger archive not prepared here.",
                              "Source annotations retained, not independently re-diagnosed.",
                              "Disease-region boxes must never be relabelled as whole leaves.",
                              "All accepted samples are train-only; no independent test split claimed.",
                              "Multispectral images excluded from RGB Android task.",
                              "Only train.jsonl / COCO-listed files are accepted; excluded originals remain.",
                              "Current-source pHash <=6 screening cannot prove global independence."]}
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    preview = Image.new("RGB", (1200, 900), "white")
    draw = ImageDraw.Draw(preview)
    for i, row in enumerate(accepted[::max(1, len(accepted) // 9)][:9]):
        with Image.open(out / row["image"]) as image:
            preview.paste(ImageOps.contain(image.convert("RGB"), (390, 260)), ((i % 3) * 400, (i // 3) * 300))
        draw.text(((i % 3) * 400, (i // 3) * 300 + 265), row["source_label"], fill="black")
    preview.save(out / "preview.jpg")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "ml-pipeline/dataset_optima_rgb_v1")
    main(parser.parse_args().out)
