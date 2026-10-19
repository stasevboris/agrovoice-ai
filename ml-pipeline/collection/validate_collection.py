"""Validate a real, consented field/voice collection without inventing labels or splits."""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path
import wave

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = {"known_leaf", "unknown_crop", "unknown_state", "nonplant", "command", "background"}


def read_manifest(path):
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def validate(rows, data_root):
    taxonomy = json.loads((ROOT / "ml-pipeline/allcrops/taxonomy_v1.json").read_text(encoding="utf-8"))
    codes = {item["code"] for item in taxonomy["classes"]}
    crops = {item["crop"] for item in taxonomy["classes"]}
    root = Path(data_root).resolve()
    errors = []
    identifiers = set()
    groups = defaultdict(set)
    pairs = defaultdict(list)
    hashes = {}
    file_owners = {}
    for index, row in enumerate(rows, 1):
        identity = row.get("id", "")
        prefix = identity or f"row {index}"
        issues = []
        required = ("id", "file", "modality", "split", "source", "series", "scenario", "rights")
        issues.extend(f"missing {key}" for key in required if not row.get(key, "").strip())
        if identity in identifiers:
            issues.append("duplicate sample id")
        identifiers.add(identity)
        split = row.get("split")
        if split not in {"train", "val", "test"}:
            issues.append("split must be assigned before evaluation: train/val/test")
        if row.get("source_kind") != "real":
            issues.append("independent collection accepts real samples only; keep synthetic/FLEURS separate")
        modality = row.get("modality")
        scenario = row.get("scenario")
        if scenario not in SCENARIOS:
            issues.append("unknown scenario")
        if modality == "image":
            for key in ("site", "label", "label_source", "light"):
                if not row.get(key):
                    issues.append(f"missing {key}")
            if scenario not in {"known_leaf", "unknown_crop", "unknown_state", "nonplant"}:
                issues.append("image scenario required")
            if scenario != "nonplant" and not row.get("plant"):
                issues.append("plant identity required, including unknown species")
            label = row.get("label", "")
            if scenario == "known_leaf" and label not in codes:
                issues.append("known_leaf needs a confirmed taxonomy code")
            if scenario == "unknown_state" and label not in crops:
                issues.append("unknown_state needs crop only, not a guessed disease")
            if scenario in {"unknown_crop", "nonplant"} and (label in codes or label in crops):
                issues.append("unknown/object must not inherit a supported disease/crop label")
        elif modality == "audio":
            for key in ("speaker", "consent_ref", "expected_actions", "context"):
                if not row.get(key):
                    issues.append(f"missing {key}")
            if scenario not in {"command", "background"}:
                issues.append("audio scenario required")
            if scenario == "command" and not row.get("transcript"):
                issues.append("command needs a human transcript")
            consent = row.get("consent")
            if consent not in {"evaluation", "training_and_evaluation"}:
                issues.append("recorded consent required")
            if split == "train" and consent != "training_and_evaluation":
                issues.append("evaluation-only consent does not permit training")
            try:
                actions = json.loads(row.get("expected_actions", ""))
                if not isinstance(actions, list) or any(
                    not isinstance(action, dict) or set(action) != {"action", "value"}
                    or not all(isinstance(value, str) for value in action.values()) for action in actions
                ):
                    raise ValueError("expected action/value objects")
                if scenario == "background" and actions:
                    raise ValueError("background must not execute actions")
            except (ValueError, TypeError):
                issues.append("expected_actions must be an explicit JSON action list (empty for refusal/background)")
            try:
                context_path = (root / row.get("context", "")).resolve()
                if not context_path.is_relative_to(root):
                    raise ValueError("context outside data root")
                context = json.loads(context_path.read_text(encoding="utf-8"))
                if not isinstance(context, dict) or context.get("screen") not in {
                    "main", "camera", "gallery", "journal", "plots", "settings", "knowledge", "note", "note_review"
                }:
                    raise ValueError("context must contain a protocol screen")
            except (OSError, ValueError) as error:
                issues.append(f"context: {error}")
        else:
            issues.append("modality must be image or audio")
        try:
            path = (root / row.get("file", "")).resolve()
            if not path.is_relative_to(root) or not path.is_file() or path.stat().st_size == 0:
                raise ValueError("missing, empty or outside data root")
            if modality == "image":
                with Image.open(path) as picture:
                    picture.verify()
            elif modality == "audio":
                with wave.open(str(path), "rb") as recording:
                    if recording.getnframes() == 0 or recording.getcomptype() != "NONE":
                        raise ValueError("audio must be a nonempty PCM WAV")
            with path.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            hashes[identity] = digest
            if digest in file_owners:
                issues.append(f"duplicate file content of {file_owners[digest]}; not an independent sample")
            file_owners[digest] = identity
            groups[("sha256", digest)].add(split)
        except (OSError, ValueError, wave.Error, EOFError) as error:
            issues.append(f"file: {error}")
        # IDs are stable across source folders and acquisition dates.
        for field in ("series", "plant", "speaker", "site", "pair"):
            if row.get(field):
                groups[(field, row[field])].add(split)
        if row.get("pair"):
            if modality != "image":
                issues.append("light/shadow pairs are image samples")
            pairs[row["pair"]].append(row)
        errors.extend(f"{prefix}: {issue}" for issue in issues)
    for (field, identity), splits in groups.items():
        if len(splits) > 1:
            errors.append(f"split leakage: {field}={identity} in {sorted(str(item) for item in splits)}")
    for pair, members in pairs.items():
        stable = {(row.get("plant"), row.get("scenario"), row.get("label")) for row in members}
        if {row.get("light") for row in members} != {"light", "shadow"} or len(stable) != 1:
            errors.append(f"pair {pair}: need real light/shadow of the same plant")
    report = {
        "status": "awaiting_data" if not rows else "invalid" if errors else "valid",
        "samples": len(rows), "counts": dict(Counter(f"{r.get('modality')}/{r.get('split')}/{r.get('scenario')}" for r in rows)),
        "has_val_and_test": {"val", "test"}.issubset({row.get("split") for row in rows}),
        "errors": errors, "sha256": hashes,
        "limitations": ["Provenance, consent and expert labels still require human verification.",
                        "Exact hashes do not detect near-duplicates; review acquisition groups before freezing test.",
                        "Valid rows alone do not prove sufficient coverage or model quality."],
    }
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--data-root", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    report = validate(read_manifest(args.manifest), args.data_root)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{report['status']}: {report['samples']} samples; {len(report['errors'])} errors. {args.out}")
    return 0 if report["status"] == "valid" else 2


if __name__ == "__main__":
    raise SystemExit(main())
