"""Print description, licence and file summary of Kaggle datasets (for the manual shortlist).

Example:
    python -X utf8 ml-pipeline/external/describe_kaggle.py owner/dataset owner2/dataset2
"""

import collections
import json
import sys
import time
from pathlib import Path

from kaggle.api.kaggle_api_extended import KaggleApi

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "ml-pipeline" / ".cache" / "external" / "search" / "kaggle_details.json"
TEMP = OUT.parent / "metadata_tmp"


def top_folders(files):
    counter = collections.Counter()
    for item in files:
        parts = item.name.replace("\\", "/").split("/")
        counter["/".join(parts[:-1]) or "."] += 1
    return counter.most_common(25)


def describe(api, ref):
    metadata_file = Path(api.dataset_metadata(ref, str(TEMP)))
    info = json.loads(metadata_file.read_text(encoding="utf-8")).get("info", {})
    metadata_file.unlink()
    page = api.dataset_list_files(ref, page_size=200)
    files = page.files or []
    token = page.next_page_token
    return {
        "ref": ref,
        "title": info.get("title"),
        "licenses": info.get("licenses"),
        "description": (info.get("description") or "")[:1500],
        "file_count_seen": len(files),
        "file_count_capped": bool(token),
        "folders": top_folders(files),
    }


def main():
    api = KaggleApi()
    api.authenticate()
    TEMP.mkdir(parents=True, exist_ok=True)
    details = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else {}
    for ref in sys.argv[1:]:
        for attempt in range(4):
            try:
                details[ref] = describe(api, ref)
                break
            except Exception as error:
                details[ref] = {"ref": ref, "error": str(error)}
                if "429" not in str(error):
                    break
                time.sleep(30 * (attempt + 1))
        time.sleep(2)
        entry = details[ref]
        print("=" * 100)
        print(ref, "|", entry.get("licenses"), "|", entry.get("file_count_seen"), "files")
        print((entry.get("description") or entry.get("error") or "")[:900].replace("\n", " "))
        print(entry.get("folders"))
    OUT.write_text(json.dumps(details, ensure_ascii=False, indent=1, default=str), encoding="utf-8")


if __name__ == "__main__":
    main()
