"""Build the all-crops YOLO dataset (allcrops_v1) from already audited sources.

Sources and the decisions reused from earlier audits:
  * PlantDoc records of dataset_clean_v2 keep their train/val/test split (field photos).
  * PlantVillage, OLID and Zenodo cucumber come from annotation_review_queue_r2:
    - quarantine records are excluded;
    - records whose family was in historical val_aux go to val, historical train families go to train;
    - everything else is split 80/10/10 by the precomputed group component (no group crosses splits).
  * PlantVillage boxes come from the publisher masks (geometry audit r1); masks with leakage/empty/
    mismatch/multiple components are excluded; PlantVillage is capped per class to limit lab-domain dominance.
  * OLID boxes are the adjudicated whole-leaf boxes; Zenodo lesion boxes become a leaf-extent union (flagged).

The script never trains. Output: ml-pipeline/dataset_allcrops_v1 (git-ignored) with report.json and previews.
Usage:
  python -X utf8 ml-pipeline/build_allcrops_v1.py            # full build
  python -X utf8 ml-pipeline/build_allcrops_v1.py --limit-per-class 5 --out <tmp>   # quick smoke build
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import io
import json
import os
from pathlib import Path, PureWindowsPath
import random
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
STUDY = ROOT/"docs/experiments/yolo26s_data_v1"
TAXONOMY = ROOT/"ml-pipeline/allcrops/taxonomy_v1.json"
QUEUE = STUDY/"annotation_review_queue_r2/queue.jsonl"
PV_RECORDS = [STUDY/"plantvillage_full_geometry_r1/records.jsonl", STUDY/"plantvillage_extra_conditions_r1/records.jsonl"]
OLID_RECORDS = STUDY/"olid_original_geometry_r3/records.jsonl"
OLID_ARCHIVES = ROOT/"ml-pipeline/source_candidates/research_2026-10-03/olid"
ZENODO_INVENTORY = STUDY/"cucumber_review/inventory.json"
ZENODO_ARCHIVE = ROOT/"ml-pipeline/source_candidates/cucumber_zenodo_16816441_tags.zip"
CLEAN_V2 = ROOT/"ml-pipeline/dataset_clean_v2"

PV_EXCLUDED_FLAGS = {"empty_mask", "mask_rgb_geometry_mismatch", "mask_area_or_background_leakage", "multiple_components"}
PV_DEPRIORITIZED_FLAGS = {"mask_spans_background_or_truncated_leaf"}
# Every Corn healthy mask is unusable, but these 256 px crops are filled by the leaf blade edge to edge.
PV_FULL_FRAME_CLASSES = {"Corn_(maize)___healthy"}
FULL_FRAME_BOX = [.5, .5, .98, .98]
SPLITS = ("train", "val", "test")
MAX_RARE_REPEAT = 6
PREFIX = {"PlantDoc": "pd", "PlantVillage": "pv", "OLID": "olid", "CucumberZenodo": "cuz"}


def read_jsonl(path: Path):
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def unit_hash(seed: int, text: str) -> float:
    """Deterministic value in [0, 1) used for splits and caps."""
    return int(hashlib.sha256(f"{seed}:{text}".encode()).hexdigest()[:12], 16)/16**12


def root_path(windows_relative: str) -> Path:
    return ROOT.joinpath(*PureWindowsPath(windows_relative).parts)


class Taxonomy:
    def __init__(self, path: Path = TAXONOMY):
        self.data = json.loads(path.read_text(encoding="utf-8"))
        self.classes = self.data["classes"]
        assert [c["id"] for c in self.classes] == list(range(len(self.classes))), "class IDs must be contiguous"
        self.by_code = {c["code"]: c["id"] for c in self.classes}
        assert len(self.by_code) == len(self.classes), "duplicate class code"
        self.mapping = {source: {key: self.by_code[code] for key, code in table.items()}
                        for source, table in self.data["source_mapping"].items()}
        self.excluded = self.data.get("excluded_source_classes", {})

    def class_id(self, source: str, source_class: str):
        """Return class ID, None for an explicit exclusion; unknown classes are an error."""
        if source_class in self.excluded.get(source, {}):
            return None
        try:
            return self.mapping[source][source_class]
        except KeyError:
            raise KeyError(f"{source} class {source_class!r} is neither mapped nor excluded in {TAXONOMY.name}") from None


def assign_split(record: dict, seed: int, train_fraction=.8, val_fraction=.1):
    """Group-aware split; returns None for quarantine."""
    if record["split_rule"].startswith("quarantine") or record["overlap_decision"].startswith("quarantine"):
        return None
    history = record.get("historical_splits") or []
    if any(split in ("val", "val_aux", "test") for split in history):
        return "val"  # historical holdout families never go to train
    if "train" in history:
        return "train"
    value = unit_hash(seed, record["component_id"])
    return "train" if value < train_fraction else "val" if value < train_fraction+val_fraction else "test"


def box_area(box):
    return box[2]*box[3]


def union_box(boxes, margin=.03):
    left = min(b[0]-b[2]/2 for b in boxes); top = min(b[1]-b[3]/2 for b in boxes)
    right = max(b[0]+b[2]/2 for b in boxes); bottom = max(b[1]+b[3]/2 for b in boxes)
    width = right-left; height = bottom-top
    left = max(0., left-margin*width); top = max(0., top-margin*height)
    right = min(1., right+margin*width); bottom = min(1., bottom+margin*height)
    return [(left+right)/2, (top+bottom)/2, right-left, bottom-top]


def zenodo_boxes(source_class: str, source_boxes):
    """Convert Zenodo boxes (class, cx, cy, w, h) to whole-leaf boxes; returns (boxes, geometry_method)."""
    boxes = [list(map(float, b[1:5])) for b in source_boxes]
    if not boxes:
        return [], "no_source_box"
    if source_class == "fresh_leaf":
        kept = [b for b in boxes if box_area(b) >= .02] or [max(boxes, key=box_area)]
        return kept, "source_leaf_boxes"
    if source_class == "anthracnose":
        large = [b for b in boxes if box_area(b) >= .05]
        return (large, "source_leaf_boxes") if large else ([union_box(boxes)], "lesion_union_auto_needs_review")
    # bacterial_wilt / downy_mildew: source boxes are lesions; approximate the affected leaf extent.
    return [union_box(boxes)], "lesion_union_auto_needs_review"


def valid_box(box):
    cx, cy, w, h = box
    return 0 < w <= 1 and 0 < h <= 1 and 0 <= cx <= 1 and 0 <= cy <= 1


def clip_box(box):
    left = max(0., box[0]-box[2]/2); top = max(0., box[1]-box[3]/2)
    right = min(1., box[0]+box[2]/2); bottom = min(1., box[1]+box[3]/2)
    return [(left+right)/2, (top+bottom)/2, right-left, bottom-top]


def collect(taxonomy: Taxonomy, seed: int, plantdoc_repeat: int, pv_cap_train: int, pv_cap_eval: int,
            limit_per_class: int | None = None, min_train_images: int = 0):
    """Return (items, exclusions). Each item describes one output image; nothing is written here."""
    items, exclusions = [], collections.Counter()
    pv_geometry = {}
    for path in PV_RECORDS:
        for record in read_jsonl(path):
            pv_geometry[record["id"]] = record
    olid_records = {record["id"]: record for record in read_jsonl(OLID_RECORDS)}
    zenodo = {image["id"]: image for image in json.loads(ZENODO_INVENTORY.read_text(encoding="utf-8"))["images"]}

    pv_pool = collections.defaultdict(list)
    for record in read_jsonl(QUEUE):
        source = record["source"]
        class_id = taxonomy.class_id(source, record["source_class"])
        if class_id is None:
            exclusions[f"{source}:excluded_class:{record['source_class']}"] += 1
            continue
        split = assign_split(record, seed)
        if split is None:
            exclusions[f"{source}:quarantine"] += 1
            continue
        base = dict(source=source, source_id=record["id"], source_class=record["source_class"],
                    class_id=class_id, split=split, component_id=record["component_id"],
                    overlap_decision=record["overlap_decision"])
        if source == "PlantVillage":
            geometry = pv_geometry.get(record["id"])
            if geometry is None:
                exclusions["PlantVillage:missing_geometry_record"] += 1
                continue
            flags = set(geometry.get("flags") or [])
            method = "publisher_mask_bbox"
            if record["source_class"] in PV_FULL_FRAME_CLASSES:
                box, method = FULL_FRAME_BOX, "full_frame_leaf_fill"
            elif flags & PV_EXCLUDED_FLAGS or not geometry.get("proposed_box_xywh"):
                exclusions["PlantVillage:mask_flag_excluded"] += 1
                continue
            else:
                box = clip_box(geometry["proposed_box_xywh"])
            if not valid_box(box):
                exclusions["PlantVillage:invalid_box"] += 1
                continue
            image = root_path(geometry["source_files"]["color"]["candidate_path"])
            pv_pool[(class_id, split)].append(dict(base, boxes=[box], geometry=method,
                                                   image=dict(kind="file", path=str(image)),
                                                   priority=1 if flags & PV_DEPRIORITIZED_FLAGS else 0))
        elif source == "OLID":
            geometry = (record.get("whole_leaf_geometry") or {}).get("box_xywh")
            original = olid_records.get(record["id"])
            if not geometry or original is None:
                exclusions["OLID:missing_geometry"] += 1
                continue
            items.append(dict(base, boxes=[clip_box(geometry)], geometry="adjudicated_whole_leaf",
                              image=dict(kind="zip", archive=str(OLID_ARCHIVES/original["archive"]),
                                         member=original["image_member"], exif="transpose")))
        elif source == "CucumberZenodo":
            original = zenodo.get(record["id"])
            if original is None:
                exclusions["CucumberZenodo:missing_inventory"] += 1
                continue
            boxes, method = zenodo_boxes(record["source_class"], original["source_boxes"])
            boxes = [clip_box(b) for b in boxes if valid_box(clip_box(b))]
            if not boxes:
                exclusions["CucumberZenodo:no_usable_box"] += 1
                continue
            items.append(dict(base, boxes=boxes, geometry=method,
                              image=dict(kind="nested_zip", archive=str(ZENODO_ARCHIVE),
                                         inner=original["archive_member"], member=original["image_member"],
                                         exif="require_upright")))
        else:
            raise ValueError(f"Unknown queue source {source}")

    for (class_id, split), pool in sorted(pv_pool.items()):
        cap = pv_cap_train if split == "train" else pv_cap_eval
        pool.sort(key=lambda item: (item.pop("priority"), unit_hash(seed, item["source_id"])))
        items.extend(pool[:cap])
        if len(pool) > cap:
            exclusions[f"PlantVillage:class_cap_{split}"] += len(pool)-cap

    manifest = json.loads((CLEAN_V2/"manifest.json").read_text(encoding="utf-8"))
    for record in manifest:
        if record["source_dataset"] != "PlantDoc":
            continue  # clean v2 PlantVillage/cucumber copies are re-imported from the queue above
        split = record["split"]
        boxes, classes = [], set()
        for cls, *box in record["boxes"]:
            classes.add(taxonomy.class_id("PlantDocLegacy", str(int(cls))))
            boxes.append((taxonomy.class_id("PlantDocLegacy", str(int(cls))), clip_box([float(v) for v in box])))
        repeats = plantdoc_repeat if split == "train" else 1
        for repeat in range(repeats):
            items.append(dict(source="PlantDoc", source_id=record["filename"].rsplit(".", 1)[0], source_class="PlantDoc",
                              class_id=min(classes), split=split, component_id=record.get("group_id"),
                              overlap_decision="clean_v2_frozen_split", boxes=None, multi_boxes=boxes,
                              geometry="plantdoc_source_box", repeat=repeat,
                              image=dict(kind="file", path=str(CLEAN_V2/"images"/split/record["filename"]), exif="transpose")))

    if min_train_images:
        # Ultralytics has no class weights: repeat rare non-PlantDoc train images (PlantDoc already has its own repeat).
        by_class = collections.defaultdict(list)
        for item in items:
            if item["split"] == "train" and item["source"] != "PlantDoc":
                by_class[item["class_id"]].append(item)
        for class_id, members in sorted(by_class.items()):
            total = len(members)+sum(1 for i in items if i["split"] == "train" and i["source"] == "PlantDoc" and i["class_id"] == class_id)
            copies = min(MAX_RARE_REPEAT-1, -(-max(0, min_train_images-total)//len(members)))
            for repeat in range(1, copies+1):
                items.extend(dict(member, repeat=repeat, overlap_decision=member["overlap_decision"]+"+rare_class_repeat") for member in members)
            if copies:
                exclusions[f"info:rare_class_repeat:{class_id}"] = copies

    if limit_per_class:
        counts, limited = collections.Counter(), []
        for item in sorted(items, key=lambda item: unit_hash(seed, item["source"]+item["source_id"])):
            key = (item["class_id"], item["split"], item["source"])
            if counts[key] < limit_per_class:
                counts[key] += 1
                limited.append(item)
        items = limited
    return items, exclusions


def output_name(item):
    stem = f"{PREFIX[item['source']]}_{item['source_id']}".replace(" ", "_")
    return stem+(f"_r{item['repeat']}" if item.get("repeat") else "")


def label_lines(item):
    pairs = item["multi_boxes"] if item.get("multi_boxes") else [(item["class_id"], b) for b in item["boxes"]]
    return [f"{cls} {b[0]:.6f} {b[1]:.6f} {b[2]:.6f} {b[3]:.6f}" for cls, b in pairs]


class ImageWriter:
    def __init__(self, max_side: int):
        from PIL import Image, ImageOps
        self.Image, self.ImageOps, self.max_side = Image, ImageOps, max_side
        self.archives, self.inner = {}, {}

    def _zip(self, path):
        if path not in self.archives:
            self.archives[path] = zipfile.ZipFile(path)
        return self.archives[path]

    def _bytes(self, spec):
        if spec["kind"] == "file":
            return Path(spec["path"]).read_bytes()
        if spec["kind"] == "zip":
            return self._zip(spec["archive"]).read(spec["member"])
        key = (spec["archive"], spec["inner"])
        if key not in self.inner:
            self.inner = {key: zipfile.ZipFile(io.BytesIO(self._zip(spec["archive"]).read(spec["inner"])))}
        return self.inner[key].read(spec["member"])

    def write(self, spec, target: Path) -> str:
        """Write a JPEG no larger than max_side; return the SHA-256 of the written file."""
        data = self._bytes(spec)
        image = self.Image.open(io.BytesIO(data))
        orientation = image.getexif().get(0x0112, 1)
        if spec.get("exif") == "require_upright" and orientation not in (0, 1):
            raise ValueError(f"EXIF orientation {orientation}; source boxes may not match the pixels")
        needs_transpose = spec.get("exif") == "transpose" and orientation not in (0, 1)
        if image.format == "JPEG" and max(image.size) <= self.max_side and not needs_transpose:
            target.write_bytes(data)
            return hashlib.sha256(data).hexdigest()
        if needs_transpose:
            image = self.ImageOps.exif_transpose(image)
        image = image.convert("RGB")
        if max(image.size) > self.max_side:
            image.thumbnail((self.max_side, self.max_side), self.Image.Resampling.LANCZOS)
        buffer = io.BytesIO()
        image.save(buffer, "JPEG", quality=92)
        target.write_bytes(buffer.getvalue())
        return hashlib.sha256(buffer.getvalue()).hexdigest()


def render_previews(out: Path, records, taxonomy: Taxonomy, seed: int, per_sheet=24):
    from PIL import Image, ImageDraw
    previews = out/"previews"
    previews.mkdir(exist_ok=True)
    by_source = collections.defaultdict(list)
    for record in records:
        by_source[record["source"]].append(record)
    rng = random.Random(seed)
    for source, rows in sorted(by_source.items()):
        sample = rng.sample(rows, min(per_sheet, len(rows)))
        tiles = []
        for row in sample:
            image = Image.open(out/"images"/row["split"]/row["image"]).convert("RGB")
            image.thumbnail((320, 320))
            draw = ImageDraw.Draw(image)
            for line in (out/"labels"/row["split"]/(Path(row["image"]).stem+".txt")).read_text().splitlines():
                cls, cx, cy, w, h = line.split()
                cx, cy, w, h = (float(cx)*image.width, float(cy)*image.height, float(w)*image.width, float(h)*image.height)
                draw.rectangle([cx-w/2, cy-h/2, cx+w/2, cy+h/2], outline=(255, 220, 0), width=3)
                draw.text((cx-w/2+4, cy-h/2+4), taxonomy.classes[int(cls)]["code"], fill=(255, 255, 0))
            tiles.append(image)
        columns = 6
        sheet = Image.new("RGB", (columns*320, ((len(tiles)+columns-1)//columns)*320), (30, 30, 30))
        for index, tile in enumerate(tiles):
            sheet.paste(tile, ((index % columns)*320, (index//columns)*320))
        sheet.save(previews/f"{source}.jpg", quality=85)


LAB_SOURCES = {"PlantVillage", "OLID", "CucumberZenodo"}
LAB_VAL_SELECT_PER_CLASS = 15  # ~ the number of field val images per class (349 / 29)


def write_lists(out: Path, records, taxonomy: Taxonomy, seed: int):
    """Domain lists and YAMLs. Training selects the best epoch on val_select: all field val + a small lab sample per class,
    so the dominant lab domain does not decide which checkpoint ships to phones."""
    for split in ("val", "test"):
        for domain in ("field", "lab"):
            rows = [f"./images/{split}/{r['image']}" for r in records
                    if r["split"] == split and (r["source"] in LAB_SOURCES) == (domain == "lab")]
            (out/f"{split}_{domain}.txt").write_text("\n".join(rows)+"\n", encoding="utf-8")
    selected, per_class = [], collections.Counter()
    for record in sorted(records, key=lambda r: unit_hash(seed, r["image"])):
        if record["split"] != "val":
            continue
        if record["source"] not in LAB_SOURCES:
            selected.append(record)
        elif per_class[record["classes"][0]] < LAB_VAL_SELECT_PER_CLASS:
            per_class[record["classes"][0]] += 1
            selected.append(record)
    rows = [f"./images/val/{r['image']}" for r in sorted(selected, key=lambda r: r["image"])]
    (out/"val_select.txt").write_text("\n".join(rows)+"\n", encoding="utf-8")
    names = "".join(f'  {c["id"]}: "{c["code"]}"\n' for c in taxonomy.classes)
    header = "# path is filled in at runtime by train scripts; val_select = field val + <=15 lab val per class\n"
    (out/"data.yaml").write_text(header+"train: images/train\nval: val_select.txt\ntest: images/test\nnames:\n"+names, encoding="utf-8")
    for domain in ("field", "lab"):
        (out/f"data_{domain}.yaml").write_text(f"train: images/train\nval: val_{domain}.txt\ntest: test_{domain}.txt\nnames:\n"+names,
                                               encoding="utf-8")
    return len(selected)


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=ROOT/"ml-pipeline/dataset_allcrops_v1")
    parser.add_argument("--seed", type=int, default=20261020)
    parser.add_argument("--plantdoc-repeat", type=int, default=3, help="Copies of each PlantDoc train image (field-domain weight)")
    parser.add_argument("--pv-cap-train", type=int, default=700)
    parser.add_argument("--pv-cap-eval", type=int, default=100)
    parser.add_argument("--min-train-images", type=int, default=150, help="Repeat rare-class train images up to this count (max 6 copies)")
    parser.add_argument("--max-side", type=int, default=1024)
    parser.add_argument("--limit-per-class", type=int, help="Smoke builds only")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--no-previews", action="store_true")
    parser.add_argument("--lists-only", action="store_true", help="Rewrite val/test lists and YAMLs from manifest.jsonl")
    args = parser.parse_args(argv)

    taxonomy = Taxonomy()
    if args.lists_only:
        records = list(read_jsonl(args.out/"manifest.jsonl"))
        print(f"val_select: {write_lists(args.out, records, taxonomy, args.seed)} images")
        return
    if args.out.exists() and any(args.out.iterdir()):
        if not args.overwrite:
            parser.error(f"{args.out} is not empty; pass --overwrite to rebuild it")
        shutil.rmtree(args.out)
    for split in SPLITS:
        (args.out/"images"/split).mkdir(parents=True, exist_ok=True)
        (args.out/"labels"/split).mkdir(parents=True, exist_ok=True)

    items, exclusions = collect(taxonomy, args.seed, args.plantdoc_repeat, args.pv_cap_train, args.pv_cap_eval,
                                args.limit_per_class, 0 if args.limit_per_class else args.min_train_images)
    writer = ImageWriter(args.max_side)
    records, names = [], set()
    for index, item in enumerate(sorted(items, key=lambda item: (item["source"], item["split"], output_name(item)))):
        name = output_name(item)
        if name in names:
            raise ValueError(f"Duplicate output name {name}")
        names.add(name)
        image_path = args.out/"images"/item["split"]/f"{name}.jpg"
        try:
            image_sha = writer.write(item["image"], image_path)
        except Exception as error:
            exclusions[f"{item['source']}:image_error:{type(error).__name__}"] += 1
            print(f"skip {name}: {error}", flush=True)
            continue
        lines = label_lines(item)
        label_text = "\n".join(lines)+"\n"
        (args.out/"labels"/item["split"]/f"{name}.txt").write_text(label_text, encoding="utf-8")
        records.append(dict(image=f"{name}.jpg", split=item["split"], source=item["source"], source_id=item["source_id"],
                            source_class=item["source_class"], classes=sorted({int(l.split()[0]) for l in lines}),
                            boxes=len(lines), geometry=item["geometry"], component_id=item["component_id"],
                            overlap_decision=item["overlap_decision"], repeat=item.get("repeat", 0),
                            image_sha256=image_sha, label_sha256=hashlib.sha256(label_text.encode()).hexdigest()))
        if index % 2000 == 0:
            print(f"{index}/{len(items)} written", flush=True)

    write_lists(args.out, records, taxonomy, args.seed)

    with (args.out/"manifest.jsonl").open("w", encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False)+"\n")
    fingerprint = hashlib.sha256("".join(f"{r['split']}/{r['image']}\t{r['image_sha256']}\t{r['label_sha256']}\n"
                                         for r in sorted(records, key=lambda r: (r["split"], r["image"]))).encode()).hexdigest()
    boxes = collections.Counter(); images = collections.Counter(); sources = collections.Counter(); geometry = collections.Counter()
    for record in records:
        sources[(record["source"], record["split"])] += 1
        geometry[(record["source"], record["geometry"])] += 1
        for line in (args.out/"labels"/record["split"]/(Path(record["image"]).stem+".txt")).read_text().splitlines():
            boxes[(int(line.split()[0]), record["split"])] += 1
        for cls in record["classes"]:
            images[(cls, record["split"])] += 1
    per_class = [dict(id=c["id"], code=c["code"], ru=c["ru"],
                      **{f"{split}_images": images[(c["id"], split)] for split in SPLITS},
                      **{f"{split}_boxes": boxes[(c["id"], split)] for split in SPLITS}) for c in taxonomy.classes]
    report = dict(dataset="allcrops_v1", fingerprint=fingerprint, seed=args.seed, taxonomy_sha256=hashlib.sha256(TAXONOMY.read_bytes()).hexdigest(),
                  annotation_target=taxonomy.data["annotation_target"], parameters=vars(args) | {"out": str(args.out)},
                  splits={split: dict(images=sum(1 for r in records if r["split"] == split)) for split in SPLITS},
                  sources={f"{s}/{split}": n for (s, split), n in sorted(sources.items())},
                  geometry={f"{s}/{g}": n for (s, g), n in sorted(geometry.items())},
                  exclusions=dict(sorted(exclusions.items())), per_class=per_class,
                  classes_without_val=[c["code"] for c in per_class if c["val_images"] == 0],
                  classes_without_test=[c["code"] for c in per_class if c["test_images"] == 0],
                  limitations=["PlantVillage/OLID/Zenodo are lab-style single-leaf photos; field quality is measured on PlantDoc val/test only.",
                               "PlantDoc test still contains a few historical near-duplicates of train (see HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md).",
                               "Zenodo bacterial_wilt/downy_mildew (and some anthracnose) boxes are automatic lesion unions, flagged for review.",
                               "No background/non-leaf negatives yet."])
    (args.out/"report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.out/"classes.json").write_text(json.dumps([c["code"] for c in taxonomy.classes], ensure_ascii=False, indent=2), encoding="utf-8")
    shutil.copyfile(TAXONOMY, args.out/"taxonomy_v1.json")
    if not args.no_previews:
        render_previews(args.out, records, taxonomy, args.seed)
    print(json.dumps(dict(out=str(args.out), fingerprint=fingerprint, splits=report["splits"], exclusions=report["exclusions"],
                          classes_without_val=report["classes_without_val"]), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
