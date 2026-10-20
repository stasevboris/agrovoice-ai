"""Prepare a local feed from validated KB and prepare_model_release.py output.

Run with the project venv (jsonschema required for KB). This copies to a NEW
directory, never publishes or downloads weights, and never edits Android assets.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def entry(path, kind, version, metadata):
    if not isinstance(version, str) or not 0 < len(version) <= 128:
        raise ValueError("Release version must contain 1 to 128 characters")
    sha = digest(path)
    limit = 512 * 1024 * 1024 if kind == "model" else 5 * 1024 * 1024
    if not 0 < path.stat().st_size <= limit:
        raise ValueError("Release exceeds size limit")
    release_id = f"{kind}-{sha[:24]}"
    return {"id": release_id, "kind": kind, "version": version,
            "file_name": release_id + path.suffix.lower(), "bytes": path.stat().st_size,
            "sha256": sha, "metadata": metadata}


def prepared_models(directory):
    directory = Path(directory)
    required = ("SHA256SUMS.txt", "MODEL_CARD.md", "RELEASE_NOTES.md", "LICENSE", "ATTRIBUTION.md")
    if not all((directory / name).is_file() for name in required):
        raise ValueError("Use the complete output of prepare_model_release.py")
    result = []
    for line in (directory / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([a-zA-Z0-9][a-zA-Z0-9_.-]*\.onnx)", line)
        if match is None:
            raise ValueError("Invalid prepared checksum line")
        sha, name = match.groups()
        path = directory / name
        if path.is_symlink() or path.resolve().parent != directory.resolve():
            raise ValueError("Invalid prepared model path")
        metadata = json.loads(path.with_suffix(".manifest_entry.json").read_text(encoding="utf-8"))
        if metadata.get("family") != "yolo" or metadata.get("dataset") != "allcrops_v1":
            raise ValueError("Only prepared YOLO allcrops_v1 releases are supported")
        if digest(path) != sha or metadata.get("sha256") != sha:
            raise ValueError("Prepared model hash mismatch")
        if not metadata.get("classes") or len(metadata["classes"]) != len(metadata.get("labels_ru", [])):
            raise ValueError("Missing model classes/labels")
        result.append((path, entry(path, "model", path.stem, {"manifest_entry": metadata,
            "prepared_by": "prepare_model_release.py", "distribution": "local"})))
    if not result:
        raise ValueError("Prepared release contains no models")
    return result


def prepared_kb(path):
    original_sha = digest(path)
    spec = importlib.util.spec_from_file_location("validate_kb", ROOT / "kb/validate_kb.py")
    validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validator)
    document = validator.load(path)
    problems = validator.validate(document, validator.load(ROOT / "ml-pipeline/allcrops/taxonomy_v1.json"),
                                  validator.load(ROOT / "kb/kb_v1.schema.json"))
    if problems:
        raise ValueError("KB validation failed: " + "; ".join(problems))
    record = entry(path, "kb", document["kb_version"], {
        "schema": document["schema"], "region": document["region"],
        "card_statuses": sorted({card["status"] for card in document["cards"]}),
        "distribution": "local", "taxonomy": document["taxonomy"],
    })
    if record["sha256"] != original_sha:
        raise ValueError("KB changed during validation")
    return path, record


def prepare_feed(output, kb=None, models=None):
    prepared = []
    if kb is not None:
        prepared.append(prepared_kb(Path(kb)))
    if models is not None:
        prepared.extend(prepared_models(Path(models)))
    if len({record["id"] for _, record in prepared}) != len(prepared):
        raise ValueError("Duplicate release")
    if len(prepared) > 100:
        raise ValueError("Feed exceeds 100 releases")
    feed = {"schema_version": 1, "releases": [record for _, record in prepared]}
    raw = json.dumps(feed, ensure_ascii=False, indent=2)
    if len(raw.encode("utf-8")) > 1024 * 1024:
        raise ValueError("Feed exceeds 1 MiB")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    for source, record in prepared:
        target = output / record["file_name"]
        shutil.copyfile(source, target)
        if digest(target) != record["sha256"] or target.stat().st_size != record["bytes"]:
            raise ValueError("Source changed during copy")
    if models is not None:
        for name in ("MODEL_CARD.md", "RELEASE_NOTES.md", "LICENSE", "ATTRIBUTION.md", "SHA256SUMS.txt"):
            shutil.copyfile(Path(models) / name, output / name)
    (output / "index.json").write_text(raw + "\n", encoding="utf-8")
    return feed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--kb", type=Path)
    parser.add_argument("--models", type=Path, help="Directory emitted by prepare_model_release.py")
    args = parser.parse_args()
    feed = prepare_feed(args.out, args.kb, args.models)
    print(json.dumps({"releases": len(feed["releases"]), "output": str(args.out)}))


if __name__ == "__main__":
    main()
