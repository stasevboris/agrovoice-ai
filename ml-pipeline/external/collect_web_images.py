"""Collect ordinary web photos for every taxonomy_v1 class (Boris, 21.10.2026).

The label of each image is the search phrase plus the page that published it.
It is a weak, source-derived label, not an expert diagnosis; reviews only exclude
images. Files stay in an ignored folder and are not redistributed.

Steps (each one resumes where it stopped):
    python -X utf8 ml-pipeline/external/collect_web_images.py search
    python -X utf8 ml-pipeline/external/collect_web_images.py download
    python -X utf8 ml-pipeline/external/collect_web_images.py dedup
    python -X utf8 ml-pipeline/external/collect_web_images.py sheets
"""

import argparse
import hashlib
import html
import io
import json
import re
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
TAXONOMY = ROOT / "ml-pipeline" / "allcrops" / "taxonomy_v1.json"
QUERIES = Path(__file__).with_name("web_queries_v1.json")
DEFAULT_OUT = ROOT / "ml-pipeline" / "dataset_web_search_v1"
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/130.0 Safari/537.36")
MAX_BYTES = 15 * 1024 * 1024
MIN_SIDE = 300
NEAR_DUPLICATE_BITS = 6


def read_jsonl(path):
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def append_jsonl(path, rows):
    with path.open("a", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def class_queries():
    """Search phrases for each class code, in a stable order."""
    taxonomy = json.loads(TAXONOMY.read_text(encoding="utf-8"))
    config = json.loads(QUERIES.read_text(encoding="utf-8"))
    result = {}
    for item in taxonomy["classes"]:
        crop_en, crop_ru, _ = config["crops"][item["crop"]]
        phrases = config["conditions"][item["condition"]]
        queries = []
        for phrase in phrases.get("en", []):
            queries.append(f"{crop_en} {phrase}")
        for phrase in phrases.get("ru", []):
            first_crop_word = crop_ru.split()[0][:4]
            queries.append(phrase if first_crop_word in phrase else f"{crop_ru} {phrase}")
        for phrase in phrases.get("latin", []):
            queries.append(phrase if crop_en.split()[-1] in phrase else f"{phrase} {crop_en}")
        result[item["code"]] = queries
    return result


def fetch(url, timeout=30):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Language": "en,ru;q=0.8"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read(MAX_BYTES + 1), response.headers.get("Content-Type", "")


def search_page(query, first):
    """One page of image search results: image URL, page URL and title."""
    params = {"q": query, "first": first, "count": 35, "mmasync": 1}
    body, _ = fetch("https://www.bing.com/images/async?" + urllib.parse.urlencode(params))
    text = body.decode("utf-8", "replace")
    results = []
    for raw in re.findall(r'm="(\{[^"]+\})"', text):
        try:
            meta = json.loads(html.unescape(raw))
        except json.JSONDecodeError:
            continue
        if meta.get("murl"):
            results.append({"image_url": meta["murl"], "page_url": meta.get("purl", ""), "title": meta.get("t", "")})
    return results


def command_search(out, pages, delay):
    path = out / "candidates.jsonl"
    done = {(row["class"], row["query"], row["first"]) for row in read_jsonl(out / "searched.jsonl")}
    for code, queries in class_queries().items():
        for query in queries:
            for page in range(pages):
                first = page * 35
                if (code, query, first) in done:
                    continue
                try:
                    results = search_page(query, first)
                except Exception as error:
                    print(f"{code} | {query} | {first}: {error}", flush=True)
                    time.sleep(delay * 4)
                    continue
                rows = [{"class": code, "query": query, "rank": first + index, **item}
                        for index, item in enumerate(results)]
                append_jsonl(path, rows)
                append_jsonl(out / "searched.jsonl", [{"class": code, "query": query, "first": first, "results": len(rows)}])
                print(f"{code} | {query} | {first}: {len(rows)}", flush=True)
                time.sleep(delay)


def download_one(url, images):
    try:
        body, content_type = fetch(url)
        if len(body) > MAX_BYTES:
            return {"image_url": url, "status": "too_large"}
        with Image.open(io.BytesIO(body)) as image:
            image.load()
            width, height = image.size
            image_format = image.format
        if min(width, height) < MIN_SIDE:
            return {"image_url": url, "status": "too_small", "width": width, "height": height}
        sha = hashlib.sha256(body).hexdigest()
        suffix = {"JPEG": ".jpg", "PNG": ".png", "WEBP": ".webp"}.get(image_format, "")
        if not suffix:
            return {"image_url": url, "status": f"format_{image_format}"}
        target = images / f"{sha}{suffix}"
        if not target.exists():
            target.write_bytes(body)
        return {"image_url": url, "status": "ok", "sha256": sha, "file": target.name,
                "width": width, "height": height, "bytes": len(body), "content_type": content_type}
    except Exception as error:
        return {"image_url": url, "status": "error", "error": str(error)[:200]}


def command_download(out, workers):
    images = out / "images"
    images.mkdir(parents=True, exist_ok=True)
    log = out / "downloads.jsonl"
    done = {row["image_url"] for row in read_jsonl(log)}
    urls = sorted({row["image_url"] for row in read_jsonl(out / "candidates.jsonl")} - done)
    print(f"to download: {len(urls)}", flush=True)
    batch = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for index, row in enumerate(pool.map(lambda url: download_one(url, images), urls), 1):
            batch.append(row)
            if len(batch) == 100:
                append_jsonl(log, batch)
                batch = []
                print(f"{index}/{len(urls)}", flush=True)
    append_jsonl(log, batch)


def phash(path):
    import imagehash
    with Image.open(path) as image:
        image = image.convert("RGB")
        image.thumbnail((256, 256))
        bits = imagehash.phash(image).hash.flatten()
    value = 0
    for bit in bits:
        value = (value << 1) | int(bit)
    return value


def hamming(value, hashes):
    diff = np.bitwise_xor(hashes, np.uint64(value))
    return np.unpackbits(diff.view(np.uint8).reshape(-1, 8), axis=1).sum(axis=1)


def command_dedup(out, index_names):
    """One record per unique photo: its classes, queries, pages and duplicate status."""
    from hash_index import INDEX_DIR
    known = [np.load(INDEX_DIR / f"{name}.npy") for name in index_names]
    known = np.concatenate(known) if known else np.zeros(0, dtype=np.uint64)
    downloads = {row["image_url"]: row for row in read_jsonl(out / "downloads.jsonl") if row["status"] == "ok"}
    photos = defaultdict(lambda: {"classes": set(), "sources": []})
    for row in read_jsonl(out / "candidates.jsonl"):
        file_row = downloads.get(row["image_url"])
        if not file_row:
            continue
        photo = photos[file_row["sha256"]]
        photo.update({key: file_row[key] for key in ("file", "width", "height")})
        photo["classes"].add(row["class"])
        photo["sources"].append({"class": row["class"], "query": row["query"], "rank": row["rank"],
                                 "image_url": row["image_url"], "page_url": row["page_url"], "title": row["title"]})
    kept_hashes = []
    records = []
    for sha in sorted(photos):
        photo = photos[sha]
        try:
            value = phash(out / "images" / photo["file"])
        except Exception:
            continue
        classes = sorted(photo["classes"])
        status = "candidate"
        if len(classes) > 1:
            status = "conflicting_classes"
        elif len(known) and hamming(value, known).min() <= NEAR_DUPLICATE_BITS:
            status = "duplicate_of_existing_dataset"
        elif kept_hashes and hamming(value, np.array(kept_hashes, dtype=np.uint64)).min() <= NEAR_DUPLICATE_BITS:
            status = "near_duplicate_web"
        if status == "candidate":
            kept_hashes.append(value)
        records.append({"sha256": sha, "file": photo["file"], "width": photo["width"], "height": photo["height"],
                        "phash": f"{value:016x}", "classes": classes, "status": status,
                        "sources": photo["sources"]})
    with (out / "photos.jsonl").open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    counts = defaultdict(lambda: defaultdict(int))
    for record in records:
        counts[record["classes"][0] if len(record["classes"]) == 1 else "*conflict"][record["status"]] += 1
    (out / "dedup_report.json").write_text(json.dumps(counts, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(counts, ensure_ascii=False, indent=1))


def command_sheets(out, per_sheet=48, status="candidate"):
    """Numbered contact sheets per class for the assistant's exclusion review."""
    sheets = out / "sheets"
    sheets.mkdir(exist_ok=True)
    by_class = defaultdict(list)
    for record in read_jsonl(out / "photos.jsonl"):
        if record["status"] == status:
            by_class[record["classes"][0]].append(record)
    cell = 200
    columns = 8
    for code, records in sorted(by_class.items()):
        for start in range(0, len(records), per_sheet):
            chunk = records[start:start + per_sheet]
            rows = (len(chunk) + columns - 1) // columns
            sheet = Image.new("RGB", (columns * cell, rows * (cell + 16)), "white")
            draw = ImageDraw.Draw(sheet)
            for index, record in enumerate(chunk):
                with Image.open(out / "images" / record["file"]) as image:
                    image = image.convert("RGB")
                    image.thumbnail((cell - 4, cell - 4))
                    x = (index % columns) * cell
                    y = (index // columns) * (cell + 16)
                    sheet.paste(image, (x + 2, y + 2))
                    draw.text((x + 4, y + cell), f"{start + index}", fill="red")
            sheet.save(sheets / f"{code}_{start // per_sheet:02d}.jpg", quality=80)
        listing = [{"index": index, "sha256": record["sha256"]} for index, record in enumerate(records)]
        (sheets / f"{code}.json").write_text(json.dumps(listing), encoding="utf-8")
    print(f"sheets for {len(by_class)} classes in {sheets}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["search", "download", "dedup", "sheets"])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--pages", type=int, default=3)
    parser.add_argument("--delay", type=float, default=1.5)
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--index", nargs="*", default=["current_sources", "ext_v2_sources"])
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.command == "search":
        command_search(args.out, args.pages, args.delay)
    elif args.command == "download":
        command_download(args.out, args.workers)
    elif args.command == "dedup":
        command_dedup(args.out, args.index)
    else:
        command_sheets(args.out)


if __name__ == "__main__":
    main()
