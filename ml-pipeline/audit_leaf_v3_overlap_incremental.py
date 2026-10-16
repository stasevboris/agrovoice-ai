"""Extend the completed r2 signature screen without repeating its comparisons.

Reads audited signatures only. Preserves r2 links byte for byte, indexes its
43,735 records, and compares the 15,611 additional PlantVillage originals with
that index and one another. Writes a row-level candidate disposition ledger;
no images, labels, split assignments, model weights or phone files are changed.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path

from prepare_supplemented import near

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "docs/experiments/yolo26s_data_v1"
PREVIOUS = BASE / "candidate_overlap_full_r2"
OUT = BASE / "candidate_overlap_full_r3"
WIDTHS = (13, 13, 13, 13, 12)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bands(value: int):
    offset = 0
    for number, width in enumerate(WIDTHS):
        yield number, (int(value) >> offset) & ((1 << width) - 1)
        offset += width


def key(row):
    return row["source"], row["id"]


def public(row):
    return {k: v for k, v in row.items() if k != "signature"}


def load(source, path, kind, subset):
    if kind == "jsonl":
        with path.open(encoding="utf-8") as handle:
            data = [json.loads(line) for line in handle if line.strip()]
    else:
        data = json.loads(path.read_text(encoding="utf-8"))
        if kind == "inventory":
            data = data["images"]
    rows = []
    for row in data:
        sig = row.get("source_signature", row.get("signature"))
        if sig is None or not all(k in sig for k in ("decoded_hash", "phash", "dhash", "size")):
            raise ValueError("Missing source signature")
        rows.append(dict(source=source, id=row.get("id", row.get("filename")),
                         signature=sig, split=row.get("split"),
                         group=row.get("group_id"), source_class=row.get("source_class"),
                         audit_subset=subset))
    return rows


def main():
    if OUT.exists():
        raise FileExistsError("Preserve completed or failed r3 evidence; use a new revision")
    prior = json.loads((PREVIOUS / "status.json").read_text(encoding="utf-8"))
    if prior["status"] != "completed_overlap_screen_requires_review":
        raise ValueError("r2 is not completed")
    # Hash checks are required before inheriting comparisons from the old run.
    for relative, expected in prior["input_sha256"].items():
        if digest(ROOT / relative) != expected:
            raise ValueError(f"Changed r2 input: {relative}")
    if digest(PREVIOUS / "links.jsonl") != prior["links_sha256"]:
        raise ValueError("Changed r2 links")
    sources = [
        ("PlantVillage", BASE / "plantvillage_full_geometry_r1/records.jsonl", "jsonl", "previous29"),
        ("CucumberZenodo", BASE / "cucumber_review/inventory.json", "inventory", "original_leaf640"),
        ("OLID", BASE / "olid_review_r2_chroma/inventory.json", "inventory", "original1047"),
        ("HistoricalV2", ROOT / "ml-pipeline/dataset_clean_v2/manifest.json", "manifest", "frozen_v2"),
    ]
    extra_path = BASE / "plantvillage_extra_conditions_r1/records.jsonl"
    extra_status = json.loads((extra_path.parent / "status.json").read_text(encoding="utf-8"))
    if extra_status["status"] != "completed_extra_source_audit_not_training_ready":
        raise ValueError("Extra source audit not complete")
    rows = []
    for source, path, kind, subset in sources:
        rows.extend(load(source, path, kind, subset))
    inherited_count = len(rows)
    if inherited_count != prior["counts"]["images"]:
        raise ValueError("r2 record count changed")
    extra = load("PlantVillage", extra_path, "jsonl", "extra9")
    if len(extra) != extra_status["counts"]["originals"]:
        raise ValueError("Extra source count changed")
    rows.extend(extra)
    identities = {key(row): i for i, row in enumerate(rows)}
    if len(identities) != len(rows):
        raise ValueError("Duplicate source-record identity")
    inherited = [json.loads(line) for line in (PREVIOUS / "links.jsonl").read_text(encoding="utf-8").splitlines()]
    if len(inherited) != sum(prior["counts"][k] for k in ("candidate_links", "historical_links", "historical_internal_links")):
        raise ValueError("r2 link count mismatch")
    OUT.mkdir(parents=True, exist_ok=False)
    index, exact = defaultdict(list), defaultdict(list)
    parent = list(range(len(rows)))

    def component(n):
        while parent[n] != n:
            parent[n] = parent[parent[n]]
            n = parent[n]
        return n

    def union(a, b):
        parent[component(a)] = component(b)

    def index_row(i):
        row = rows[i]
        for band in bands(row["signature"]["phash"]):
            index[band].append(i)
        exact[row["signature"]["decoded_hash"]].append(i)

    # Replay existing graph edges, not the original image-pair search.
    for link in inherited:
        a, b = identities[key(link["a"])], identities[key(link["b"])]
        if not near(rows[a]["signature"], rows[b]["signature"]):
            raise ValueError("Inherited edge no longer matches its signatures")
        union(a, b)
    for i in range(inherited_count):
        index_row(i)
    counts = dict(records=len(rows), inherited_records=inherited_count,
                  incremental_records=len(extra), incremental_checked=0,
                  inherited_links=len(inherited), additional_links=0)
    state = dict(status="running", pid=os.getpid(), gpu=False, training_accepted=0,
                 script_sha256=digest(Path(__file__)),
                 near_helper_sha256=digest(ROOT / "ml-pipeline/prepare_supplemented.py"),
                 inputs={str(path.relative_to(ROOT)): digest(path) for _, path, _, _ in sources},
                 previous_links_sha256=prior["links_sha256"], counts=counts,
                 algorithm="Inherit r2 edges; five pHash bands with exact near() verification only for extra9",
                 limitations=["Matches do not establish physical plant identity.",
                              "Rotations, crops and opposite leaf sides may be missed.",
                              "This audit does not accept labels, annotations or a split."])
    state["inputs"][str(extra_path.relative_to(ROOT))] = digest(extra_path)

    def save():
        state["updated_utc"] = datetime.now(timezone.utc).isoformat()
        tmp = OUT / "status.tmp"
        tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(OUT / "status.json")

    save()
    try:
        # Exact inherited bytes remain identifiable independently of delta links.
        (OUT / "inherited_links.jsonl").write_bytes((PREVIOUS / "links.jsonl").read_bytes())
        delta = []
        with (OUT / "additional_links.jsonl").open("w", encoding="utf-8") as output:
            for i in range(inherited_count, len(rows)):
                a = rows[i]
                sig = a["signature"]
                candidate_indices = set(exact[sig["decoded_hash"]])
                for band in bands(sig["phash"]):
                    candidate_indices.update(index[band])
                for j in sorted(candidate_indices):
                    b = rows[j]
                    if not near(sig, b["signature"]):
                        continue
                    union(i, j)
                    link = dict(a=public(a), b=public(b),
                                exact_decoded=sig["decoded_hash"] == b["signature"]["decoded_hash"],
                                phash_distance=(sig["phash"] ^ b["signature"]["phash"]).bit_count(),
                                dhash_distance=(sig["dhash"] ^ b["signature"]["dhash"]).bit_count())
                    delta.append(link)
                    output.write(json.dumps(link, ensure_ascii=False) + "\n")
                index_row(i)
                counts["incremental_checked"] += 1
                counts["additional_links"] = len(delta)
                if counts["incremental_checked"] % 512 == 0:
                    output.flush()
                    save()
        all_links = inherited + delta
        hist_matches, neighbors = defaultdict(list), defaultdict(list)
        for link in all_links:
            a, b = link["a"], link["b"]
            for first, other in ((a, b), (b, a)):
                if first["source"] == "HistoricalV2":
                    continue
                target = hist_matches if other["source"] == "HistoricalV2" else neighbors
                target[key(first)].append(dict(other=other, exact_decoded=link["exact_decoded"],
                                              phash_distance=link["phash_distance"],
                                              dhash_distance=link["dhash_distance"]))
        exact_groups = defaultdict(list)
        for row in rows:
            if row["source"] != "HistoricalV2":
                exact_groups[row["signature"]["decoded_hash"]].append(row)
        # Raw category disagreements from one publisher require source resolution.
        conflict_ids = set()
        conflicts = []
        for decoded_hash, members in exact_groups.items():
            by_source = defaultdict(list)
            for row in members:
                by_source[row["source"]].append(row)
            for source, group in by_source.items():
                if len({row["source_class"] for row in group}) > 1:
                    conflicts.append(dict(source=source, decoded_hash=decoded_hash,
                                          records=[public(row) for row in group],
                                          decision="quarantine_all_conflicting_records_pending_publisher_metadata"))
                    conflict_ids.update(key(row) for row in group)
        decisions = Counter()
        ledger_count = 0
        with (OUT / "candidate_disposition.jsonl").open("w", encoding="utf-8") as output:
            for row in rows:
                if row["source"] == "HistoricalV2":
                    continue
                identity = key(row)
                history = hist_matches.get(identity, [])
                if identity in conflict_ids:
                    decision = "quarantine_conflicting_publisher_labels"
                elif any(link["exact_decoded"] for link in history):
                    decision = "historical_exact_copy_not_a_new_independent_example"
                elif history:
                    decision = "quarantine_possible_historical_overlap_pending_visual_review"
                elif identity in neighbors:
                    decision = "internal_match_pending_dedup_group_and_label_review"
                else:
                    decision = "no_match_in_this_screen_still_pending_semantics_boxes_groups"
                output.write(json.dumps(dict(**public(row), decision=decision,
                                             historical_matches=history,
                                             candidate_matches=neighbors.get(identity, []),
                                             training_accepted=False), ensure_ascii=False) + "\n")
                decisions[decision] += 1
                ledger_count += 1
        (OUT / "label_conflicts.json").write_text(json.dumps(conflicts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        components = defaultdict(list)
        for i, row in enumerate(rows):
            components[component(i)].append(public(row))
        with (OUT / "suggested_groups.jsonl").open("w", encoding="utf-8") as output:
            for group in components.values():
                if len(group) > 1:
                    output.write(json.dumps(group, ensure_ascii=False) + "\n")
        counts.update(candidate_records=ledger_count, label_conflict_groups=len(conflicts),
                      label_conflict_records=len(conflict_ids), dispositions=dict(decisions),
                      suggested_components=len(components),
                      delta_exact_links=sum(link["exact_decoded"] for link in delta),
                      delta_nonexact_links=sum(not link["exact_decoded"] for link in delta))
        assert counts["incremental_checked"] == len(extra)
        assert ledger_count == len(rows) - sum(row["source"] == "HistoricalV2" for row in rows)
        assert digest(OUT / "inherited_links.jsonl") == prior["links_sha256"]
        state["output_sha256"] = {name: digest(OUT / name) for name in (
            "inherited_links.jsonl", "additional_links.jsonl", "candidate_disposition.jsonl",
            "label_conflicts.json", "suggested_groups.jsonl")}
        state["status"] = "completed_all_sources_screen_pending_annotation_and_split_acceptance"
        save()
    except Exception as error:
        state.update(status="failed", error=str(error))
        save()
        raise
    print(json.dumps(state, ensure_ascii=False))


if __name__ == "__main__":
    main()
