"""Screen downloaded bean images against existing sources and across source splits."""

import json
import os
import shutil
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

from build_external_v2 import dihedral_hashes, group_duplicates, min_distance
from prepare_selected import ROOT, fresh_output, write_json


def select_groups(rows, groups, distances):
    members = defaultdict(list)
    for i, group in enumerate(groups):
        members[group].append(i)
    accepted = []
    excluded = []
    for indices in members.values():
        reason = None
        if any(distances[i] <= 6 for i in indices):
            reason = "near_duplicate_existing_source"
        elif len({rows[i]["split"] for i in indices}) > 1:
            reason = "near_duplicate_cross_split"
        elif len({rows[i]["source_label"] for i in indices}) > 1:
            reason = "near_duplicate_conflicting_label"
        if reason:
            excluded.extend({"image": rows[i]["image"], "reason": reason} for i in indices)
        else:
            accepted.append(indices[0])
            excluded.extend({"image": rows[i]["image"], "reason": "near_duplicate_within_split"} for i in indices[1:])
    return accepted, excluded


def main():
    source = ROOT / "ml-pipeline/dataset_unknown_beans_v1"
    rows = []
    for split in ("train", "val", "test"):
        with (source / f"{split}.jsonl").open(encoding="utf-8") as stream:
            rows.extend(json.loads(line) for line in stream)
    print("Computing rotation/reflection pHash on CPU", flush=True)
    values = [dihedral_hashes(source / row["image"]) for row in rows]
    if any(v is None for v in values):
        raise ValueError("Unreadable image")
    hashes = np.array(values, dtype=np.uint64)
    index_path = ROOT / "ml-pipeline/.cache/external/hash_index/current_sources.npy"
    existing = np.load(index_path)
    distances = min_distance(hashes, existing, block=8)
    groups = group_duplicates(hashes)
    accepted, excluded = select_groups(rows, groups, distances)
    out = fresh_output("dataset_unknown_beans_screened_v1")
    shutil.copy2(Path(__file__).with_name("licenses") / "IBEAN-MIT.txt", out / "LICENSE-MIT.txt")
    selected = []
    for index in accepted:
        row = dict(rows[index], duplicate_group=f"beans:{groups[index]}")
        target = out / row["image"]
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.link(source / row["image"], target)
        except OSError:
            shutil.copy2(source / row["image"], target)
        selected.append(row)
    for split in ("train", "val", "test"):
        with (out / f"{split}.jsonl").open("w", encoding="utf-8") as stream:
            for row in selected:
                if row["split"] == split:
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    write_json(out / "excluded.json", excluded)
    original_report = json.loads((source / "report.json").read_text(encoding="utf-8"))
    report = dict(original_report, counts=dict(Counter(r["split"] for r in selected)),
                  excluded=dict(Counter(r["reason"] for r in excluded)),
                  screening={"method": "64-bit pHash, 8 rotations/reflections, distance <=6",
                             "reference_hashes": len(existing), "reference_index": str(index_path),
                             "scope": "Cached PlantDoc/PlantVillage/OLID/Zenodo index; not a global web duplicate check"})
    report["limitations"] = [line for line in original_report["limitations"] if "near-duplicate screening pending" not in line]
    report["limitations"].append("pHash screening does not prove image/plant independence. Source split retained after exclusions.")
    write_json(out / "report.json", report)
    print(json.dumps(report["counts"]), flush=True)


if __name__ == "__main__":
    main()
