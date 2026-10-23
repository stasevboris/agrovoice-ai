"""Assemble the self-contained training kit for a GPU (RTX 5090) and zip it.

Kit = gpu_kit scripts + datasets/allcrops_web_v1 (checked allcrops_v1 + screened web photos)
+ tiny smoke set + COCO-pretrained weights + plot font.
  python -X utf8 ml-pipeline/pack_gpu_kit.py
Output: artifacts/gpu_kit/agrovoice_gpu_kit_<date>.zip and kit_manifest.json (SHA of every file).
Build the datasets first: build_allcrops_v1.py (full), prepare_web_search.py build (web),
build_mix_dataset.py (mix), build_allcrops_v1.py --limit-per-class 2 --out ml-pipeline/dataset_allcrops_v1_smoke
"""
from __future__ import annotations

import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ["README.md", "check_env.py", "train_queue.py", "package_results.py", "queue.json",
           "setup_linux.sh", "setup_windows.ps1", "requirements-gpu.txt"]
WEIGHTS = ["yolo26n.pt", "yolo26s.pt", "yolo26m.pt", "yolo11n.pt"]


def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", type=Path, default=ROOT/"ml-pipeline/dataset_allcrops_web_v1")
    parser.add_argument("--smoke-dataset", type=Path, default=ROOT/"ml-pipeline/dataset_allcrops_v1_smoke")
    parser.add_argument("--out", type=Path, default=ROOT/"artifacts/gpu_kit")
    args = parser.parse_args()
    for path in (args.dataset/"report.json", args.smoke_dataset/"report.json"):
        if not path.exists():
            parser.error(f"Missing {path}; build the dataset first")
    kit_name = f"agrovoice_gpu_kit_{date.today():%Y%m%d}"
    archive_path = args.out/f"{kit_name}.zip"
    args.out.mkdir(parents=True, exist_ok=True)
    entries = []
    for name in SCRIPTS:
        entries.append((ROOT/"ml-pipeline/gpu_kit"/name, name))
    for name in WEIGHTS:
        entries.append((ROOT/"ml-pipeline"/name, f"weights/{name}"))
    font = ROOT/"ml-pipeline/.cache/ultralytics_root/Arial.ttf"
    if font.exists():  # Ultralytics downloads it for plots otherwise
        entries += [(font, "config/Arial.ttf"), (font, "config/Ultralytics/Arial.ttf")]
    # train_queue.py and check_env.py read the dataset from queue.json, so the kit must use exactly that path.
    queue = json.loads((ROOT/"ml-pipeline/gpu_kit/queue.json").read_text(encoding="utf-8"))
    for source, target in ((args.dataset, queue["dataset"]), (args.smoke_dataset, queue["smoke_dataset"])):
        for path in sorted(source.rglob("*")):
            if path.is_file() and path.suffix != ".cache" and "previews" not in path.parts:
                entries.append((path, f"{target}/{path.relative_to(source).as_posix()}"))
    manifest = []
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_STORED, allowZip64=True) as archive:
        for index, (source, target) in enumerate(entries):
            compression = zipfile.ZIP_STORED if source.suffix.lower() in (".jpg", ".pt", ".png") else zipfile.ZIP_DEFLATED
            archive.write(source, f"{kit_name}/{target}", compress_type=compression)
            if not target.startswith("datasets/"):
                manifest.append(dict(path=target, bytes=source.stat().st_size, sha256=sha256(source)))
            if index % 5000 == 0:
                print(f"{index}/{len(entries)}", flush=True)
        report = json.loads((args.dataset/"report.json").read_text(encoding="utf-8"))
        info = dict(kit=kit_name, dataset_fingerprint=report["fingerprint"], dataset_splits=report["splits"],
                    files=len(entries), checked_files=manifest)
        archive.writestr(f"{kit_name}/kit_manifest.json", json.dumps(info, indent=2, ensure_ascii=False))
    (args.out/f"{kit_name}.manifest.json").write_text(json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8")
    shutil.copyfile(ROOT/"ml-pipeline/gpu_kit/README.md", args.out/f"{kit_name}.README.md")  # readable before unzipping
    print(f"{archive_path}: {archive_path.stat().st_size/2**30:.2f} GiB, {len(entries)} files, sha256 {sha256(archive_path)}")


if __name__ == "__main__":
    main()
