"""Turn collected web photos (collect_web_images.py) into a YOLO addition for taxonomy_v1.

    python -X utf8 ml-pipeline/external/prepare_web_search.py screen   # CLIP: photo of leaves or not
    python -X utf8 ml-pipeline/external/prepare_web_search.py boxes    # leaf detector, as for ext_v2
    python -X utf8 ml-pipeline/external/prepare_web_search.py build    # images/labels, split by web site

The class is the one of the search phrase. CLIP and the detector only remove
pictures that are not usable photos of leaves; they never change a class.
Split: whole web sites go either to train or to val, so one site does not leak.
There is no test split: web labels are weak and a test needs checked labels.
"""

import argparse
import hashlib
import json
import random
import shutil
import sys
import urllib.parse
from collections import Counter, defaultdict
from pathlib import Path

from PIL import Image, ImageOps

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_external_v2 import DETECTOR, DETECTOR_CONF, DETECTOR_IMGSZ, OUTPUT_LONG_SIDE, leaf_boxes, reframe  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
TAXONOMY = ROOT / "ml-pipeline" / "allcrops" / "taxonomy_v1.json"
DEFAULT_OUT = ROOT / "ml-pipeline" / "dataset_web_search_v1"
VAL_SHARE = 0.15
SEED = 20261021

KEEP_PROMPTS = [
    "a close-up photo of a plant leaf with spots or disease symptoms",
    "a close-up photo of green plant leaves",
    "a photo of crop plants with leaves in a field or greenhouse",
]
REJECT_PROMPTS = [
    "a diagram, drawing or illustration",
    "a page of text, a table, a chart or an infographic",
    "a microscope image of cells, spores or bacteria",
    "a photo of fruits or vegetables",
    "a photo of an insect close up",
    "a photo of people",
    "a product package, a bottle or a sprayer",
    "a collage or grid of several photos",
    "a photo of a tree trunk, branches or roots without leaves",
    "a photo of potato tubers or seeds",
]
CROP_NAMES = {
    "apple": "apple tree", "blueberry": "blueberry bush", "cherry": "cherry tree", "corn": "corn (maize)",
    "cucumber": "cucumber", "grape": "grapevine", "orange": "citrus tree", "peach": "peach tree",
    "pepper": "bell pepper", "potato": "potato", "raspberry": "raspberry", "soybean": "soybean",
    "squash": "squash or pumpkin", "strawberry": "strawberry", "tomato": "tomato",
}
KEEP_SHARE = 0.5     # summed probability of the "leaf photo" prompts
CROP_MAX_RANK = 4    # labelled crop must be among the four most likely crop prompts

# Стоковые превью, клипарт, соцсети и обложки видео: водяные знаки, коллажи и чужие права.
# Такие страницы не дают пригодного полевого кадра, поэтому не доходят до разметки.
BLOCKED_DOMAINS = (
    "dreamstime", "alamy", "freepik", "vecteezy", "shutterstock", "istockphoto", "gettyimages",
    "depositphotos", "123rf", "adobe", "colourbox", "bigstock", "canstockphoto", "pond5",
    "pinterest", "youtube", "ytimg", "facebook", "instagram", "tiktok", "twitter", "x.com",
    "gstatic", "bing.com", "google.", "duckduckgo", "amazon.", "ebay.", "etsy.", "aliexpress",
    "researchgate", "sciencedirect", "springer", "wiley", "tandfonline", "jstor", "mdpi.",
    "frontiersin", "nature.com", "cell.com", "biomedcentral", "academia.edu", "scribd",
    "slideshare", "coursehero", "studocu", "lookaside", "fbcdn", "cdninstagram", "pinimg",
    "cartoondealer", "inspiredpencil", "ozon.ru", "wildberries", "clipart",
)


def read_jsonl(path):
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def write_jsonl(path, rows):
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def class_crops():
    taxonomy = json.loads(TAXONOMY.read_text(encoding="utf-8"))
    return {item["code"]: item["crop"] for item in taxonomy["classes"]}


def candidates(out):
    return [row for row in read_jsonl(out / "photos.jsonl") if row["status"] == "candidate"]


