"""Build dataset_ext_v2: external images for the 49 classes of taxonomy v1, split into training parts.

Stages (each caches its result in ml-pipeline/.cache/external/work/, rerun is cheap):
  collect  - list images of the folders mapped in mapping_v2.json, with the class from the source
  hash     - pHash of 8 rotations/flips; drop images that repeat the current sources
             (hash_index.py current_sources) and collapse near-duplicates inside the new data
  select   - cap images per class (field first), assign train/val/test by duplicate group
  boxes    - whole-leaf boxes from the leaf detector (yolo26s trained on clean v2) where the
             source has no leaf boxes
  write    - apply the box policy, write YOLO images/labels, parts of the train split,
             val/test lists, report.json and previews

  python -X utf8 ml-pipeline/external/build_external_v2.py all
  python -X utf8 ml-pipeline/external/build_external_v2.py write --part-size 3000

The class always comes from the source folder; the detector only draws leaf boxes.
"""

import argparse
import csv
import hashlib
import json
import random
import shutil
import time
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import imagehash
import numpy as np
import yaml
from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
RAW = ROOT / "ml-pipeline/.cache/external/raw"
WORK = ROOT / "ml-pipeline/.cache/external/work"
INDEX = ROOT / "ml-pipeline/.cache/external/hash_index/current_sources.npy"
DETECTOR = ROOT / "ml-pipeline/.cache/external/models/leaf_yolo26s_cleanv2.pt"
TAXONOMY = ROOT / "ml-pipeline/allcrops/taxonomy_v1.json"
OUT = ROOT / "ml-pipeline/dataset_ext_v2"

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
SEED = 20261018
DUPLICATE_BITS = 6          # pHash distance treated as the same photo
MAX_PER_CLASS_FIELD = 1500  # new images per class, field domain
MAX_PER_CLASS_LAB = 600     # new images per class, lab domain
DETECTOR_CONF = 0.25
DETECTOR_IMGSZ = 416
KEEP_RELATIVE_AREA = 0.5    # disease photo: keep leaves at least half as large as the largest one
MAX_DISEASE_BOXES = 3
CROP_MARGIN = 0.08
FULL_FRAME_BOX = [0.5, 0.5, 0.98, 0.98]
OUTPUT_LONG_SIDE = 1024


def unit_hash(text):
    """Deterministic value in [0, 1) for splits and ordering."""
    digest = hashlib.sha256(f"{SEED}:{text}".encode()).hexdigest()
    return int(digest[:12], 16) / 16 ** 12


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=1), encoding="utf-8")


def taxonomy_codes():
    return {c["code"]: c["id"] for c in read_json(TAXONOMY)["classes"]}


# ---------------------------------------------------------------- collect

def collect_folders(source, spec):
    records = []
    for folder, code in spec.get("folders", {}).items():
        base = RAW / source / folder
        if not base.exists():
            raise FileNotFoundError(base)
        for path in sorted(base.rglob("*")):
            if path.suffix.lower() in IMAGE_SUFFIXES:
                records.append({"path": str(path), "class": code})
    return records


