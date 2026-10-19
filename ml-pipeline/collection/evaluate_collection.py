"""Score JSONL: id + accepted/label (image), id + actions (actual audio executor output).

Use full camera frames with one adjudicated target. This scores decisions, not mAP.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path

from validate_collection import read_manifest, validate


def evaluate(rows, predictions, split):
    selected = {row["id"]: row for row in rows if row["split"] == split}
    if not selected:
        raise ValueError(f"No independent samples for {split}")
    if len({item["id"] for item in predictions}) != len(predictions):
        raise ValueError("Duplicate prediction IDs")
    outputs = {item["id"]: item for item in predictions}
    if set(selected) != set(outputs):
        raise ValueError("Prediction IDs must exactly match the selected split, with no missing samples")
    groups = defaultdict(lambda: {"samples": 0, "correct": 0, "accepted": 0, "wrong_accepted": 0, "false_actions": 0})
    errors = []
    for identity, row in selected.items():
        output = outputs[identity]
        group = groups[f"{row['modality']}/{row['scenario']}"]
        group["samples"] += 1
        if row["modality"] == "image":
            accepted = output.get("accepted")
            if type(accepted) is not bool:
                raise ValueError(f"{identity}: explicit boolean accepted is required")
            if accepted and not isinstance(output.get("label"), str):
                raise ValueError(f"{identity}: accepted output needs a label")
            known = row["scenario"] == "known_leaf"
            correct = (accepted and output["label"] == row["label"]) if known else not accepted
            group["accepted"] += int(accepted)
            group["wrong_accepted"] += int(accepted and not correct)
        else:
            actions = output.get("actions")
            if not isinstance(actions, list):
                raise ValueError(f"{identity}: executor action list is required")
            expected = json.loads(row["expected_actions"])
            correct = actions == expected
            group["false_actions"] += int(bool(actions) and not correct)
        group["correct"] += int(correct)
        if not correct:
            errors.append({"id": identity, "scenario": row["scenario"], "output": output})
    for key, group in groups.items():
        group["accuracy"] = group["correct"] / group["samples"]
        if key.startswith("image/"):
            group["coverage"] = group["accepted"] / group["samples"]
            group["risk_among_accepted"] = group["wrong_accepted"] / group["accepted"] if group["accepted"] else None
    return {"split": split, "samples": len(selected), "groups": dict(groups), "errors": errors,
            "scope": "Decision-level full-frame single-target images and actual executed speech actions; not mAP or WER"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("predictions", type=Path)
    parser.add_argument("--data-root", required=True, type=Path)
    parser.add_argument("--split", choices=("val", "test"), required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    rows = read_manifest(args.manifest)
    validation = validate(rows, args.data_root)
    if validation["status"] != "valid":
        parser.error("Collection is not valid; run validate_collection.py and resolve its report first")
    predictions = [json.loads(line) for line in args.predictions.read_text(encoding="utf-8").splitlines() if line.strip()]
    report = evaluate(rows, predictions, args.split)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{report['samples']} samples; {len(report['errors'])} errors. {args.out}")


if __name__ == "__main__":
    main()
