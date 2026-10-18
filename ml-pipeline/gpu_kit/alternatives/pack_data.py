"""Package prepared data for a rented GPU; no training, weights or publication."""

import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[3]
DATASETS = ["dataset_vision_training_v1", "dataset_nonplant_openimages_v2",
            "dataset_unknown_beans_screened_v1", "dataset_unknown_deepweeds_v1",
            "dataset_assistant_ru_v1", "dataset_intents_ru_massive_v1",
            "dataset_asr_ru_fleurs_v1"]


def accepted_images(root):
    accepted = set()
    for split in ("train", "val", "test"):
        with (root / f"{split}.jsonl").open(encoding="utf-8") as stream:
            accepted.update(json.loads(line)["image"] for line in stream)
    return accepted


def main():
    out = ROOT / "artifacts/gpu_kit/agrovoice_research_data_20261019.zip"
    out.parent.mkdir(parents=True, exist_ok=True)
    counts = {}
    dataset_reports = {}
    # Check all expected datasets before starting an archive.
    for name in DATASETS:
        dataset_reports[name] = json.loads((ROOT / "ml-pipeline" / name / "report.json").read_text(encoding="utf-8"))
    if not dataset_reports["dataset_nonplant_openimages_v2"]["training_ready"]:
        raise ValueError("Unreviewed negatives cannot be packaged")
    with zipfile.ZipFile(out, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for name in DATASETS:
            folder = ROOT / "ml-pipeline" / name
            accepted = accepted_images(folder) if name == "dataset_unknown_deepweeds_v1" else None
            count = 0
            source_bytes = 0
            for path in sorted(folder.rglob("*")):
                if not path.is_file():
                    continue
                relative = path.relative_to(folder).as_posix()
                if accepted is not None and relative.startswith("images/") and relative not in accepted:
                    continue
                archive.write(path, f"data/{name}/{relative}")
                count += 1
                source_bytes += path.stat().st_size
            counts[name] = {"files": count, "uncompressed_bytes": source_bytes}
            print(name, count, "files", flush=True)
        for path, relative in [
            (Path(__file__).with_name("README.md"), "README.md"),
            (Path(__file__).with_name("configure_dfine.py"), "configure_dfine.py"),
            (ROOT / "ATTRIBUTION.md", "ATTRIBUTION.md"),
            (ROOT / "LICENSE", "LICENSE"),
            (ROOT / "docs/data/TRAINING_PREPARATION_REPORT.json", "TRAINING_PREPARATION_REPORT.json"),
            (ROOT / "docs/data/DATASET_CANDIDATES_2026-10-19.csv", "DATASET_CANDIDATES.csv"),
            (ROOT / "docs/research/DATA_AND_MODEL_SELECTION_2026-10-19.md", "DATA_AND_MODEL_SELECTION.md"),
        ]:
            archive.write(path, relative)
    with out.open("rb") as stream:
        sha = hashlib.file_digest(stream, "sha256").hexdigest()
    # Full CRC verification once; do not call an archive ready just because it exists.
    with zipfile.ZipFile(out) as archive:
        bad = archive.testzip()
        if bad is not None:
            raise ValueError(f"Archive CRC failed: {bad}")
        entries = len(archive.infolist())
    report = {"archive": str(out.relative_to(ROOT)), "bytes": out.stat().st_size,
              "sha256": sha, "entries": entries, "crc_verified": True,
              "datasets": counts, "weights_included": False, "training_performed": False}
    out.with_suffix(".report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
