"""Prepare human-labelled nonplant scenes with attribution and author-group splits.

Explicit negative Plant plus positive object labels generate only candidates.
Visual review is mandatory before empty leaf annotations are written. Open Images
validation is the source subset; local splits are named separately.
"""

import csv
import argparse
import hashlib
import json
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import imagehash
from PIL import Image, ImageDraw, ImageOps

from prepare_selected import ROOT, download, fresh_output, write_json

LABELS = {"/m/05s2s"}
OBJECT_NAMES = {"Chair", "Table", "Desk", "Computer keyboard", "Computer monitor",
                "Laptop", "Mobile phone", "Mug", "Coffee cup", "Spoon", "Fork",
                "Scissors", "Book", "Pen", "Car", "Bus", "Bicycle", "Hammer",
                "Wrench", "Bottle", "Tool", "Clock", "Flag", "Toy", "Toilet",
                "Sink", "Bathtub", "Handbag"}


def eligible_ids(rows):
    negative = defaultdict(set)
    contradictory = set()
    for row in rows:
        if row["LabelName"] in LABELS:
            if row["Confidence"] == "0":
                negative[row["ImageID"]].add(row["LabelName"])
            else:
                contradictory.add(row["ImageID"])
    return {key for key, labels in negative.items() if labels == LABELS and key not in contradictory}


def author_split(author):
    value = int(hashlib.sha256(author.encode()).hexdigest()[:8], 16) % 10
    return "train" if value < 6 else "val" if value < 8 else "test"


def load_image(row):
    image_id = row["ImageID"]
    url = f"https://open-images-dataset.s3.amazonaws.com/validation/{image_id}.jpg"
    path, receipt = download(url, f"oi_{image_id}.jpg")
    return row, path, receipt


