"""Download pinned public corpora and preserve their original labels and splits.

Only prepares data. Does not train models, execute dataset scripts or use a GPU.
Run from the repository root: python -X utf8 ml-pipeline/external/prepare_selected.py
"""

import argparse
import csv
import hashlib
import io
import json
import shutil
import tarfile
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path

from scipy.io import wavfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "ml-pipeline/.cache/selected_20261019"
FLEURS_REV = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
BEANS_REV = "27aa014ce09b193e1a6f58112d4a66e0eddb69c5"


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(url, name):
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    receipt = path.with_suffix(path.suffix + ".json")
    if path.exists() and receipt.exists():
        metadata = json.loads(receipt.read_text(encoding="utf-8"))
        if metadata["url"] == url and metadata["sha256"] == digest(path):
            return path, metadata
        raise ValueError(f"Cache mismatch: {path}")
    temporary = path.with_suffix(path.suffix + ".part")
    print(f"Downloading {name}", flush=True)
    request = urllib.request.Request(url, headers={"User-Agent": "AgroVoice-Research/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        with temporary.open("wb") as target:
            shutil.copyfileobj(response, target, 1024 * 1024)
        expected = response.headers.get("Content-Length")
    if expected and temporary.stat().st_size != int(expected):
        raise ValueError(f"Incomplete download: {name}")
    temporary.replace(path)
    metadata = {"url": url, "bytes": path.stat().st_size, "sha256": digest(path)}
    write_json(receipt, metadata)
    return path, metadata


def fresh_output(name):
    out = ROOT / "ml-pipeline" / name
    if out.exists() and any(out.iterdir()):
        raise FileExistsError(f"Output is not empty: {out}")
    out.mkdir(exist_ok=True)
    return out


def prepare_beans():
    downloads = []
    rows = []
    files = []
    for split in ("train", "validation", "test"):
        url = f"https://huggingface.co/datasets/AI-Lab-Makerere/beans/resolve/{BEANS_REV}/data/{split}.zip"
        path, metadata = download(url, f"beans_{split}.zip")
        downloads.append(metadata)
        files.append((split, path))
    out = fresh_output("dataset_unknown_beans_v1")
    for split, path in files:
        with zipfile.ZipFile(path) as archive:
            for member in sorted(archive.namelist()):
                if not member.lower().endswith((".jpg", ".jpeg", ".png")):
                    continue
                parts = Path(member).parts
                label = parts[-2]
                if label not in {"angular_leaf_spot", "bean_rust", "healthy"}:
                    raise ValueError(f"Unexpected bean label: {member}")
                data = archive.read(member)
                sha = hashlib.sha256(data).hexdigest()
                relative = Path("images") / split / f"{sha}.jpg"
                target = out / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                rows.append({"image": relative.as_posix(), "source": "Makerere_ibean",
                             "source_file": member, "source_split": split,
                             "source_label": label, "crop": "bean",
                             "target": "unsupported_crop", "sha256": sha,
                             "split": "val" if split == "validation" else split})
    # Same bytes cannot straddle train/evaluation; quarantine conflicting copies.
    occurrences = {}
    for row in rows:
        occurrences.setdefault(row["sha256"], set()).add((row["split"], row["source_label"]))
    seen = set()
    accepted = []
    rejected = []
    for row in rows:
        if len(occurrences[row["sha256"]]) > 1 or row["sha256"] in seen:
            rejected.append(row)
        else:
            accepted.append(row)
        seen.add(row["sha256"])
    for split in ("train", "val", "test"):
        with (out / f"{split}.jsonl").open("w", encoding="utf-8") as stream:
            for row in accepted:
                if row["split"] == split:
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    write_json(out / "quarantine.json", rejected)
    report = {"source": "https://github.com/AI-Lab-Makerere/ibean",
              "license": "MIT", "downloads": downloads,
              "counts": dict(Counter(r["split"] for r in accepted)),
              "source_labels": dict(Counter(r["source_label"] for r in accepted)),
              "quarantined": len(rejected), "annotation_target": "image_classification",
              "limitations": ["No leaf boxes: never use empty detection labels.",
                              "Bean is outside taxonomy_v1; diseases are not soybean labels.",
                              "Original split; plant/session IDs unavailable.",
                              "Exact dedup only; near-duplicate screening pending.",
                              "After exposure training, test is seen-crop OOD, not novel-species test."]}
    write_json(out / "report.json", report)
    print(out, report["counts"], flush=True)


def fleurs_records(tsv_text):
    rows = []
    for fields in csv.reader(io.StringIO(tsv_text), delimiter="\t", quoting=csv.QUOTE_NONE):
        if len(fields) != 7:
            raise ValueError("Unexpected FLEURS TSV columns")
        rows.append({"id": fields[0], "filename": fields[1], "text": fields[2],
                     "normalized_text": fields[3], "num_samples": int(fields[5]),
                     "gender": fields[6]})
    return rows


def prepare_fleurs():
    downloads = []
    prepared = []
    base = f"https://huggingface.co/datasets/google/fleurs/resolve/{FLEURS_REV}/data/ru_ru"
    for source_split in ("train", "dev", "test"):
        table, receipt = download(f"{base}/{source_split}.tsv", f"fleurs_ru_{source_split}.tsv")
        downloads.append(receipt)
        audio, receipt = download(f"{base}/audio/{source_split}.tar.gz", f"fleurs_ru_{source_split}.tar.gz")
        downloads.append(receipt)
        prepared.append((source_split, table, audio))
    out = fresh_output("dataset_asr_ru_fleurs_v1")
    counts = {}
    durations = {}
    all_audio = {}
    for source_split, table, audio in prepared:
        split = "val" if source_split == "dev" else source_split
        records = fleurs_records(table.read_text(encoding="utf-8"))
        indexed = {r["filename"]: r for r in records}
        if len(indexed) != len(records):
            raise ValueError("Repeated filename in FLEURS TSV")
        target_dir = out / "audio" / split
        target_dir.mkdir(parents=True)
        found = set()
        with tarfile.open(audio, "r:gz") as archive:
            for member in archive:
                name = Path(member.name).name
                if not member.isfile() or name not in indexed:
                    continue
                if name in found:
                    raise ValueError(f"Repeated archive audio: {name}")
                target = target_dir / name
                with archive.extractfile(member) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                rate, samples_array = wavfile.read(target)
                samples = len(samples_array)
                if rate != 16000 or samples_array.ndim != 1:
                    raise ValueError(f"Unexpected audio format: {name}")
                if samples != indexed[name]["num_samples"]:
                    raise ValueError(f"TSV/audio duration mismatch: {name}")
                sha = digest(target)
                if sha in all_audio and all_audio[sha] != split:
                    raise ValueError("FLEURS audio crosses source splits")
                all_audio[sha] = split
                indexed[name].update(audio=target.relative_to(out).as_posix(),
                                     duration_seconds=samples / rate, sample_rate=rate,
                                     split=split, source="google/fleurs:ru_ru", sha256=sha)
                found.add(name)
        if found != set(indexed):
            raise ValueError(f"Missing FLEURS audio: {len(set(indexed) - found)}")
        with (out / f"{split}.jsonl").open("w", encoding="utf-8") as stream:
            for row in records:
                stream.write(json.dumps(row, ensure_ascii=False) + "\n")
        counts[split] = len(records)
        durations[split] = sum(r["duration_seconds"] for r in records)
    write_json(out / "report.json", {"license": "CC-BY-4.0", "revision": FLEURS_REV,
               "source": "https://huggingface.co/datasets/google/fleurs",
               "counts": counts, "duration_seconds": durations, "downloads": downloads,
               "audio_format": "Original mono 16 kHz float WAV; convert to PCM16 only for runtimes that require it.",
               "limitations": ["Read speech, not field commands; no claim of domain WER.",
                               "Retains official splits; no speaker identity inferred from filenames."]})
    print(out, counts, flush=True)


def prepare_massive():
    url = "https://amazon-massive-nlu-dataset.s3.amazonaws.com/amazon-massive-dataset-1.1.tar.gz"
    archive_path, receipt = download(url, "massive_1.1.tar.gz")
    out = fresh_output("dataset_intents_ru_massive_v1")
    rows = []
    with tarfile.open(archive_path, "r:gz") as archive:
        for member in archive:
            if member.isfile() and Path(member.name).name == "ru-RU.jsonl":
                with archive.extractfile(member) as stream:
                    rows.extend(json.loads(line) for line in stream)
    if not rows:
        raise ValueError("Russian MASSIVE data not found")
    counts = Counter()
    for split, partition in (("train", "train"), ("val", "dev"), ("test", "test")):
        with (out / f"{split}.jsonl").open("w", encoding="utf-8") as stream:
            for row in rows:
                if row["partition"] == partition:
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
                    counts[split] += 1
    if sum(counts.values()) != len(rows):
        raise ValueError("Unknown MASSIVE partition")
    write_json(out / "report.json", {"source": "https://github.com/alexa/massive",
               "version": "1.1", "license": "CC-BY-4.0", "downloads": [receipt],
               "counts": dict(counts), "intents": sorted({r["intent"] for r in rows}),
               "role": "Auxiliary Russian intent/slot task with ORIGINAL labels.",
               "limitations": ["Not AgroVoice actions; no automatic mapping to app commands.",
                               "Do not merge test into training or duplicate parallel languages across splits."]})
    print(out, dict(counts), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpora", nargs="*", choices=("beans", "fleurs", "massive"))
    args = parser.parse_args()
    for name in args.corpora or ("beans", "massive", "fleurs"):
        {"beans": prepare_beans, "fleurs": prepare_fleurs, "massive": prepare_massive}[name]()


if __name__ == "__main__":
    main()
