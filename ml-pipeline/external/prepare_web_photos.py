"""Prepare reviewed ordinary photographs for leaf presence and species crops.

No disease labels or empty detector labels are generated. All additions are
train-only; existing validation/test sets stay separate. Source metadata and
review decisions are frozen in small JSON files next to this script.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil

import numpy as np
import requests
from PIL import Image

from build_external_v2 import dihedral_hashes, min_distance

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).parent


def validate_review(row, decision):
    if row["license_code"] not in {"cc0", "cc-by"}:
        raise ValueError("Unapproved photo license")
    if row.get("disease_label") is not None:
        raise ValueError("Ordinary photos must not acquire disease labels")
    box = decision.get("box_xyxy")
    if box is not None:
        x1, y1, x2, y2 = box
        if not (0 <= x1 < x2 <= row["width"] and 0 <= y1 < y2 <= row["height"]):
            raise ValueError("Invalid leaf rectangle")
    if not decision.get("reason"):
        raise ValueError("Review reason missing")


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path, rows):
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def prepare(out):
    rows = json.loads((HERE / "web_photo_sources_v1.json").read_text(encoding="utf-8"))
    review = json.loads((HERE / "web_photo_review_v1.json").read_text(encoding="utf-8"))
    if out.exists():
        raise FileExistsError("Use a new output directory; existing data are never overwritten")
    if set(review["decisions"]) != {str(row["photo_id"]) for row in rows}:
        raise ValueError("Each photo must have an explicit decision")
    cache = ROOT / "ml-pipeline/dataset_web_inat_candidates_v1"
    cache.mkdir(parents=True, exist_ok=True)
    paths = []
    for row in rows:
        validate_review(row, review["decisions"][str(row["photo_id"])])
        path = cache / row["image"]
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            response = requests.get(row["download_url"], timeout=45)
            response.raise_for_status()
            path.write_bytes(response.content)
        if hashlib.sha256(path.read_bytes()).hexdigest() != row["sha256"]:
            raise ValueError(f"Photo bytes changed: {row['photo_id']}")
        with Image.open(path) as image:
            if image.size != (row["width"], row["height"]):
                raise ValueError("Image dimensions changed")
        paths.append(path)
    reference_path = ROOT / "ml-pipeline/.cache/external/hash_index/current_sources.npy"
    reference = np.load(reference_path)
    hashes = np.array([dihedral_hashes(path) for path in paths], dtype=np.uint64)
    distances = min_distance(hashes, reference, block=8)
    out.mkdir(parents=True)
    accepted, excluded, crops = [], [], []
    seen = []
    for row, path, variants, distance in zip(rows, paths, hashes, distances):
        decision = review["decisions"][str(row["photo_id"])]
        reason = None
        if not decision["accept"]:
            reason = decision["reason"]
        elif distance <= 6:
            reason = "near_duplicate_existing_reference"
        elif any((int(a) ^ int(b)).bit_count() <= 6 for a in variants for b in seen):
            reason = "near_duplicate_within_pilot"
        if reason:
            excluded.append({"photo_id": row["photo_id"], "reason": reason})
            continue
        seen.extend(variants)
        target = out / row["image"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        item = dict(row, split="train", target="leaf_present", review=decision,
                    reviewer=review["reviewer"], annotation_scope="scene_presence_and_optional_partial_box",
                    disease_label=None, source_group=f"inat:{row['author_id']}:{row['observed_on']}",
                    reference_phash_distance=int(distance))
        accepted.append(item)
        if "box_xyxy" in decision:
            relative = f"crops/{row['photo_id']}.jpg"
            (out / "crops").mkdir(exist_ok=True)
            with Image.open(path) as image:
                image.crop(decision["box_xyxy"]).convert("RGB").save(out / relative, quality=95)
            crops.append(dict(item, image=relative, source_image=row["image"],
                              target="species_only" if row["source_species"] == "Cucumis sativus" else "unknown_crop",
                              source_sha256=row["sha256"],
                              sha256=hashlib.sha256((out / relative).read_bytes()).hexdigest()))
    write_jsonl(out / "train.jsonl", accepted)
    write_jsonl(out / "crops.jsonl", crops)
    write_json(out / "excluded.json", excluded)
    write_json(out / "source_metadata.json", rows)
    report = {"training_ready_for": ["leaf_presence_train_positives", "selected_species_crops"],
              "training_ready_for_taxonomy_v1_disease_detection": False,
              "counts": {"candidates": len(rows), "train_images": len(accepted),
                         "derived_crops": len(crops), "excluded": len(excluded)},
              "species": dict(Counter(row["source_species"] for row in accepted)),
              "licenses": dict(Counter(row["license_code"] for row in accepted)),
              "screening": {"reference": reference_path.relative_to(ROOT).as_posix(),
                            "reference_hashes": len(reference), "phash_threshold": 6},
              "limitations": ["AI visual review, not expert disease diagnosis.",
                              "Partial boxes are not complete detection annotations.",
                              "Medium-size public images, not original-resolution independent field test.",
                              "All records are supplemental train; no new evaluation split.",
                              "Reference pHash screening is not global web deduplication.",
                              "iNaturalist research grade supports species, not health status.",
                              "Crops are derivatives of the same observations, not new images."]}
    write_json(out / "report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "ml-pipeline/dataset_web_leaf_presence_v1")
    prepare(parser.parse_args().out)