def blocked(row):
    hosts = {urllib.parse.urlparse(source["page_url"] or source["image_url"]).netloc.lower()
             for source in row["sources"]}
    return any(pattern in host for host in hosts for pattern in BLOCKED_DOMAINS)


def command_screen(out, batch):
    import open_clip
    import torch

    model, _, preprocess = open_clip.create_model_and_transforms("ViT-B-32", pretrained="laion2b_s34b_b79k")
    tokenizer = open_clip.get_tokenizer("ViT-B-32")
    model.eval()
    crops = list(CROP_NAMES)
    kind_prompts = KEEP_PROMPTS + REJECT_PROMPTS
    crop_prompts = [f"a photo of {CROP_NAMES[crop]} leaves" for crop in crops]
    with torch.no_grad():
        kind_text = model.encode_text(tokenizer(kind_prompts))
        crop_text = model.encode_text(tokenizer(crop_prompts))
        kind_text = kind_text / kind_text.norm(dim=-1, keepdim=True)
        crop_text = crop_text / crop_text.norm(dim=-1, keepdim=True)
    crop_of = class_crops()
    screen_path = out / "screen.jsonl"
    results = read_jsonl(screen_path) if screen_path.exists() else []
    done = {row["sha256"] for row in results}
    rows = [row for row in candidates(out) if row["sha256"] not in done]
    print(f"to screen: {len(rows)} new, {len(results)} already screened", flush=True)
    for start in range(0, len(rows), batch):
        chunk = rows[start:start + batch]
        tensors = []
        for row in chunk:
            with Image.open(out / "images" / row["file"]) as image:
                tensors.append(preprocess(ImageOps.exif_transpose(image).convert("RGB")))
        with torch.no_grad():
            features = model.encode_image(torch.stack(tensors))
            features = features / features.norm(dim=-1, keepdim=True)
            kind = (100 * features @ kind_text.T).softmax(dim=-1)
            crop = (100 * features @ crop_text.T).softmax(dim=-1)
        for row, kind_probs, crop_probs in zip(chunk, kind.tolist(), crop.tolist()):
            label_crop = crop_of[row["classes"][0]]
            order = sorted(range(len(crops)), key=lambda index: -crop_probs[index])
            rank = order.index(crops.index(label_crop)) + 1
            keep_share = sum(kind_probs[:len(KEEP_PROMPTS)])
            top_kind = kind_prompts[max(range(len(kind_prompts)), key=lambda index: kind_probs[index])]
            decision = "keep"
            if keep_share < KEEP_SHARE:
                decision = "not_a_leaf_photo"
            elif rank > CROP_MAX_RANK:
                decision = "crop_unlikely"
            results.append({"sha256": row["sha256"], "class": row["classes"][0], "leaf_share": round(keep_share, 4),
                            "top_kind": top_kind, "crop_rank": rank, "top_crop": crops[order[0]],
                            "decision": decision})
        print(f"{len(results)}/{len(done) + len(rows)}", flush=True)
    write_jsonl(out / "screen.jsonl", results)
    counts = Counter(row["decision"] for row in results)
    print(json.dumps(counts, indent=1))


def command_boxes(out, device, batch):
    from ultralytics import YOLO

    kept = {row["sha256"] for row in read_jsonl(out / "screen.jsonl") if row["decision"] == "keep"}
    detections_path = out / "detections.jsonl"
    detections = read_jsonl(detections_path) if detections_path.exists() else []
    done = {row["sha256"] for row in detections}
    rows = [row for row in candidates(out) if row["sha256"] in kept and not blocked(row) and row["sha256"] not in done]
    print(f"to detect: {len(rows)} new, {len(detections)} already detected", flush=True)
    model = YOLO(str(DETECTOR))
    for start in range(0, len(rows), batch):
        chunk = rows[start:start + batch]
        paths = [str(out / "images" / row["file"]) for row in chunk]
        results = model.predict(paths, imgsz=DETECTOR_IMGSZ, conf=DETECTOR_CONF, agnostic_nms=True,
                                device=device, verbose=False)
        for row, result in zip(chunk, results):
            boxes = [[round(value, 5) for value in xywhn] + [round(confidence, 4)]
                     for xywhn, confidence in zip(result.boxes.xywhn.tolist(), result.boxes.conf.tolist())]
            detections.append({"sha256": row["sha256"], "boxes": boxes})
        print(f"{len(detections)}/{len(done) + len(rows)}", flush=True)
    write_jsonl(out / "detections.jsonl", detections)


