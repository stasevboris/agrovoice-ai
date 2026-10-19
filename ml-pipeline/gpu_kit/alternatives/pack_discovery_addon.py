"""Package only accepted new photos plus their provenance; never overwrite."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[3]
DATASETS = ["dataset_web_leaf_presence_v1", "dataset_optima_rgb_v1"]


def selected_files(folder):
    selected = {path.name for path in folder.glob("*.json")}
    for name in ("train.jsonl", "crops.jsonl"):
        manifest = folder / name
        if not manifest.is_file():
            continue
        selected.add(name)
        for line in manifest.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            selected.add(row["image"])
            if "source_member" in row:
                image = Path(row["image"])
                selected.add((Path("native_labels") / image.parent.name / (image.stem + ".txt")).as_posix())
    return sorted(selected)


def main():
    out = ROOT / "artifacts/gpu_kit/agrovoice_discovery_addon_20261019.zip"
    out.parent.mkdir(parents=True, exist_ok=True)
    counts = {}
    with zipfile.ZipFile(out, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for name in DATASETS:
            folder = ROOT / "ml-pipeline" / name
            files = selected_files(folder)
            for relative in files:
                archive.write(folder / relative, f"data/{name}/{relative}")
            counts[name] = len(files)
        for path in ["ATTRIBUTION.md", "LICENSE", "docs/guides/TRAINING_PLAYBOOK.md", "docs/data/WEB_DATA_2026-10-19.md",
                     "ml-pipeline/external/web_photo_sources_v1.json", "ml-pipeline/external/web_photo_review_v1.json",
                     "ml-pipeline/external/prepare_web_photos.py", "ml-pipeline/external/prepare_optima.py"]:
            archive.write(ROOT / path, path)
        archive.write(Path(__file__).with_name("configure_yolo_pilot.py"), "configure_yolo_pilot.py")
        archive.writestr("README.txt", "Extract beside the existing research data package. New data are train-only auxiliaries, NOT additions to taxonomy_v1 detection. Read docs/guides/TRAINING_PLAYBOOK.md and docs/data/WEB_DATA_2026-10-19.md. Preparers are included for reference; reproduction needs the project external helpers and source hash index. No weights included.\n")
    with zipfile.ZipFile(out) as archive:
        bad = archive.testzip()
        if bad is not None:
            raise ValueError(f"CRC error: {bad}")
        entries = len(archive.infolist())
    with out.open("rb") as stream:
        sha = hashlib.file_digest(stream, "sha256").hexdigest()
    report = {"archive": out.relative_to(ROOT).as_posix(), "bytes": out.stat().st_size,
              "sha256": sha, "crc_verified": True, "entries": entries, "dataset_files": counts,
              "weights_included": False, "training_performed": False}
    out.with_suffix(".report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
