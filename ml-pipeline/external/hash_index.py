"""Perceptual-hash index of images, used to drop re-uploads of the current sources.

Example:
    python -X utf8 ml-pipeline/external/hash_index.py --name current_sources \
        ml-pipeline/dataset_allcrops_v1/images ml-pipeline/dataset_clean_v2/images \
        ml-pipeline/source_candidates/yolo26s_data_v1/plantvillage_all_crops/images/color
"""

import argparse
import json
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import imagehash
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
INDEX_DIR = ROOT / "ml-pipeline" / ".cache" / "external" / "hash_index"
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".tif", ".tiff"}


def list_images(folders):
    paths = []
    for folder in folders:
        for path in Path(folder).rglob("*"):
            if path.suffix.lower() in IMAGE_SUFFIXES:
                paths.append(path)
    return sorted(paths)


def image_hash(path):
    """Return the 64-bit pHash as an unsigned integer, or None for unreadable files."""
    try:
        with Image.open(path) as image:
            image = image.convert("RGB")
            image.thumbnail((256, 256))
            bits = imagehash.phash(image).hash.flatten()
    except Exception:
        return None
    value = 0
    for bit in bits:
        value = (value << 1) | int(bit)
    return value


def hash_many(paths, workers=12):
    with ProcessPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(image_hash, paths, chunksize=64))


def save_index(name, paths, hashes):
    INDEX_DIR.mkdir(parents=True, exist_ok=True)
    kept_paths = []
    kept_hashes = []
    for path, value in zip(paths, hashes):
        if value is not None:
            kept_paths.append(str(path))
            kept_hashes.append(value)
    np.save(INDEX_DIR / f"{name}.npy", np.array(kept_hashes, dtype=np.uint64))
    (INDEX_DIR / f"{name}.paths.json").write_text(json.dumps(kept_paths), encoding="utf-8")
    return len(kept_hashes)


def load_index(name):
    hashes = np.load(INDEX_DIR / f"{name}.npy")
    paths = json.loads((INDEX_DIR / f"{name}.paths.json").read_text(encoding="utf-8"))
    return hashes, paths


def hamming_to_index(value, index_hashes):
    """Hamming distances from one hash to every hash of the index."""
    diff = np.bitwise_xor(index_hashes, np.uint64(value))
    as_bytes = diff.view(np.uint8).reshape(-1, 8)
    return np.unpackbits(as_bytes, axis=1).sum(axis=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folders", nargs="+", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--workers", type=int, default=12)
    args = parser.parse_args()

    paths = list_images(args.folders)
    print(f"images: {len(paths)}")
    hashes = hash_many(paths, args.workers)
    count = save_index(args.name, paths, hashes)
    print(f"indexed: {count} -> {INDEX_DIR / args.name}.npy")


if __name__ == "__main__":
    main()
