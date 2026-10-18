"""Pack the ext_v2 addon for the unpacked GPU kit (unzip it over the kit folder).

Addon = datasets/ext_v2 (external images, train split in parts) + yaml-only datasets that combine it
with allcrops_v1 + queue_ext_v2.json (fine-tuning from the v1 runs) + README_ext_v2.md + current train_queue.py.
  python -X utf8 ml-pipeline/pack_ext_addon.py
Output: artifacts/gpu_kit/<kit name>_ext_v2.zip with the same root folder as the kit zip.
Build the dataset first: ml-pipeline/external/build_external_v2.py write
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[1]
KIT_FILES = ["train_queue.py", "package_results.py", "queue_ext_v2.json", "README_ext_v2.md"]
SMOKE_PER_CLASS = 1


def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def prefixed(value, folder):
    """Paths of a dataset yaml rewritten relative to a sibling dataset folder."""
    if isinstance(value, list):
        return [f"../{folder}/{item}" for item in value]
    return f"../{folder}/{value}"


def as_list(value):
    return value if isinstance(value, list) else [value]


def combined_yamls(v1: Path, v1_folder: str, ext_train: list, ext_val: str, ext_test: str, ext_domains: dict):
    """data.yaml = v1 + ext; data_field/lab = v1 only; data_ext_<domain> = ext only. Returns {file name: dict}."""
    base = yaml.safe_load((v1/"data.yaml").read_text(encoding="utf-8"))
    names = base["names"]
    files = {"data.yaml": {
        "train": as_list(prefixed(base["train"], v1_folder)) + ext_train,
        "val": as_list(prefixed(base["val"], v1_folder)) + [ext_val],
        "test": as_list(prefixed(base["test"], v1_folder)) + [ext_test],
        "names": names}}
    for path in sorted(v1.glob("data_*.yaml")):
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        files[path.name] = {key: prefixed(config[key], v1_folder) for key in ("train", "val", "test")} | {"names": names}
    for domain, (val, test) in ext_domains.items():
        files[f"data_ext_{domain}.yaml"] = {"train": ext_train[0], "val": val, "test": test, "names": names}
    return files


def smoke_lists(manifest: list):
    """A few ext images per class for the 2-minute smoke run: train from part01, val per domain."""
    train, field, lab = [], [], []
    taken = {}
    for row in manifest:
        key = (row["class"], row["split"], row["domain"])
        if taken.get(key, 0) >= SMOKE_PER_CLASS:
            continue
        if row.get("part") == "part01":
            train.append(row["image_path"])
        elif row["split"] == "val":
            (field if row["domain"] == "field" else lab).append(row["image_path"])
        else:
            continue
        taken[key] = taken.get(key, 0) + 1
    return {"smoke_train.txt": train, "smoke_val_field.txt": field, "smoke_val_lab.txt": lab}


def dataset_report(name: str, reports: list, extra: dict):
    digest = hashlib.sha256(name.encode())
    for report in reports:
        digest.update(report["fingerprint"].encode())
    return {"dataset": name, "fingerprint": digest.hexdigest(), "combines": [r["dataset"] for r in reports]} | extra


def plan_datasets(v1: Path, smoke: Path, ext: Path):
    """Return {folder: {file name: text}} for allcrops_v2_p1..pN, allcrops_v2_all and allcrops_v2_smoke."""
    v1_report = json.loads((v1/"report.json").read_text(encoding="utf-8"))
    v1_report.setdefault("dataset", "allcrops_v1")
    smoke_report = json.loads((smoke/"report.json").read_text(encoding="utf-8"))
    smoke_report.setdefault("dataset", "allcrops_v1_smoke")
    ext_report = json.loads((ext/"report.json").read_text(encoding="utf-8"))
    taxonomy = (v1/"taxonomy_v1.json").read_text(encoding="utf-8")
    parts = sorted(p for p in ext_report["parts"] if p.startswith("part"))
    domains = {d: (f"../ext_v2/val_{d}.txt", f"../ext_v2/test_{d}.txt") for d in ("field", "lab")
               if (ext/f"val_{d}.txt").read_text(encoding="utf-8").strip()}
    plans = {}
    for count in range(1, len(parts)+1):
        chosen = parts[:count]
        folder = "allcrops_v2_all" if count == len(parts) else f"allcrops_v2_p{count}"
        ext_train = [f"../ext_v2/images/{p}" for p in chosen]
        files = combined_yamls(v1, "allcrops_v1", ext_train, "../ext_v2/val.txt", "../ext_v2/test.txt", domains)
        extra = {"ext_parts": chosen, "ext_train_images": sum(ext_report["parts"][p]["images"] for p in chosen),
                 "v1_splits": v1_report["splits"]}
        plans[folder] = files | {"report.json": dataset_report(folder, [v1_report, ext_report], extra)}
    smoke_domains = {d: (f"../ext_v2/smoke_val_{d}.txt",)*2 for d in domains}
    files = combined_yamls(smoke, "allcrops_v1_smoke", ["../ext_v2/smoke_train.txt"], "../ext_v2/smoke_val_field.txt",
                           "../ext_v2/smoke_val_field.txt", smoke_domains)
    plans["allcrops_v2_smoke"] = files | {"report.json": dataset_report("allcrops_v2_smoke", [smoke_report, ext_report], {})}
    texts = {}
    for folder, files in plans.items():
        texts[folder] = {"taxonomy_v1.json": taxonomy}
        for name, value in files.items():
            if name.endswith(".yaml"):
                texts[folder][name] = "# paths are relative to this folder; train_queue.py fills in 'path' at runtime\n" + \
                    yaml.safe_dump(value, allow_unicode=True, sort_keys=False)
            else:
                texts[folder][name] = json.dumps(value, indent=2, ensure_ascii=False)
    return texts


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ext", type=Path, default=ROOT/"ml-pipeline/dataset_ext_v2")
    parser.add_argument("--v1", type=Path, default=ROOT/"ml-pipeline/dataset_allcrops_v1")
    parser.add_argument("--smoke", type=Path, default=ROOT/"ml-pipeline/dataset_allcrops_v1_smoke")
    parser.add_argument("--kit-name", default="agrovoice_gpu_kit_20261016", help="root folder of the kit zip")
    parser.add_argument("--out", type=Path, default=ROOT/"artifacts/gpu_kit")
    args = parser.parse_args()
    if not (args.ext/"report.json").exists():
        parser.error(f"Missing {args.ext/'report.json'}; run build_external_v2.py write first")
    plans = plan_datasets(args.v1, args.smoke, args.ext)
    manifest = [json.loads(line) for line in (args.ext/"manifest.jsonl").read_text(encoding="utf-8").splitlines()]
    lists = smoke_lists(manifest)
    archive_path = args.out/f"{args.kit_name}_ext_v2.zip"
    args.out.mkdir(parents=True, exist_ok=True)
    root = args.kit_name
    count = 0
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_STORED, allowZip64=True) as archive:
        for name in KIT_FILES:
            archive.write(ROOT/"ml-pipeline/gpu_kit"/name, f"{root}/{name}", compress_type=zipfile.ZIP_DEFLATED)
        for path in sorted(args.ext.rglob("*")):
            if path.is_file() and path.suffix != ".cache" and "previews" not in path.parts:
                compression = zipfile.ZIP_STORED if path.suffix == ".jpg" else zipfile.ZIP_DEFLATED
                archive.write(path, f"{root}/datasets/ext_v2/{path.relative_to(args.ext).as_posix()}", compress_type=compression)
                count += 1
        for name, rows in lists.items():
            archive.writestr(f"{root}/datasets/ext_v2/{name}", "".join(f"./{row}\n" for row in rows),
                             compress_type=zipfile.ZIP_DEFLATED)
        for folder, files in plans.items():
            for name, text in files.items():
                archive.writestr(f"{root}/datasets/{folder}/{name}", text, compress_type=zipfile.ZIP_DEFLATED)
    shutil.copyfile(ROOT/"ml-pipeline/gpu_kit/README_ext_v2.md", args.out/f"{args.kit_name}_ext_v2.README.md")
    print(f"datasets: {', '.join(plans)}; ext_v2 files: {count}")
    print(f"{archive_path}: {archive_path.stat().st_size/2**30:.2f} GiB, sha256 {sha256(archive_path)}")


if __name__ == "__main__":
    main()
