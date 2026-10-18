"""Download the shortlisted external datasets into ml-pipeline/.cache/external/raw/<id>/.

Already downloaded sources are skipped (a `.done` marker is written after unzipping).

Example:
    python -X utf8 ml-pipeline/external/download_sources.py            # all
    python -X utf8 ml-pipeline/external/download_sources.py esca pp2021
"""

import json
import sys
import time
from pathlib import Path

from kaggle.api.kaggle_api_extended import KaggleApi

ROOT = Path(__file__).resolve().parents[2]
SOURCES = Path(__file__).with_name("sources_v2.json")
RAW = ROOT / "ml-pipeline" / ".cache" / "external" / "raw"


def load_sources():
    return json.loads(SOURCES.read_text(encoding="utf-8"))["sources"]


def download_kaggle(api, source, target):
    for attempt in range(4):
        try:
            api.dataset_download_files(source["ref"], path=str(target), unzip=True, quiet=True)
            return
        except Exception as error:
            print(f"  attempt {attempt + 1} failed: {error}")
            time.sleep(60 * (attempt + 1))
    raise RuntimeError(f"could not download {source['ref']}")


def main():
    wanted = set(sys.argv[1:])
    api = KaggleApi()
    api.authenticate()
    for source in load_sources():
        if wanted and source["id"] not in wanted:
            continue
        target = RAW / source["id"]
        marker = target / ".done"
        if marker.exists():
            print(f"{source['id']}: already downloaded")
            continue
        target.mkdir(parents=True, exist_ok=True)
        started = time.time()
        print(f"{source['id']}: downloading {source['ref']}", flush=True)
        try:
            download_kaggle(api, source, target)
        except RuntimeError as error:
            print(f"{source['id']}: FAILED {error}", flush=True)
            continue
        files = sum(1 for path in target.rglob("*") if path.is_file())
        marker.write_text(json.dumps({"ref": source["ref"], "files": files}), encoding="utf-8")
        print(f"{source['id']}: {files} files in {time.time() - started:.0f} s", flush=True)


if __name__ == "__main__":
    main()
