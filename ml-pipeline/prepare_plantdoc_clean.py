"""Build PlantDoc from its ZIP without overwriting case-sensitive filenames.

Original TEST remains a held-out test. Validation is derived from TRAIN.
Old datasets, checkpoints and the archive are preserved.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import io
import json
import math
import random
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import unquote
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SEED = 20261003


def alias(name):
    return unquote(name.split("?", 1)[0])


def orient_box(box, width, height, orientation):
    def transform(x, y):
        return {2: (width-x, y), 3: (width-x, height-y), 4: (x, height-y),
                5: (y, x), 6: (height-y, x), 7: (height-y, width-x),
                8: (y, width-x)}.get(orientation, (x, y))
    xmin, ymin, xmax, ymax = box
    points = [transform(x, y) for x in (xmin, xmax) for y in (ymin, ymax)]
    return min(p[0] for p in points), min(p[1] for p in points), max(p[0] for p in points), max(p[1] for p in points)


def convert_boxes(rows, raw_size, upright_size, orientation, ids, issues, source):
    boxes = []
    w, h = raw_size
    ow, oh = upright_size
    for row in rows:
        def issue(kind, action, **details):
            issues.append(dict(source=source, kind=kind, action=action, **details))
        try:
            box = tuple(float(row[k]) for k in ("xmin", "ymin", "xmax", "ymax"))
            cw, ch = float(row["width"]), float(row["height"])
            if not all(math.isfinite(v) for v in (*box, cw, ch)):
                raise ValueError("non-finite values")
            if cw <= 0 or ch <= 0:
                issue("missing_csv_size", "use_raw_size")
                box = orient_box(box, w, h, orientation)
            elif (cw, ch) == raw_size:
                box = orient_box(box, w, h, orientation)
            elif orientation in (5, 6, 7, 8) and (cw, ch) == upright_size:
                issue("upright_csv_coordinates", "keep_upright")
            elif abs((cw/ch)/(w/h)-1) <= 0.015:
                box = orient_box((box[0]*w/cw, box[1]*h/ch, box[2]*w/cw, box[3]*h/ch), w, h, orientation)
                issue("resized_source", "scale_coordinates", csv_size=[cw, ch], actual_size=list(raw_size))
            else:
                issue("ambiguous_dimensions", "exclude_box", csv_size=[cw, ch], actual_size=list(raw_size), orientation=orientation)
                continue
            xmin, ymin, xmax, ymax = box
            clipped = (max(0, min(xmin, ow)), max(0, min(ymin, oh)), max(0, min(xmax, ow)), max(0, min(ymax, oh)))
            if clipped != box:
                issue("out_of_bounds", "clip_box")
            xmin, ymin, xmax, ymax = clipped
            if xmax <= xmin or ymax <= ymin:
                raise ValueError("empty or inverted box")
            boxes.append((ids[row["class"]], (xmin+xmax)/(2*ow), (ymin+ymax)/(2*oh), (xmax-xmin)/ow, (ymax-ymin)/oh))
        except (ValueError, KeyError) as exc:
            issue("invalid_box", "exclude_box", reason=str(exc))
    return sorted(set(boxes))


def validation_split(records, fraction, seed):
    records = sorted(records, key=lambda r: r["pixel_hash"])
    random.Random(seed).shuffle(records)
    support = Counter(k for r in records for k in {b[0] for b in r["boxes"]})
    remaining, selected, val_classes = support.copy(), set(), set()
    def select(record):
        classes = {b[0] for b in record["boxes"]}
        if record["pixel_hash"] in selected or any(remaining[k] <= 1 for k in classes):
            return False
        selected.add(record["pixel_hash"])
        remaining.subtract(classes)
        val_classes.update(classes)
        return True
    for cls in sorted(support, key=lambda k: (support[k], k)):
        if cls in val_classes or support[cls] < 2:
            continue
        for record in records:
            if any(b[0] == cls for b in record["boxes"]) and select(record):
                break
    for record in records:
        if len(selected) >= max(1, round(len(records)*fraction)):
            break
        select(record)
    return {"train": [r for r in records if r["pixel_hash"] not in selected],
            "val": [r for r in records if r["pixel_hash"] in selected]}


def build_dataset(archive, output, val_fraction=0.15, seed=SEED):
    archive, output = Path(archive), Path(output)
    if output.exists():
        raise FileExistsError(f"Dataset version already exists: {output}")
    if not 0 < val_fraction < 0.5:
        raise ValueError("val_fraction must be between 0 and 0.5")
    issues, records, resolutions = [], [], []
    with zipfile.ZipFile(archive) as z:
        members = z.namelist()
        member_set = set(members)
        prefix = next(n.rsplit("/", 1)[0] for n in members if n.endswith("/train_labels.csv"))
        rows_by_split = {s: list(csv.DictReader(io.StringIO(z.read(f"{prefix}/{f}").decode("utf-8-sig"))))
                         for s, f in (("TRAIN", "train_labels.csv"), ("TEST", "test_labels.csv"))}
        names = sorted({r["class"] for rows in rows_by_split.values() for r in rows})
        ids = {name: k for k, name in enumerate(names)}
        case_pairs = defaultdict(list)
        for member in members:
            if "/TRAIN/" in member and not member.endswith(("/", ".xml")):
                case_pairs[member.casefold()].append(member)
        for split, rows in rows_by_split.items():
            groups, aliases = defaultdict(list), defaultdict(list)
            for row in rows:
                groups[row["filename"]].append(row)
            for member in members:
                if member.startswith(f"{prefix}/{split}/") and not member.endswith(("/", ".xml")):
                    aliases[alias(member.rsplit("/", 1)[-1])].append(member)
            for number, (filename, group) in enumerate(sorted(groups.items()), 1):
                member = f"{prefix}/{split}/{filename}"
                if member not in member_set:
                    candidates = aliases.get(alias(filename), [])
                    if len(candidates) != 1:
                        issues.append(dict(source=f"{split}/{filename}", kind="unresolved_image", action="exclude_image", boxes=len(group), candidates=candidates))
                        continue
                    member = candidates[0]
                    resolutions.append(dict(csv_filename=filename, archive_member=member, method="strip_url_query"))
                try:
                    payload = z.read(member)
                    with Image.open(io.BytesIO(payload)) as image:
                        raw_size, orientation = image.size, image.getexif().get(274, 1)
                        upright = ImageOps.exif_transpose(image).convert("RGB")
                        upright.load()
                    boxes = convert_boxes(group, raw_size, upright.size, orientation, ids, issues, member)
                    if not boxes:
                        issues.append(dict(source=member, kind="no_valid_boxes", action="exclude_image"))
                        continue
                    pixel_hash = hashlib.sha256(str(upright.size).encode()+upright.tobytes()).hexdigest()
                    records.append(dict(source_split=split, member=member, boxes=boxes,
                                        raw_hash=hashlib.sha256(payload).hexdigest(), pixel_hash=pixel_hash,
                                        size=list(upright.size), orientation=orientation))
                except (OSError, ValueError) as exc:
                    issues.append(dict(source=member, kind="unreadable_image", action="exclude_image", reason=str(exc)))
                if number % 200 == 0:
                    print(f"Checked {split}: {number}/{len(groups)}", flush=True)
        content_groups = defaultdict(list)
        for record in records:
            content_groups[record["pixel_hash"]].append(record)
        pool, test, duplicates = [], [], []
        for pixel_hash, group in sorted(content_groups.items()):
            held_out = [r for r in group if r["source_split"] == "TEST"]
            retained = held_out or group
            record = min(retained, key=lambda r: r["member"]).copy()
            record["boxes"] = sorted({b for r in retained for b in r["boxes"]})
            record["sources"] = [r["member"] for r in retained]
            record["filename"] = "pd_"+hashlib.sha256(record["member"].encode()).hexdigest()[:20]+".jpg"
            if len(group) > 1:
                duplicates.append(dict(pixel_hash=pixel_hash, members=[r["member"] for r in group],
                                       kept_partition="test" if held_out else "train_pool",
                                       policy="test_annotations_only" if held_out else "merge_unique_annotations"))
            (test if held_out else pool).append(record)
        partitions = validation_split(pool, val_fraction, seed)
        partitions["test"] = test
        if not all(partitions.values()):
            raise ValueError("A split is empty; refusing to produce a training configuration")
        hashes = [{r["pixel_hash"] for r in partitions[s]} for s in ("train", "val", "test")]
        if any(hashes[a] & hashes[b] for a, b in ((0, 1), (0, 2), (1, 2))):
            raise AssertionError("Content leakage")
        output.mkdir(parents=True)
        for split, items in partitions.items():
            images, labels = output/"images"/split, output/"labels"/split
            images.mkdir(parents=True)
            labels.mkdir(parents=True)
            for record in items:
                with Image.open(io.BytesIO(z.read(record["member"]))) as image:
                    ImageOps.exif_transpose(image).convert("RGB").save(images/record["filename"], "JPEG", quality=95)
                (labels/Path(record["filename"]).with_suffix(".txt")).write_text(
                    "\n".join(f"{b[0]} "+" ".join(f"{v:.8f}" for v in b[1:]) for b in record["boxes"])+"\n", encoding="utf-8")
                record["split"] = split
        # Ultralytics resolves a relative path against its global datasets_dir.
        # The trainer replaces this value with the actual absolute dataset path.
        yaml = "train: images/train\nval: images/val\ntest: images/test\nnames:\n"
        yaml += "".join(f"  {k}: {json.dumps(n)}\n" for k, n in enumerate(names))
        (output/"data.yaml").write_text(yaml, encoding="utf-8")
        flat = sorted((r for items in partitions.values() for r in items), key=lambda r: r["filename"])
        manifest = json.dumps(flat, ensure_ascii=False, sort_keys=True, indent=2)
        fingerprint = hashlib.sha256(manifest.encode()).hexdigest()
        (output/"manifest.json").write_text(manifest, encoding="utf-8")
        (output/"classes.json").write_text(json.dumps(names, ensure_ascii=False, indent=2), encoding="utf-8")
        split_counts, counts = {}, {}
        for split, items in partitions.items():
            counts[split] = Counter(b[0] for r in items for b in r["boxes"])
            split_counts[split] = dict(images=len(items), boxes=sum(counts[split].values()),
                                       absent_classes=[n for k, n in enumerate(names) if not counts[split][k]])
        report = dict(dataset=output.name, seed=seed, val_fraction=val_fraction, fingerprint=fingerprint,
                      source_archive=archive.name, source_url="https://github.com/pratikkayal/PlantDoc-Object-Detection-Dataset",
                      annotation_target="whole_leaf_with_crop_and_condition", license="CC-BY-4.0",
                      split_policy="Original TEST held out; TRAIN deduplicated and split into train/val; test content takes precedence",
                      splits=split_counts, case_collision_groups=[v for v in case_pairs.values() if len(v)>1],
                      resolved_filenames=resolutions, duplicate_groups=duplicates, issues=issues,
                      issue_counts=dict(Counter(i["kind"] for i in issues)),
                      classes=[dict(id=k, name=n, **{s: counts[s][k] for s in partitions}) for k, n in enumerate(names)],
                      limitations=["Test does not cover every declared class", "Very scarce classes remain experimental",
                                   "Decoded-pixel hashing does not detect every near-duplicate", "Whole-leaf boxes do not measure diseased tissue percentage"])
        (output/"report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        return report


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=ROOT/"ml-pipeline/plantdoc_yolo.zip")
    parser.add_argument("--output", type=Path, default=ROOT/"ml-pipeline/dataset_clean_v1")
    parser.add_argument("--val-fraction", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()
    report = build_dataset(args.archive, args.output, args.val_fraction, args.seed)
    reports = ROOT/"docs/data"
    reports.mkdir(parents=True, exist_ok=True)
    path = reports/f"{args.output.name}_report.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(dict(splits=report["splits"], issue_counts=report["issue_counts"], fingerprint=report["fingerprint"], report=str(path)), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
