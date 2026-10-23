"""Pack results after the training process has stopped (checkpoints may be changing during training).

  python package_results.py            -> results_<host>_<YYYYmmdd_HHMM>.zip in the kit root
Includes per run: best.pt, last.pt, last_resumable.pt, best.onnx, results.csv, args.yaml, plots, eval/, run_summary.json,
android_manifest_entry.json; plus env.json, queue*.json and logs/. Skips epoch*.pt snapshots.
"""
from __future__ import annotations

from datetime import datetime
from pathlib import Path
import platform
import zipfile

KIT = Path(__file__).resolve().parent
KEEP_SUFFIXES = {".csv", ".yaml", ".json", ".png", ".jpg", ".onnx", ".txt", ".log"}


def wanted(path: Path) -> bool:
    if path.name.startswith("epoch") and path.suffix == ".pt":
        return False
    if path.suffix == ".pt":
        return path.name in ("best.pt", "last.pt", "last_resumable.pt")
    return path.suffix in KEEP_SUFFIXES


def main():
    target = KIT/f"results_{platform.node() or 'gpu'}_{datetime.now():%Y%m%d_%H%M}.zip"
    files = [p for p in (KIT/"runs").rglob("*") if p.is_file() and wanted(p)]
    files += [p for p in (KIT/"logs").rglob("*") if p.is_file()] if (KIT/"logs").exists() else []
    files += [KIT/"env.json"] if (KIT/"env.json").exists() else []
    files += sorted(KIT.glob("queue*.json"))
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
        for path in files:
            compress = zipfile.ZIP_STORED if path.suffix in (".png", ".jpg") else zipfile.ZIP_DEFLATED
            archive.write(path, path.relative_to(KIT).as_posix(), compress_type=compress)
    print(f"{target} ({target.stat().st_size/2**20:.1f} MiB, {len(files)} files)")


if __name__ == "__main__":
    main()
