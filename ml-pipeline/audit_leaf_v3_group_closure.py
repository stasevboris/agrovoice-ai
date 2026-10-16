"""Record conservative split constraints from publisher groups and overlaps.

Does not create a training split. Keep every known publisher leaf group,
historical provenance group, exact image copy, conservative near-match and
same-second OLID capture together. A connected component is a split constraint,
not an assertion that every member is a verified instance of one plant.
"""
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "docs/experiments/yolo26s_data_v1"
OUT = BASE / "group_closure_r1"
OVERLAP = BASE / "candidate_overlap_full_r3"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def lines(path):
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def main():
    if OUT.exists():
        raise FileExistsError("Preserve existing group audit; use a new revision")
    state = json.loads((OVERLAP / "status.json").read_text(encoding="utf-8"))
    if not state["status"].startswith("completed_"):
        raise ValueError("Complete overlap screen required")
    for relative, digest in state["inputs"].items():
        if sha(ROOT / relative) != digest:
            raise ValueError(f"Overlap input changed: {relative}")
    for name, digest in state["output_sha256"].items():
        if sha(OVERLAP / name) != digest:
            raise ValueError(f"Overlap output changed: {name}")
    leaf_map = ROOT / "ml-pipeline/source_candidates/plantvillage_subset/leaf-map.json"
    publisher_archive = ROOT / "ml-pipeline/source_candidates/yolo26s_data_v1/plantvillage_all_crops/publisher_snapshot_7f7ecc7e1eaca78107e3affe7cb5abd9427e139a.zip"
    with zipfile.ZipFile(publisher_archive) as archive:
        names = [n for n in archive.namelist() if n.endswith("/leaf-map.json")]
        if not names or any(hashlib.sha256(archive.read(n)).hexdigest() != sha(leaf_map) for n in names):
            raise ValueError("Grouping map differs from the pinned publisher archive")
    ledger = lines(OVERLAP / "candidate_disposition.jsonl")
    rows = [dict(source=r["source"], id=r["id"], split=None,
                 decision=r["decision"], publisher_leaf_group=None,
                 historical_group=None, capture_time=None) for r in ledger]
    manifest_path = ROOT / "ml-pipeline/dataset_clean_v2/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    rows.extend(dict(source="HistoricalV2", id=r["filename"], split=r["split"],
                     decision="frozen_historical_record", publisher_leaf_group=None,
                     historical_group=r["group_id"], capture_time=None) for r in manifest)
    identity = {(r["source"], r["id"]): i for i, r in enumerate(rows)}
    if len(identity) != len(rows):
        raise ValueError("Duplicate record identity")
    pv_paths = [BASE / "plantvillage_full_geometry_r1/records.jsonl",
                BASE / "plantvillage_extra_conditions_r1/records.jsonl"]
    for path in pv_paths:
        for r in lines(path):
            rows[identity[("PlantVillage", r["id"])]]['publisher_leaf_group'] = r.get("physical_leaf_group_candidate")
    olid_path = BASE / "olid_original_geometry_r3/records.jsonl"
    for r in lines(olid_path):
        exif = r.get("capture_exif") or {}
        rows[identity[("OLID", r["id"])]]['capture_time'] = exif.get("DateTimeOriginal")
    parent = list(range(len(rows)))
    edge_counts = Counter()

    def find(n):
        while parent[n] != n:
            parent[n] = parent[parent[n]]
            n = parent[n]
        return n

    def join(a, b, reason):
        edge_counts[reason] += 1
        parent[find(a)] = find(b)

    for field, reason in [("publisher_leaf_group", "publisher_leaf_map"),
                          ("historical_group", "frozen_historical_provenance"),
                          ("capture_time", "OLID_same_second_conservative")]:
        first = {}
        for i, r in enumerate(rows):
            value = r.get(field)
            if not value:
                continue
            group_key = (r["source"], value)
            if group_key in first:
                join(i, first[group_key], reason)
            else:
                first[group_key] = i
    for name in ("inherited_links.jsonl", "additional_links.jsonl"):
        for link in lines(OVERLAP / name):
            join(identity[(link["a"]["source"], link["a"]["id"])],
                 identity[(link["b"]["source"], link["b"]["id"])],
                 "decoded_exact_copy" if link["exact_decoded"] else "near_match_conservative")
    components = defaultdict(list)
    for i, row in enumerate(rows):
        components[find(i)].append(row)
    OUT.mkdir(exist_ok=False)
    counts = Counter()
    conflicts = []
    with (OUT / "candidate_split_constraints.jsonl").open("w", encoding="utf-8") as output:
        for component in components.values():
            members = sorted(f"{r['source']}:{r['id']}" for r in component)
            component_id = "split:" + hashlib.sha256("\n".join(members).encode()).hexdigest()
            old_splits = sorted({r["split"] for r in component if r["source"] == "HistoricalV2"})
            label_conflict = any(r["decision"] == "quarantine_conflicting_publisher_labels" for r in component)
            if len(old_splits) > 1:
                conflicts.append(dict(component_id=component_id, historical_splits=old_splits,
                                      members=members, decision="quarantine_from_new_split_until_inherited_overlap_review"))
            for row in component:
                if row["source"] == "HistoricalV2":
                    continue
                if label_conflict:
                    rule = "quarantine_source_label_conflict_component"
                elif len(old_splits) > 1:
                    rule = "quarantine_component_touches_multiple_historical_splits"
                elif old_splits == ["train"]:
                    rule = "training_only_if_other_checks_pass_never_new_independent_holdout"
                elif old_splits:
                    rule = "historical_holdout_family_never_train_or_new_independent_test"
                else:
                    rule = "no_historical_split_restriction_physical_provenance_still_requires_review"
                counts[rule] += 1
                output.write(json.dumps(dict(**row, component_id=component_id,
                                             component_records=len(component), historical_splits=old_splits,
                                             split_rule=rule, assigned_split=None,
                                             training_accepted=False), ensure_ascii=False) + "\n")
    (OUT / "inherited_split_conflicts.json").write_text(json.dumps(conflicts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = dict(status="completed_split_constraints_only_no_training_dataset",
                   updated_utc=datetime.now(timezone.utc).isoformat(), gpu=False, training_accepted=0,
                   script_sha256=sha(Path(__file__)), leaf_map_sha256=sha(leaf_map),
                   leaf_map_matches_pinned_archive=True,
                   source_inputs_sha256={str(p.relative_to(ROOT)): sha(p) for p in pv_paths + [olid_path, manifest_path]},
                   overlap_state_sha256=sha(OVERLAP / "status.json"),
                   counts=dict(records=len(rows), candidate_records=len(ledger), components=len(components),
                               edges_by_reason=dict(edge_counts), candidate_rules=dict(counts),
                               inherited_multi_split_components=len(conflicts)),
                   limitations=["No final train/val/test assignments were made.",
                                "Connected split constraints are not proven plant identities.",
                                "No images or frozen historical split assignments were changed."],
                   output_sha256={name: sha(OUT / name) for name in (
                       "candidate_split_constraints.jsonl", "inherited_split_conflicts.json")})
    assert sum(counts.values()) == len(ledger)
    (OUT / "status.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
