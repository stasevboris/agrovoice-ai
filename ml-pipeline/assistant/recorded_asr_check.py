"""Prepare a fixed FLEURS val sample and score Android Vosk output; no training or download.

prepare --dataset ml-pipeline/dataset_asr_ru_fleurs_v1 --out artifacts/verification/fleurs_validation_v1
score --cases <folder>/cases.json --recognition <folder>/recognition_results.json --out <evaluation.json>
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import re


SAMPLE_COUNT = 12
SEED = 20261020
MODES = ("main_grammar", "dictation")
NORMALIZATION = "Unicode letters/digits, lowercase, ё→е; punctuation removed; no number expansion"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def select_samples(records):
    if any(item["split"] != "val" for item in records):
        raise ValueError("Only official val records are allowed")
    by_sentence = {}
    for item in sorted(records, key=lambda row: row["filename"]):
        by_sentence.setdefault(item["id"], item)
    candidates = [by_sentence[key] for key in sorted(by_sentence)]
    return random.Random(SEED).sample(candidates, SAMPLE_COUNT)


def convert_audio(source, target, expected_sha):
    import numpy as np
    from scipy.io import wavfile

    if sha256(source) != expected_sha:
        raise ValueError("Source WAV SHA does not match prepared dataset")
    rate, samples = wavfile.read(source)
    if rate != 16000 or samples.ndim != 1 or samples.dtype.kind != "f":
        raise ValueError("Expected original mono float WAV at 16000 Hz")
    if not np.isfinite(samples).all():
        raise ValueError("Audio contains non-finite samples")
    pcm = np.clip(np.rint(samples.astype(np.float64) * 32768), -32768, 32767).astype(np.int16)
    wavfile.write(target, rate, pcm)
    return len(pcm)


def prepare(dataset, out):
    records = [json.loads(line) for line in (dataset / "val.jsonl").read_text(encoding="utf-8").splitlines()]
    selected = select_samples(records)
    source_report = json.loads((dataset / "report.json").read_text(encoding="utf-8"))
    out.mkdir(parents=True, exist_ok=False)
    cases = []
    for index, item in enumerate(selected):
        source = (dataset / item["audio"]).resolve()
        if not source.is_relative_to((dataset / "audio/val").resolve()):
            raise ValueError("Audio must remain inside official val directory")
        filename = f"sample_{index:02d}.wav"
        target = out / filename
        count = convert_audio(source, target, item["sha256"])
        if count != item["num_samples"]:
            raise ValueError("Audio length differs from the prepared manifest")
        cases.append({"file": filename, "sentence_id": item["id"], "reference": item["text"],
                      "source_audio": item["audio"], "source_sha256": item["sha256"],
                      "pcm_sha256": sha256(target), "num_samples": count,
                      "duration_seconds": count / 16000, "split": "val"})
    manifest = {
        "source": source_report["source"], "revision": source_report["revision"],
        "license": source_report["license"], "locale": "ru_ru", "split": "val",
        "selection": {"seed": SEED, "count": SAMPLE_COUNT,
                      "method": "Filename-sort, first recording per sentence ID, sample IDs sorted lexically"},
        "audio_format": "PCM16 LE mono 16000 Hz; round(float * 32768), clip to [-32768, 32767]",
        "duration_seconds": sum(item["duration_seconds"] for item in cases),
        "cases": cases,
        "limitations": ["Recorded read speech, not live microphone or field speech.",
                        "FLEURS has no AgroVoice command labels; no command accuracy is measured.",
                        "Small fixed val sample, not an estimate for the entire corpus; test untouched."]}
    (out / "cases.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def words(text):
    return re.findall(r"[^\W_]+", text.lower().replace("ё", "е"))


def word_errors(reference, hypothesis):
    expected, actual = words(reference), words(hypothesis)
    # State is (edit distance, substitutions, deletions, insertions).
    previous = [(index, 0, 0, index) for index in range(len(actual) + 1)]
    for row, word in enumerate(expected, 1):
        current = [(row, 0, row, 0)]
        for column, recognized in enumerate(actual, 1):
            if word == recognized:
                current.append(previous[column - 1])
                continue
            distance, substitution, deletion, insertion = previous[column - 1]
            substitute = (distance + 1, substitution + 1, deletion, insertion)
            distance, substitution, deletion, insertion = previous[column]
            delete = (distance + 1, substitution, deletion + 1, insertion)
            distance, substitution, deletion, insertion = current[-1]
            insert = (distance + 1, substitution, deletion, insertion + 1)
            current.append(min((substitute, delete, insert), key=lambda state: state[0]))
        previous = current
    _, substitutions, deletions, insertions = previous[-1]
    return dict(substitutions=substitutions, deletions=deletions,
                insertions=insertions, reference_words=len(expected))


def score(manifest, recognition):
    if not recognition.get("complete", False):
        raise ValueError("Recognition run is incomplete")
    expected = {(case["file"], mode): case for case in manifest["cases"] for mode in MODES}
    seen = set()
    corpus = {mode: dict(substitutions=0, deletions=0, insertions=0, reference_words=0,
                        parsed_commands=0) for mode in MODES}
    scored = []
    for sample in recognition["samples"]:
        key = (sample["file"], sample["mode"])
        if key not in expected or key in seen:
            raise ValueError("Unknown or duplicate recording/mode")
        seen.add(key)
        case = expected[key]
        if sample["pcm_sha256"] != case["pcm_sha256"]:
            raise ValueError("Scored PCM SHA differs from prepared sample")
        counts = word_errors(case["reference"], sample["transcript"])
        totals = corpus[sample["mode"]]
        for name, count in counts.items():
            totals[name] += count
        totals["parsed_commands"] += sum(bool(segment.get("action")) for segment in sample["segments"])
        scored.append({**sample, "reference": case["reference"], "word_errors": counts})
    if seen != set(expected):
        raise ValueError("Missing recording/mode")
    for totals in corpus.values():
        errors = sum(totals[name] for name in ("substitutions", "deletions", "insertions"))
        totals["word_errors"] = errors
        totals["wer"] = errors / totals["reference_words"] if totals["reference_words"] else None
    return {**recognition, "samples": scored, "protocol": manifest, "corpus": corpus,
            "normalization": NORMALIZATION,
            "alignment_ties": "Prefer substitution, then deletion, then insertion",
            "command_accuracy_measured": False,
            "command_count_scope": "Observed decoded commands only; no reference command labels or executed actions"}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare")
    preparation.add_argument("--dataset", type=Path, required=True)
    preparation.add_argument("--out", type=Path, required=True)
    scoring = commands.add_parser("score")
    scoring.add_argument("--cases", type=Path, required=True)
    scoring.add_argument("--recognition", type=Path, required=True)
    scoring.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        report = prepare(args.dataset, args.out)
        print(json.dumps({"samples": len(report["cases"]), "duration_seconds": report["duration_seconds"]}))
    else:
        manifest = json.loads(args.cases.read_text(encoding="utf-8"))
        recognition = json.loads(args.recognition.read_text(encoding="utf-8"))
        report = score(manifest, recognition)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report["corpus"], ensure_ascii=False))


if __name__ == "__main__":
    main()
