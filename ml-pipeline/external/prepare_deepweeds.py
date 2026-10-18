"""Prepare unsupported-species exposure and species-held-out OOD probes.

DeepWeeds Negative means 'none of the target weeds', NOT 'no plant/no leaf'.
Never export these photos as empty detector labels or invent disease diagnoses.
"""

import csv
import hashlib
import json
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

from build_external_v2 import dihedral_hashes, group_duplicates, min_distance
from prepare_selected import ROOT, download, fresh_output, write_json
from screen_unknown import select_groups

SPECIES_SPLITS = {"1": "train", "2": "train", "3": "train", "4": "val",
                  "5": "val", "6": "test", "7": "test"}


def session_group(filename):
    parts = Path(filename).stem.split("-")
    if len(parts) != 3:
        raise ValueError(f"Unexpected capture filename: {filename}")
    return parts[0] + ":camera" + parts[2]


def main():
    archive, image_receipt = download(
        "https://drive.usercontent.google.com/download?id=1xnK3B6K6KekDI55vwJ0vnc2IGoDga9cj&export=download&confirm=t",
        "deepweeds_images.zip")
    labels, label_receipt = download(
        "https://raw.githubusercontent.com/AlexOlsen/DeepWeeds/master/labels/labels.csv", "deepweeds_labels.csv")
    with labels.open(encoding="utf-8") as stream:
        original = list(csv.DictReader(stream))
    sessions = defaultdict(set)
    for row in original:
        if row["Label"] in SPECIES_SPLITS:
            sessions[session_group(row["Filename"])].add(SPECIES_SPLITS[row["Label"]])
    out = fresh_output("dataset_unknown_deepweeds_v1")
    candidates = []
    excluded = []
    with zipfile.ZipFile(archive) as source:
        for row in original:
            name = row["Filename"]
            label = row["Label"]
            if label not in SPECIES_SPLITS:
                reason = "negative_is_not_nonplant" if label == "8" else "species_reserved_outside_pilot"
                excluded.append({"image": name, "reason": reason})
                continue
            group = session_group(name)
            if len(sessions[group]) != 1:
                excluded.append({"image": name, "reason": "capture_day_camera_crosses_species_splits"})
                continue
            split = SPECIES_SPLITS[label]
            data = source.read(name)
            relative = Path("images") / split / Path(name).name
            target = out / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            candidates.append({"image": relative.as_posix(), "source_id": name, "source": "DeepWeeds",
                               "source_label": row["Species"], "source_label_id": label,
                               "split": split, "capture_group": group,
                               "target": "unsupported_species_scene", "sha256": hashlib.sha256(data).hexdigest()})
    print("Screening species/date split and near duplicates on CPU", flush=True)
    values = [dihedral_hashes(out / row["image"]) for row in candidates]
    if any(value is None for value in values):
        raise ValueError("Unreadable DeepWeeds image")
    hashes = np.array(values, dtype=np.uint64)
    existing = np.load(ROOT / "ml-pipeline/.cache/external/hash_index/current_sources.npy")
    distances = min_distance(hashes, existing, block=8)
    groups = group_duplicates(hashes)
    accepted, screening_excluded = select_groups(candidates, groups, distances)
    excluded.extend(screening_excluded)
    records = [dict(candidates[i], duplicate_group=f"deepweeds:{groups[i]}") for i in accepted]
    for split in ("train", "val", "test"):
        with (out / f"{split}.jsonl").open("w", encoding="utf-8") as stream:
            for row in records:
                if row["split"] == split:
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    write_json(out / "excluded.json", excluded)
    write_json(out / "report.json", {"source": "https://github.com/AlexOlsen/DeepWeeds",
              "license": "CC-BY-4.0 images/annotations; author README distinguishes repository code license",
              "downloads": [image_receipt, label_receipt], "source_rows": len(original),
              "counts": dict(Counter(r["split"] for r in records)),
              "species": {s: sorted({r["source_label"] for r in records if r["split"] == s}) for s in ("train", "val", "test")},
              "excluded": dict(Counter(r["reason"] for r in excluded)),
              "reference_hashes": len(existing),
              "limitations": ["Image-level species labels, no disease labels or leaf boxes.",
                               "Species and camera/date groups held out; location independence not established.",
                               "Low-resolution Australian scenes are not a Belarus phone field test.",
                               "pHash rotation/reflection <=6 screening does not prove independence.",
                               "Only JSONL-listed images are accepted; excluded files remain on disk for traceability.",
                               "Never train on held-out species test before final OOD comparison."]})
    print(out, Counter(r["split"] for r in records), flush=True)


if __name__ == "__main__":
    main()