def site(row):
    hosts = sorted({urllib.parse.urlparse(source["page_url"] or source["image_url"]).netloc.lower()
                    for source in row["sources"]})
    return hosts[0].removeprefix("www.") if hosts else "unknown"


def split_of(host):
    value = int(hashlib.sha256(f"{SEED}:{host}".encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    return "val" if value < VAL_SHARE else "train"


def command_build(out, overwrite):
    taxonomy = json.loads(TAXONOMY.read_text(encoding="utf-8"))
    class_ids = {item["code"]: item["id"] for item in taxonomy["classes"]}
    target = out / "yolo"
    if target.exists():
        if not overwrite:
            raise SystemExit(f"{target} exists; pass --overwrite")
        shutil.rmtree(target)
    photos = {row["sha256"]: row for row in candidates(out)}
    detections = {row["sha256"]: row["boxes"] for row in read_jsonl(out / "detections.jsonl")}
    manifest = []
    decisions = Counter()
    per_class = defaultdict(Counter)
    for sha, boxes in sorted(detections.items()):
        photo = photos.get(sha)
        if photo is None:  # became a near-duplicate after a later collection pass
            decisions["stale_detection"] += 1
            continue
        code = photo["classes"][0]
        record = {"class": code, "closeup": False}
        kept, region, decision = leaf_boxes(record, boxes)
        decisions[decision] += 1
        if not kept:
            continue
        if region:
            kept = reframe(kept, region)
        host = site(photo)
        split = split_of(host)
        for folder in ("images", "labels"):
            (target / folder / split).mkdir(parents=True, exist_ok=True)
        image_path = target / "images" / split / f"{sha}.jpg"
        with Image.open(out / "images" / photo["file"]) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            if region:
                width, height = image.size
                image = image.crop((round(region[0] * width), round(region[1] * height),
                                    round(region[2] * width), round(region[3] * height)))
            image.thumbnail((OUTPUT_LONG_SIDE, OUTPUT_LONG_SIDE))
            image.save(image_path, quality=90)
        lines = [f"{class_ids[code]} " + " ".join(f"{value:.5f}" for value in box[:4]) for box in kept]
        (target / "labels" / split / f"{sha}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        per_class[code][split] += 1
        manifest.append({"sha256": sha, "class": code, "split": split, "site": host, "geometry": decision,
                         "boxes": len(kept), "crop_region": region, "sources": photo["sources"]})
    write_jsonl(target / "manifest.jsonl", manifest)
    names = {item["id"]: item["code"] for item in taxonomy["classes"]}
    data_yaml = ["path: .", "train: images/train", "val: images/val", f"nc: {len(names)}", "names:"]
    data_yaml += [f"  {index}: {names[index]}" for index in sorted(names)]
    (target / "data.yaml").write_text("\n".join(data_yaml) + "\n", encoding="utf-8", newline="\n")
    report = {
        "label_source": "search phrase and publishing page; weak label, not an expert diagnosis",
        "split": "by web site; no test split",
        "geometry": dict(decisions),
        "per_class": {code: dict(per_class.get(code, {})) for code in class_ids},
        "images": len(manifest),
    }
    (target / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n",
                                        encoding="utf-8", newline="\n")
    print(json.dumps(report, ensure_ascii=False, indent=1))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["screen", "boxes", "build"])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--batch", type=int, default=32)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()
    random.seed(SEED)
    if args.command == "screen":
        command_screen(args.out, args.batch)
    elif args.command == "boxes":
        command_boxes(args.out, args.device, args.batch)
    else:
        command_build(args.out, args.overwrite)


if __name__ == "__main__":
    main()