def collect_csv(source, spec):
    table = spec["csv_labels"]
    records = []
    with (RAW / source / table["file"]).open(encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            code = table["map"].get(row["labels"].strip())
            if code:
                records.append({"path": str(RAW / source / table["images"] / row["image"]), "class": code})
    return records


def read_yolo_boxes(label_path):
    boxes = []
    if label_path.exists():
        for line in label_path.read_text().split("\n"):
            parts = line.split()
            if len(parts) == 5:
                boxes.append([int(parts[0])] + [float(v) for v in parts[1:]])
    return boxes


def collect_yolo(source, spec):
    """Roboflow exports: one class per image is required (the class goes to every leaf box)."""
    table = spec["yolo_boxes"]
    names = yaml.safe_load((RAW / source / table["yaml"]).read_text(encoding="utf-8"))["names"]
    records = []
    for image in sorted((RAW / source).rglob("*")):
        if image.suffix.lower() not in IMAGE_SUFFIXES or image.parent.name != "images":
            continue
        label = image.parent.parent / "labels" / (image.stem + ".txt")
        boxes = read_yolo_boxes(label)
        classes = {names[b[0]] for b in boxes}
        if len(classes) != 1:
            continue
        code = table["map"].get(classes.pop())
        if code:
            records.append({"path": str(image), "class": code, "source_boxes": [b[1:] for b in boxes]})
    return records


def stage_collect():
    mapping = read_json(HERE / "mapping_v2.json")
    codes = taxonomy_codes()
    records = []
    for source, spec in mapping["sources"].items():
        if "csv_labels" in spec:
            found = collect_csv(source, spec)
        elif "yolo_boxes" in spec:
            found = collect_yolo(source, spec)
        else:
            found = collect_folders(source, spec)
        for record in found:
            assert record["class"] in codes, record["class"]
            record["source"] = source
            record["domain"] = spec["domain"]
            record["closeup"] = bool(spec.get("closeup"))
            record["id"] = hashlib.sha1(record["path"].encode()).hexdigest()[:16]
        print(f"{source}: {len(found)} images")
        records.extend(found)
    write_json(WORK / "collected.json", records)
    print(f"collected {len(records)}")


# ---------------------------------------------------------------- hash

def phash_value(image):
    bits = imagehash.phash(image).hash.flatten()
    value = 0
    for bit in bits:
        value = (value << 1) | int(bit)
    return value


def dihedral_hashes(path):
    """pHash of the image and its 7 rotations/flips; None if unreadable."""
    try:
        with Image.open(path) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            image.thumbnail((256, 256))
    except Exception:
        return None
    variants = []
    for flipped in (image, ImageOps.mirror(image)):
        for angle in (0, 90, 180, 270):
            variants.append(phash_value(flipped.rotate(angle, expand=True)))
    return variants


def min_distance(queries, index, block=64):
    """For every row of queries (n x 8 uint64) the smallest Hamming distance to any index hash."""
    best = np.full(len(queries), 64, dtype=np.int64)
    for start in range(0, len(queries), block):
        chunk = queries[start:start + block]
        diff = np.bitwise_xor(chunk[:, :, None], index[None, None, :])
        distance = np.bitwise_count(diff).min(axis=(1, 2))
        best[start:start + block] = distance
    return best


def group_duplicates(hashes):
    """Union-find over images whose any rotation/flip is within DUPLICATE_BITS of the other's original."""
    parent = list(range(len(hashes)))

    def root(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    originals = hashes[:, 0]
    for i in range(len(hashes)):
        distance = np.bitwise_count(np.bitwise_xor(hashes[i][:, None], originals[None, i + 1:])).min(axis=0)
        for j in np.nonzero(distance <= DUPLICATE_BITS)[0]:
            a = root(i)
            b = root(i + 1 + int(j))
            if a != b:
                parent[b] = a
    return [root(i) for i in range(len(hashes))]


def stage_hash(workers):
    records = read_json(WORK / "collected.json")
    cache_path = WORK / "hashes.json"
    cache = read_json(cache_path) if cache_path.exists() else {}
    missing = [r for r in records if r["id"] not in cache]
    print(f"hashing {len(missing)} images ({len(records) - len(missing)} cached)")
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for record, value in zip(missing, pool.map(dihedral_hashes, [r["path"] for r in missing], chunksize=32)):
            cache[record["id"]] = [str(v) for v in value] if value else None
    write_json(cache_path, cache)

    readable = [r for r in records if cache[r["id"]]]
    hashes = np.array([[int(v) for v in cache[r["id"]]] for r in readable], dtype=np.uint64)
    index = np.load(INDEX)
    started = time.time()
    to_current = min_distance(hashes, index)
    print(f"compared with {len(index)} current images in {time.time() - started:.0f} s")
    decisions = {}
    for record in records:
        if not cache[record["id"]]:
            decisions[record["id"]] = {"status": "unreadable"}
    kept_positions = []
    for position, record in enumerate(readable):
        if to_current[position] <= DUPLICATE_BITS:
            decisions[record["id"]] = {"status": "duplicate_of_current", "distance": int(to_current[position])}
        else:
            kept_positions.append(position)
    by_class = defaultdict(list)
    for position in kept_positions:
        by_class[readable[position]["class"]].append(position)
    for code, positions in sorted(by_class.items()):
        groups = group_duplicates(hashes[positions])
        for position, group in zip(positions, groups):
            record = readable[position]
            representative = readable[positions[group]]["id"]
            status = "unique" if representative == record["id"] else "near_duplicate_new"
            decisions[record["id"]] = {"status": status, "group": f"{code}:{representative}"}
    write_json(WORK / "dedup.json", decisions)
    print(Counter(d["status"] for d in decisions.values()))


# ---------------------------------------------------------------- select

def stage_select():
    records = read_json(WORK / "collected.json")
    decisions = read_json(WORK / "dedup.json")
    unique = [r for r in records if decisions[r["id"]]["status"] == "unique"]
    by_class_domain = defaultdict(list)
    for record in unique:
        by_class_domain[(record["class"], record["domain"])].append(record)
    selected = []
    for (code, domain), items in sorted(by_class_domain.items()):
        items.sort(key=lambda r: unit_hash(r["id"]))
        cap = MAX_PER_CLASS_FIELD if domain == "field" else MAX_PER_CLASS_LAB
        for record in items[:cap]:
            group = decisions[record["id"]]["group"]
            value = unit_hash("split:" + group)
            record["split"] = "train" if value < 0.8 else "val" if value < 0.9 else "test"
            selected.append(record)
    write_json(WORK / "selected.json", selected)
    print(f"selected {len(selected)} of {len(unique)} unique")
    print(Counter((r["domain"], r["split"]) for r in selected))


# ---------------------------------------------------------------- boxes

def stage_boxes(device, batch):
    from ultralytics import YOLO
    selected = read_json(WORK / "selected.json")
    cache_path = WORK / "detections.jsonl"
    done = set()
    if cache_path.exists():
        for line in cache_path.read_text(encoding="utf-8").splitlines():
            done.add(json.loads(line)["id"])
    todo = [r for r in selected if "source_boxes" not in r and r["id"] not in done]
    print(f"detecting leaves on {len(todo)} images ({len(done)} cached), device {device}")
    model = YOLO(str(DETECTOR))
    started = time.time()
    with cache_path.open("a", encoding="utf-8") as stream:
        for start in range(0, len(todo), batch):
            chunk = todo[start:start + batch]
            results = model.predict([r["path"] for r in chunk], imgsz=DETECTOR_IMGSZ, conf=DETECTOR_CONF,
                                    agnostic_nms=True, device=device, verbose=False)
            for record, result in zip(chunk, results):
                boxes = result.boxes
                rows = []
                for xywhn, confidence in zip(boxes.xywhn.tolist(), boxes.conf.tolist()):
                    rows.append([round(v, 5) for v in xywhn] + [round(confidence, 4)])
                stream.write(json.dumps({"id": record["id"], "boxes": rows}) + "\n")
            stream.flush()
            finished = start + len(chunk)
            if finished % (batch * 20) < batch:
                rate = finished / (time.time() - started)
                print(f"  {finished}/{len(todo)}  {rate:.1f} img/s", flush=True)


# ---------------------------------------------------------------- write

def area(box):
    return box[2] * box[3]


def to_corners(box):
    return [box[0] - box[2] / 2, box[1] - box[3] / 2, box[0] + box[2] / 2, box[1] + box[3] / 2]


def overlap_fraction(box, region):
    """Share of the box area inside the region (both as corners)."""
    left = max(box[0], region[0])
    top = max(box[1], region[1])
    right = min(box[2], region[2])
    bottom = min(box[3], region[3])
    inside = max(0.0, right - left) * max(0.0, bottom - top)
    own = (box[2] - box[0]) * (box[3] - box[1])
    return inside / own if own > 0 else 0.0


def leaf_boxes(record, detections):
    """Return (boxes, crop_region or None, decision). Boxes are normalized xywh of the original image."""
    if "source_boxes" in record:
        return record["source_boxes"], None, "source_leaf_boxes"
    found = [d[:4] for d in detections]
    if not found:
        if record["closeup"]:
            return [FULL_FRAME_BOX], None, "closeup_full_frame"
        return [], None, "no_leaf_detected"
    if record["class"].endswith("__healthy"):
        return found, None, "detector_all_leaves_healthy"
    found.sort(key=area, reverse=True)
    largest = area(found[0])
    kept = [b for b in found if area(b) >= KEEP_RELATIVE_AREA * largest][:MAX_DISEASE_BOXES]
    dropped = [b for b in found if b not in kept]
    if not dropped:
        return kept, None, "detector_dominant_leaves"
    corners = [to_corners(b) for b in kept]
    width = max(c[2] for c in corners) - min(c[0] for c in corners)
    height = max(c[3] for c in corners) - min(c[1] for c in corners)
    region = [
        max(0.0, min(c[0] for c in corners) - CROP_MARGIN * width),
        max(0.0, min(c[1] for c in corners) - CROP_MARGIN * height),
        min(1.0, max(c[2] for c in corners) + CROP_MARGIN * width),
        min(1.0, max(c[3] for c in corners) + CROP_MARGIN * height),
    ]
    for box in dropped:
        if overlap_fraction(to_corners(box), region) > 0.5:
            return [], None, "unlabelled_leaf_inside_crop"
    return kept, region, "detector_dominant_leaves_cropped"


def reframe(boxes, region):
    """Express boxes (normalized to the full image) in the coordinates of the crop region."""
    width = region[2] - region[0]
    height = region[3] - region[1]
    result = []
    for box in boxes:
        corners = to_corners(box)
        left = (max(corners[0], region[0]) - region[0]) / width
        top = (max(corners[1], region[1]) - region[1]) / height
        right = (min(corners[2], region[2]) - region[0]) / width
        bottom = (min(corners[3], region[3]) - region[1]) / height
        result.append([(left + right) / 2, (top + bottom) / 2, right - left, bottom - top])
    return result


def save_image(source_path, target_path, region):
    with Image.open(source_path) as image:
        image = ImageOps.exif_transpose(image).convert("RGB")
        if region:
            w, h = image.size
            image = image.crop((round(region[0] * w), round(region[1] * h), round(region[2] * w), round(region[3] * h)))
        image.thumbnail((OUTPUT_LONG_SIDE, OUTPUT_LONG_SIDE))
        image.save(target_path, quality=90)


def assign_parts(train_records, part_size):
    """Round-robin over classes (field first inside a class) so that every part is class-balanced."""
    queues = defaultdict(list)
    for record in train_records:
        queues[record["class"]].append(record)
    for items in queues.values():
        items.sort(key=lambda r: (r["domain"] != "field", unit_hash("part:" + r["id"])))
    ordered = []
    while any(queues.values()):
        for code in sorted(queues):
            if queues[code]:
                ordered.append(queues[code].pop(0))
    for position, record in enumerate(ordered):
        record["part"] = f"part{position // part_size + 1:02d}"
    return ordered


def write_preview(records, out, per_class=4):
    shown = defaultdict(int)
    tiles = []
    for record in sorted(records, key=lambda r: unit_hash("preview:" + r["id"])):
        if shown[record["class"]] >= per_class:
            continue
        shown[record["class"]] += 1
        with Image.open(out / record["image_path"]) as image:
            tile = image.convert("RGB")
        draw = ImageDraw.Draw(tile)
        w, h = tile.size
        for box in record["boxes"]:
            c = to_corners(box)
            draw.rectangle([c[0] * w, c[1] * h, c[2] * w, c[3] * h], outline=(255, 193, 77), width=max(2, w // 150))
        tile.thumbnail((240, 240))
        tiles.append((record["class"], f"{record['source']} {record['geometry']}", tile))
    tiles.sort(key=lambda t: t[0])
    columns = 8
    rows = (len(tiles) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 250, rows * 275), "white")
    draw = ImageDraw.Draw(sheet)
    for i, (code, note, tile) in enumerate(tiles):
        x = (i % columns) * 250
        y = (i // columns) * 275
        sheet.paste(tile, (x + 5, y + 30))
        draw.text((x + 5, y + 2), code, fill="black")
        draw.text((x + 5, y + 15), note[:40], fill="gray")
    (out / "previews").mkdir(exist_ok=True)
    sheet.save(out / "previews" / "boxes_by_class.jpg", quality=80)


def stage_write(part_size, overwrite):
    selected = read_json(WORK / "selected.json")
    detections = {}
    detection_file = WORK / "detections.jsonl"
    if detection_file.exists():
        for line in detection_file.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            detections[row["id"]] = row["boxes"]
    codes = taxonomy_codes()
    if OUT.exists():
        if not overwrite:
            raise SystemExit(f"{OUT} exists; pass --overwrite")
        shutil.rmtree(OUT)
    written = []
    rejected = Counter()
    for record in selected:
        if "source_boxes" not in record and record["id"] not in detections:
            rejected["not_detected_yet"] += 1
            continue
        boxes, region, decision = leaf_boxes(record, detections.get(record["id"], []))
        if not boxes:
            rejected[decision] += 1
            continue
        if region:
            boxes = reframe(boxes, region)
        record["boxes"] = boxes
        record["geometry"] = decision
        record["region"] = region
        written.append(record)
    train = [r for r in written if r["split"] == "train"]
    assign_parts(train, part_size)
    for record in written:
        folder = record.get("part", record["split"])
        name = f"x_{record['source']}_{record['id']}.jpg"
        record["image_path"] = f"images/{folder}/{name}"
        target = OUT / record["image_path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        save_image(record["path"], target, record["region"])
        label = OUT / "labels" / folder / (Path(name).stem + ".txt")
        label.parent.mkdir(parents=True, exist_ok=True)
        lines = [f"{codes[record['class']]} " + " ".join(f"{v:.6f}" for v in box) for box in record["boxes"]]
        label.write_text("\n".join(lines) + "\n")
    write_lists(written)
    write_report(written, rejected, part_size)
    write_preview(written, OUT)
    print(f"written {len(written)} images; rejected {dict(rejected)}")


def write_lists(written):
    for split in ("val", "test"):
        for domain in ("field", "lab", None):
            rows = [r for r in written if r["split"] == split and (domain is None or r["domain"] == domain)]
            name = f"{split}_{domain}.txt" if domain else f"{split}.txt"
            (OUT / name).write_text("".join(f"./{r['image_path']}\n" for r in rows), encoding="utf-8")
    shutil.copyfile(TAXONOMY, OUT / "taxonomy_v1.json")
    names = {c["id"]: c["code"] for c in read_json(TAXONOMY)["classes"]}
    parts = sorted({r["part"] for r in written if "part" in r})
    config = {"train": [f"images/{p}" for p in parts], "val": "val.txt", "test": "test.txt", "names": names}
    (OUT / "data.yaml").write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False), encoding="utf-8")
    manifest = []
    for r in written:
        manifest.append({k: r.get(k) for k in ("image_path", "source", "class", "domain", "split", "part", "geometry", "region", "path")})
    with (OUT / "manifest.jsonl").open("w", encoding="utf-8") as stream:
        for row in manifest:
            row["path"] = Path(row["path"]).relative_to(RAW).as_posix()
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_report(written, rejected, part_size):
    collected = read_json(WORK / "collected.json")
    decisions = read_json(WORK / "dedup.json")
    status_by_source = defaultdict(Counter)
    for record in collected:
        status_by_source[record["source"]][decisions[record["id"]]["status"]] += 1
    per_part = defaultdict(Counter)
    for r in written:
        per_part[r.get("part", r["split"])][r["class"]] += 1
    digest = hashlib.sha256()
    for r in sorted(written, key=lambda r: r["image_path"]):
        digest.update(r["image_path"].encode())
        digest.update(json.dumps(r["boxes"]).encode())
    report = {
        "dataset": "ext_v2",
        "fingerprint": digest.hexdigest(),
        "taxonomy": "taxonomy_v1 (49 classes, unchanged)",
        "parameters": {"duplicate_bits": DUPLICATE_BITS, "max_per_class_field": MAX_PER_CLASS_FIELD,
                       "max_per_class_lab": MAX_PER_CLASS_LAB, "detector": DETECTOR.name, "detector_conf": DETECTOR_CONF,
                       "keep_relative_area": KEEP_RELATIVE_AREA, "max_disease_boxes": MAX_DISEASE_BOXES,
                       "part_size": part_size, "seed": SEED},
        "images": len(written),
        "boxes": sum(len(r["boxes"]) for r in written),
        "splits": dict(Counter(r["split"] for r in written)),
        "domains": dict(Counter(f"{r['domain']}/{r['split']}" for r in written)),
        "geometry": dict(Counter(r["geometry"] for r in written)),
        "rejected_at_write": dict(rejected),
        "dedup_by_source": {s: dict(c) for s, c in sorted(status_by_source.items())},
        "per_source": dict(Counter(r["source"] for r in written)),
        "per_class": {code: dict(Counter(r["split"] for r in written if r["class"] == code))
                      for code in sorted({r["class"] for r in written})},
        "parts": {p: {"images": sum(c.values()), "classes": len(c)} for p, c in sorted(per_part.items())},
    }
    write_json(OUT / "report.json", report)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("stage", choices=["collect", "hash", "select", "boxes", "write", "all"])
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--part-size", type=int, default=3000)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    random.seed(SEED)
    if args.stage in ("collect", "all"):
        stage_collect()
    if args.stage in ("hash", "all"):
        stage_hash(args.workers)
    if args.stage in ("select", "all"):
        stage_select()
    if args.stage in ("boxes", "all"):
        stage_boxes(args.device, args.batch)
    if args.stage in ("write", "all"):
        stage_write(args.part_size, args.overwrite or args.stage == "all")


if __name__ == "__main__":
    main()
