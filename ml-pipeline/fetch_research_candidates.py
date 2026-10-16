"""Fetch selected public research candidates; never modify training datasets.

Uses publisher metadata and verifies published MD5 before accepting each archive.
Run with the existing Windows Python environment. No weights/models are loaded.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "ml-pipeline/source_candidates/research_2026-10-03"
REPORT = ROOT / "docs/research/dataset_downloads_2026-10-03.json"
SOURCES = [
    ("wur_spider", "https://api.figshare.com/v2/articles/12712289", {"data.zip"}),
    ("olid", "https://zenodo.org/api/records/8105154", {
        "tomato__part_1.zip", "tomato__part_2.zip", "cucumber.zip"}),
]


def digest(path: Path) -> str:
    result = hashlib.md5()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def save(report: dict) -> None:
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    temp = REPORT.with_suffix(".tmp")
    temp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(REPORT)


def main() -> None:
    DEST.mkdir(parents=True, exist_ok=True)
    report = {"checked_utc": datetime.now(timezone.utc).isoformat(),
              "purpose": "Candidates only; excluded from dataset_clean_v2 and study10_v2",
              "files": []}
    for source, api, selected in SOURCES:
        try:
            with urllib.request.urlopen(api, timeout=60) as stream:
                metadata = json.load(stream)
        except Exception as error:
            report["files"].append({"source": source, "api": api, "status": "metadata_failed", "error": str(error)})
            save(report)
            continue
        folder = DEST / source
        folder.mkdir(exist_ok=True)
        (folder / "publisher_metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        licence = metadata.get("metadata", metadata).get("license")
        for info in metadata.get("files", []):
            name = info.get("key", info.get("name"))
            if name not in selected:
                continue
            target = folder / name
            part = target.with_suffix(target.suffix + ".part")
            expected = info.get("checksum", info.get("computed_md5", "")).removeprefix("md5:")
            url = info.get("links", {}).get("self") or info.get("download_url")
            entry = {"source": source, "metadata_api": api, "title": metadata.get("metadata", metadata).get("title"),
                     "license": licence, "file": name, "bytes": info["size"], "expected_md5": expected,
                     "url": url, "path": str(target.relative_to(ROOT)), "status": "downloading"}
            report["files"].append(entry)
            save(report)
            try:
                if target.exists() and target.stat().st_size == info["size"] and digest(target) == expected:
                    entry["status"] = "verified_existing"
                    print(f"Verified existing: {source}/{name}", flush=True)
                    save(report)
                    continue
                if shutil.disk_usage(DEST).free < info["size"] + 10 * 1024**3:
                    raise RuntimeError("Insufficient free disk space with 10 GiB reserve")
                offset = part.stat().st_size if part.exists() else 0
                headers = {"User-Agent": "AgroVoice-research/1.0"}
                if offset:
                    headers["Range"] = f"bytes={offset}-"
                request = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(request, timeout=120) as stream:
                    mode = "ab" if offset and stream.status == 206 else "wb"
                    if mode == "wb":
                        offset = 0
                    last_notice = time.monotonic()
                    with part.open(mode) as output:
                        while chunk := stream.read(4 * 1024 * 1024):
                            output.write(chunk)
                            offset += len(chunk)
                            if time.monotonic() - last_notice >= 30:
                                print(f"{source}/{name}: {offset / info['size']:.0%}", flush=True)
                                last_notice = time.monotonic()
                if part.stat().st_size != info["size"]:
                    raise ValueError("Size differs from publisher metadata")
                actual = digest(part)
                entry["actual_md5"] = actual
                if actual != expected:
                    raise ValueError("MD5 differs from publisher metadata; archive not accepted")
                part.replace(target)
                entry["status"] = "downloaded_verified"
                entry["completed_utc"] = datetime.now(timezone.utc).isoformat()
                print(f"Downloaded and verified: {source}/{name}", flush=True)
            except Exception as error:
                entry["status"] = "failed"
                entry["error"] = str(error)
                print(f"Failed: {source}/{name}: {error}", flush=True)
            save(report)


if __name__ == "__main__":
    main()