def prepare_candidates():
    labels, label_receipt = download(
        "https://storage.googleapis.com/openimages/v5/validation-annotations-human-imagelabels.csv", "oi_labels.csv")
    metadata, metadata_receipt = download(
        "https://storage.googleapis.com/openimages/2018_04/validation/validation-images-with-rotation.csv", "oi_metadata.csv")
    descriptions, _ = download("https://storage.googleapis.com/openimages/v7/oidv7-class-descriptions-boxable.csv", "oi_classes.csv")
    with descriptions.open(encoding="utf-8") as stream:
        classes = dict(csv.reader(stream))
    with labels.open(encoding="utf-8") as stream:
        label_rows = list(csv.DictReader(stream))
    eligible = eligible_ids(label_rows)
    objects = {r["ImageID"] for r in label_rows if r["Confidence"] == "1"
               and classes.get(r["LabelName"]) in OBJECT_NAMES}
    eligible &= objects
    with metadata.open(encoding="utf-8") as stream:
        candidates = [r for r in csv.DictReader(stream) if r["ImageID"] in eligible
                      and r["License"] == "https://creativecommons.org/licenses/by/2.0/"
                      and r["AuthorProfileURL"] and r["OriginalLandingURL"]]
    # One image per author in this small pilot avoids sequence/person split leakage.
    chosen = []
    authors = set()
    for row in sorted(candidates, key=lambda r: hashlib.sha256(r["ImageID"].encode()).hexdigest()):
        if row["AuthorProfileURL"] not in authors:
            authors.add(row["AuthorProfileURL"])
            chosen.append(row)
        if len(chosen) == 64:
            break
    with ThreadPoolExecutor(max_workers=4) as pool:
        loaded = list(pool.map(load_image, chosen))
    out = fresh_output("dataset_nonplant_candidates_v2")
    records = []
    previous_hashes = []
    excluded = []
    for row, path, receipt in loaded:
        with Image.open(path) as source:
            rgb = source.convert("RGB")
            variants = [int(str(imagehash.phash(view.rotate(angle, expand=True))), 16)
                        for view in (rgb, ImageOps.mirror(rgb)) for angle in (0, 90, 180, 270)]
            if any((a ^ b).bit_count() <= 6 for a in variants for b in previous_hashes):
                excluded.append(row["ImageID"])
                continue
            previous_hashes.extend(variants)
            split = author_split(row["AuthorProfileURL"])
            relative = Path("images") / split / path.name
            target = out / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(path.read_bytes())
            records.append({"image": relative.as_posix(), "split": split,
                            "target": "pending_visual_review", "source_subset": "validation",
                            "source": "OpenImages", "source_id": row["ImageID"],
                            "author": row["Author"], "author_url": row["AuthorProfileURL"],
                            "landing_url": row["OriginalLandingURL"], "license": row["License"],
                            "evidence": "Human annotations: Plant=0 AND a household/object class=1; insufficient without review",
                            "sha256": receipt["sha256"], "download_url": receipt["url"]})
    with (out / "manifest.jsonl").open("w", encoding="utf-8") as stream:
        for row in records:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    for split in ("train", "val", "test"):
        (out / f"{split}.txt").write_text("".join(r["image"] + "\n" for r in records if r["split"] == split), encoding="utf-8")
    previews = out / "previews"
    previews.mkdir()
    for page in range(0, len(records), 16):
        sheet = Image.new("RGB", (1024, 800), "white")
        draw = ImageDraw.Draw(sheet)
        for offset, row in enumerate(records[page:page + 16]):
            x, y = (offset % 4) * 256, (offset // 4) * 200
            with Image.open(out / row["image"]) as im:
                thumb = ImageOps.contain(im.convert("RGB"), (250, 170))
                sheet.paste(thumb, (x, y))
            draw.text((x, y + 174), f"{page + offset}: {row['source_id']}", fill="black")
        sheet.save(previews / f"page_{page // 16}.jpg")
    write_json(out / "report.json", {"counts": dict(Counter(r["split"] for r in records)),
               "training_ready": False,
               "selection": "Explicit human negative Plant and positive object; CC-BY-2.0 images; one image per author",
               "annotations_license": "CC-BY-4.0", "downloads": [label_receipt, metadata_receipt],
               "near_duplicate_excluded": excluded,
               "limitations": ["Small nonplant pilot, not proof of zero false diagnoses.",
                               "Source annotation errors remain possible; contact sheets provided.",
                               "Candidate queue only: negative source labels can coexist with visible foliage.",
                               "Local author-group splits differ from Open Images source split.",
                               "Use only train negatives in training; val for calibration; test after selection."]})
    print(out, len(records), flush=True)


def apply_review(review_path):
    source = ROOT / "ml-pipeline/dataset_nonplant_candidates_v2"
    review = json.loads(review_path.read_text(encoding="utf-8"))
    with (source / "manifest.jsonl").open(encoding="utf-8") as stream:
        rows = [json.loads(line) for line in stream]
    decisions = review["decisions"]
    if set(decisions) != {r["source_id"] for r in rows}:
        raise ValueError("Every candidate must have an explicit review decision")
    if any(d["decision"] not in {"accept", "exclude"} or not d["reason"] for d in decisions.values()):
        raise ValueError("Invalid review")
    out = fresh_output("dataset_nonplant_openimages_v2")
    selected = []
    for row in rows:
        decision = decisions[row["source_id"]]
        if decision["decision"] != "accept":
            continue
        target = out / row["image"]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((source / row["image"]).read_bytes())
        label = out / "labels" / row["split"] / (target.stem + ".txt")
        label.parent.mkdir(parents=True, exist_ok=True)
        label.write_text("", encoding="utf-8")
        selected.append(dict(row, target="nonplant_scene", review=decision))
    with (out / "manifest.jsonl").open("w", encoding="utf-8") as stream:
        for row in selected:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    for split in ("train", "val", "test"):
        (out / f"{split}.txt").write_text("".join(r["image"] + "\n" for r in selected if r["split"] == split), encoding="utf-8")
    write_json(out / "review.json", review)
    report = json.loads((source / "report.json").read_text(encoding="utf-8"))
    report.update(training_ready=True, counts=dict(Counter(r["split"] for r in selected)),
                  reviewed=len(rows), excluded=len(rows) - len(selected),
                  review_scope="Assistant visual absence-of-foliage screening; not expert disease annotation")
    report["limitations"] = ["Small reviewed pilot; independent household/field recordings still required.",
                              "AI visual screening can miss small foliage; inspect full resolution before final acceptance.",
                              "CC-BY-2.0 individual image attribution is in manifest.jsonl; annotations CC-BY-4.0.",
                              "Only train split can be included in training."]
    write_json(out / "report.json", report)
    print(out, report["counts"], flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review", type=Path)
    args = parser.parse_args()
    if args.review:
        apply_review(args.review)
    else:
        prepare_candidates()
