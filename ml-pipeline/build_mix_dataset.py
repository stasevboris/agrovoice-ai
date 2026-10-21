"""Merge dataset_allcrops_v1 with the screened web photos (dataset_web_search_v1/yolo).

    python -X utf8 ml-pipeline/build_mix_dataset.py

Output: ml-pipeline/dataset_allcrops_web_v1 (git-ignored). Training images = allcrops train
+ web train; validation for early stopping = the same val_select.txt (field val + lab val);
test = the same images/test. Web val goes to images/val and val_web.txt, but is NOT used for
early stopping: web labels are weak (search phrase and page), field val stays the only referee.

Files are hard-linked when both datasets are on one volume, so the mix costs almost no disk.

    python -X utf8 ml-pipeline/build_mix_dataset.py --overwrite
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT/"ml-pipeline/dataset_allcrops_v1"
WEB = ROOT/"ml-pipeline/dataset_web_search_v1/yolo"
OUT = ROOT/"ml-pipeline/dataset_allcrops_web_v1"
SPLITS = ("train", "val", "test")
LISTS = ("val_select.txt", "val_field.txt", "test_field.txt", "val_lab.txt", "test_lab.txt")
YAMLS = ("data.yaml", "data_field.yaml", "data_lab.yaml")


def read_jsonl(path: Path):
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


class Linker:
    """Hard-link a file, copy only when the volumes differ; count what happened for the report."""

    def __init__(self):
        self.linked = 0
        self.copied = 0

    def __call__(self, source: Path, target: Path):
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            raise FileExistsError(target)
        try:
            os.link(source, target)
            self.linked += 1
        except OSError:
            shutil.copyfile(source, target)
            self.copied += 1


def plan_web(web: Path):
    """Web images and labels keyed by split, with a prefix that cannot clash with allcrops names."""
    planned = []
    for split in ("train", "val"):
        for image in sorted((web/"images"/split).iterdir()):
            if image.suffix.lower() not in (".jpg", ".jpeg", ".png"):
                continue
            label = web/"labels"/split/f"{image.stem}.txt"
            if not label.exists():
                raise FileNotFoundError(label)
            planned.append(dict(split=split, image=image, label=label,
                                name=f"web_{image.name}", label_name=f"web_{image.stem}.txt"))
    return planned


def web_class(manifest: Path):
    """Weak class of every web image, from the manifest the web build wrote."""
    if not manifest.exists():
        return {}
    return {row["sha256"]: row["class"] for row in read_jsonl(manifest)}


def link_base(base: Path, out: Path, linker: Linker):
    """All allcrops images and labels keep their names; returns the base train image names."""
    train_names = []
    for split in SPLITS:
        for image in sorted((base/"images"/split).iterdir()):
            linker(image, out/"images"/split/image.name)
            label = base/"labels"/split/f"{image.stem}.txt"
            linker(label, out/"labels"/split/label.name)
            if split == "train":
                train_names.append(image.name)
    return train_names


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", type=Path, default=BASE)
    parser.add_argument("--web", type=Path, default=WEB)
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args(argv)

    base_report = json.loads((args.base/"report.json").read_text(encoding="utf-8"))
    web_report_path = args.web/"report.json"
    if not web_report_path.exists():
        parser.error(f"Missing {web_report_path}; run prepare_web_search.py build first")
    web_report = json.loads(web_report_path.read_text(encoding="utf-8"))
    web_fingerprint = web_report.get("fingerprint") or sha256(args.web/"manifest.jsonl")
    if not (args.base/"manifest.jsonl").exists():
        parser.error(f"Missing {args.base/'manifest.jsonl'}")
    if args.out.exists() and any(args.out.iterdir()):
        if not args.overwrite:
            parser.error(f"{args.out} is not empty; pass --overwrite to rebuild it")
        shutil.rmtree(args.out)
    for folder in ("images", "labels"):
        for split in SPLITS:
            (args.out/folder/split).mkdir(parents=True, exist_ok=True)

    linker = Linker()
    train_names = link_base(args.base, args.out, linker)
    planned = plan_web(args.web)
    class_of = web_class(args.web/"manifest.jsonl")
    web_names = {"train": [], "val": []}
    web_images = Counter()
    for item in planned:
        linker(item["image"], args.out/"images"/item["split"]/item["name"])
        linker(item["label"], args.out/"labels"/item["split"]/item["label_name"])
        web_names[item["split"]].append(f"./images/{item['split']}/{item['name']}")
        web_images[item["split"]] += 1
    print(f"linked {linker.linked} files, copied {linker.copied}", flush=True)

    def write_list(name, lines):
        (args.out/name).write_text("\n".join(lines)+"\n", encoding="utf-8", newline="\n")

    write_list("train_web.txt", web_names["train"])
    write_list("val_web.txt", web_names["val"])
    write_list("train_base.txt", [f"./images/train/{name}" for name in train_names])
    for name in LISTS:
        shutil.copyfile(args.base/name, args.out/name)
    for name in YAMLS:
        shutil.copyfile(args.base/name, args.out/name)
    shutil.copyfile(args.base/"taxonomy_v1.json", args.out/"taxonomy_v1.json")
    shutil.copyfile(args.base/"classes.json", args.out/"classes.json")

    # A missing image or label would only surface on the GPU machine, so check every list here.
    audit = dict(lists={}, images=0, labels=0)
    for name in (*LISTS, "train_web.txt", "val_web.txt", "train_base.txt"):
        lines = [line for line in (args.out/name).read_text(encoding="utf-8").splitlines() if line.strip()]
        missing = [line for line in lines if not (args.out/line.removeprefix("./")).exists()]
        if missing:
            raise SystemExit(f"{name}: {len(missing)} missing files, first {missing[0]}")
        audit["lists"][name] = len(lines)
    for split in SPLITS:
        images = list((args.out/"images"/split).glob("*.jpg"))
        labels = list((args.out/"labels"/split).glob("*.txt"))
        without_label = [image.name for image in images if not (args.out/"labels"/split/f"{image.stem}.txt").exists()]
        if without_label:
            raise SystemExit(f"{split}: {len(without_label)} images without labels, first {without_label[0]}")
        audit["images"] += len(images)
        audit["labels"] += len(labels)

    base_splits = base_report["splits"]
    splits = dict(base_splits) | {"web_train": web_images["train"], "web_val": web_images["val"]}
    # Per class: web counts are weak labels, base counts are the checked ones.
    per_class = []
    base_classes = {row["code"] for row in base_report["per_class"]}
    unknown = set(class_of.values()) - base_classes
    if unknown:
        raise SystemExit(f"Web classes not in the allcrops taxonomy: {sorted(unknown)}")
    web_train_stems = {Path(line).stem.removeprefix("web_") for line in web_names["train"]}
    web_train_by_class = Counter(cls for sha, cls in class_of.items() if sha in web_train_stems)
    for row in base_report["per_class"]:
        per_class.append(dict(id=row["id"], code=row["code"], ru=row["ru"], train_images=row["train_images"],
                              val_images=row["val_images"], test_images=row["test_images"],
                              web_train_images=web_train_by_class[row["code"]]))
    fingerprint = hashlib.sha256("".join([
        base_report["fingerprint"], sha256(web_report_path),
        f"{web_images['train']}/{web_images['val']}\n",
    ]).encode()).hexdigest()
    report = dict(
        dataset="allcrops_web_v1", fingerprint=fingerprint,
        composition={"base": args.base.name, "base_fingerprint": base_report["fingerprint"],
                     "web": args.web.as_posix(), "web_fingerprint": web_fingerprint},
        splits=splits,
        audit=audit,
        train="allcrops train + web train (images/train)",
        validation="val_select.txt: field val + up to 15 lab val per class; web val is not used for early stopping",
        web_val="val_web.txt (weak labels; report separately, never as the final metric)",
        per_class=per_class,
        web_geometry=web_report.get("geometry", {}),
        web_sites="train/val split by whole web site; no test split for weak labels",
        limitations=[
            "Web labels come from the search phrase and the publishing page: weak labels, not an expert diagnosis.",
            "Field metrics on the former field val/test remain the only basis for replacing the APK model.",
            "Web photos are not redistributed; rights of every source must be checked before publishing weights or data.",
            "Rare web classes (a few photos per class) cannot make those classes robust on their own.",
        ],
    )
    (args.out/"report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.out/"web_manifest.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False)+"\n" for row in (read_jsonl(args.web/"manifest.jsonl")
                if (args.web/"manifest.jsonl").exists() else [])), encoding="utf-8", newline="\n")
    (args.out/"README.md").write_text(
        "# allcrops_web_v1\n\n"
        "Смесь `dataset_allcrops_v1` и отфильтрованных веб-фото (`dataset_web_search_v1/yolo`).\n\n"
        f"- train: {splits['train']} проверенных + {web_images['train']} веб-фото;\n"
        f"- val (ранняя остановка и метрики): `val_select.txt` из allcrops_v1, веб-val не участвует;\n"
        f"- test: `images/test` из allcrops_v1;\n"
        f"- веб-val: `val_web.txt` ({web_images['val']} фото) — только справочно, метки слабые.\n\n"
        "Веб-метки — поисковый запрос и страница-источник, не диагноз эксперта.\n"
        "Решение о замене модели в APK — только по field-метрикам (PlantDoc val/test).\n",
        encoding="utf-8", newline="\n")
    print(json.dumps(dict(out=str(args.out), fingerprint=fingerprint, splits=splits,
                          audit=audit, linked=linker.linked, copied=linker.copied), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
