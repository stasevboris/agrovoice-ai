"""Fail-closed validation for future Leaf v3 data; this module never starts training."""
from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[1]
STUDY = ROOT / "docs/experiments/yolo26s_data_v1"
# Deliberately pin the frozen baseline fingerprint. Updating the baseline requires a reviewed validator revision.
PINNED_BASE_MANIFEST_SHA256 = "7306a09a94cfccb1854707d86e45de654a403145dffc59d92d38cbc5b694d5bc"
SPLITS = ("train", "val", "test")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json_sha256(value) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256_bytes(encoded)


def test_manifest_sha256(records) -> str:
    """Stable test seal: canonical JSON over test rows sorted by unique record_id."""
    ordered = sorted(records, key=lambda row: row["record_id"])
    return canonical_json_sha256(ordered)


def is_sha256(value) -> bool:
    return isinstance(value, str) and SHA256_RE.fullmatch(value) is not None


def safe_relative_path(value, base: Path, allowed_root: Path) -> Path:
    """Resolve a manifest path; reject absolute, traversal, and symlink escapes."""
    if not isinstance(value, str) or not value.strip():
        raise ValueError("path must be a non-empty relative string")
    normalized = value.replace("\\", "/")
    posix = PurePosixPath(normalized)
    windows = PureWindowsPath(value)
    if posix.is_absolute() or windows.is_absolute() or windows.drive:
        raise ValueError("absolute or drive-qualified paths are forbidden")
    if any(part in ("", ".", "..") for part in posix.parts):
        raise ValueError("empty, dot, and parent path components are forbidden")
    root = allowed_root.resolve()
    candidate = (base / Path(*posix.parts)).resolve()
    if not candidate.is_relative_to(root):
        raise ValueError("resolved path escapes its approved root")
    return candidate


def load_review_evidence(contract, repo_root: Path, accepted_candidate_keys):
    """Verify pinned review ledgers and index constraints for accepted source candidates only."""
    problems = []
    specs = contract.get("review_evidence")
    required_ids = {
        "annotation_review_queue", "candidate_split_constraints", "candidate_dispositions",
        "plantvillage_previous29_provenance", "plantvillage_extra9_provenance", "olid_source_provenance",
        "cucumber_source_provenance", "legacy_visual_quarantine", "inherited_split_conflicts",
    }
    if not isinstance(specs, list):
        return ["Manifest contract review_evidence must be a list"], {}, set(), set(), None
    by_id = {}
    if not isinstance(contract.get("source_revisions"), dict) or not isinstance(contract.get("source_licenses"), dict):
        return ["Pinned source revisions/licenses must be object mappings"], {}, set(), set(), None
    for spec in specs:
        if not isinstance(spec, dict) or not all(key in spec for key in ("id", "path", "sha256")) or not isinstance(spec.get("id"), str):
            problems.append("Malformed review-evidence reference in contract")
            continue
        if not is_sha256(spec.get("sha256")):
            problems.append(f"Invalid pinned review-evidence SHA-256 for {spec['id']}")
            continue
        if spec["id"] in by_id:
            problems.append(f"Duplicate review-evidence ID: {spec['id']}")
        by_id[spec["id"]] = spec
    if set(by_id) != required_ids:
        problems.append("Contract review_evidence IDs do not match the required pinned audit set")
    evidence_digest = canonical_json_sha256(sorted(
        [{"id": spec.get("id"), "path": spec.get("path"), "sha256": spec.get("sha256")} for spec in specs if isinstance(spec, dict)],
        key=lambda row: str(row["id"]),
    ))
    paths = {}
    for evidence_id, spec in by_id.items():
        try:
            path = safe_relative_path(spec["path"], repo_root, repo_root)
            if not path.is_file():
                problems.append(f"Pinned review evidence is missing: {evidence_id}")
                continue
            if not is_sha256(spec["sha256"]) or sha256_file(path) != spec["sha256"]:
                problems.append(f"Pinned review evidence hash mismatch: {evidence_id}")
                continue
            paths[evidence_id] = path
        except (TypeError, ValueError, OSError, KeyError) as exc:
            problems.append(f"Unsafe review-evidence reference {evidence_id}: {exc}")
    if problems:
        return sorted(set(problems)), {}, set(), set(), evidence_digest

    candidate_index_ids = ("annotation_review_queue", "candidate_split_constraints", "candidate_dispositions")
    candidate_indexes = {name: {} for name in candidate_index_ids}
    candidate_all_keys = {}
    provenance_indexes = {}
    expected_counts = contract.get("review_evidence_expected_counts")
    if not isinstance(expected_counts, dict):
        problems.append("Contract must pin per-ledger record counts")
        expected_counts = {}
    if not isinstance(contract.get("review_evidence_expected_candidate_records"), int) or contract["review_evidence_expected_candidate_records"] < 0:
        problems.append("Contract candidate total must be a non-negative integer")

    def register_row(evidence_id, row, seen, line_number):
        if not isinstance(row, dict) or not isinstance(row.get("source"), str) or not isinstance(row.get("id"), str):
            problems.append(f"Missing source/id in {evidence_id} line {line_number}")
            return
        key = (row["source"], row["id"])
        if key in seen:
            problems.append(f"Duplicate candidate key in {evidence_id}: {key}")
            return
        seen.add(key)
        if key in accepted_candidate_keys:
            if evidence_id in candidate_indexes:
                candidate_indexes[evidence_id][key] = row
            else:
                provenance_indexes.setdefault(evidence_id, {})[key] = row

    for evidence_id, path in paths.items():
        if evidence_id in candidate_indexes or evidence_id in {"plantvillage_previous29_provenance", "plantvillage_extra9_provenance", "olid_source_provenance"}:
            seen = set()
            count = 0
            try:
                with path.open("r", encoding="utf-8-sig") as stream:
                    for line_number, line in enumerate(stream, 1):
                        if not line.strip():
                            continue
                        count += 1
                        try:
                            row = json.loads(line)
                        except json.JSONDecodeError as exc:
                            problems.append(f"Invalid JSONL in {evidence_id} at line {line_number}: {exc}")
                            continue
                        if evidence_id in candidate_indexes:
                            register_row(evidence_id, row, seen, line_number)
                        else:
                            source_name = "PlantVillage" if evidence_id.startswith("plantvillage_") else "OLID"
                            if source_name == "PlantVillage":
                                color = (row.get("source_files") or {}).get("color", {})
                                member = color.get("source_path") or color.get("archive_member")
                                payload = color.get("candidate_path")
                                digest = (row.get("source_sha256") or {}).get("color")
                            else:
                                archive, member = row.get("archive"), row.get("image_member")
                                member = f"{archive}::{member}" if isinstance(archive, str) and isinstance(member, str) else None
                                payload = f"ml-pipeline/source_candidates/yolo26s_data_v1/accepted_payloads/OLID/{row.get('id')}.jpg" if member else None
                                digest = row.get("source_sha256")
                            wrapped = {"source": source_name, "id": row.get("id"), "provenance": row,
                                       "source_member": member, "source_payload_path": payload, "source_sha256": digest}
                            register_row(evidence_id, wrapped, seen, line_number)
                candidate_all_keys[evidence_id] = seen
            except OSError as exc:
                problems.append(f"Unable to read pinned review evidence {evidence_id}: {exc}")
            expected = expected_counts.get(evidence_id)
            if not isinstance(expected, int) or expected < 0:
                problems.append(f"Contract has no valid expected record count for {evidence_id}")
            elif count != expected:
                problems.append(f"{evidence_id} has {count} records; contract expects {expected}")
        elif evidence_id == "cucumber_source_provenance":
            try:
                inventory = read_json(path)
                rows = inventory.get("images") if isinstance(inventory, dict) else None
                if not isinstance(rows, list):
                    problems.append("Cucumber provenance inventory has no images list")
                    rows = []
                for index, row in enumerate(rows):
                    archive_member, image_member = row.get("archive_member"), row.get("image_member")
                    member = f"{archive_member}::{image_member}" if isinstance(archive_member, str) and isinstance(image_member, str) else None
                    payload = f"ml-pipeline/source_candidates/yolo26s_data_v1/accepted_payloads/CucumberZenodo/{row.get('id')}.jpg" if member else None
                    wrapped = {"source": "CucumberZenodo", "id": row.get("id"), "provenance": row,
                               "source_member": member, "source_payload_path": payload, "source_sha256": row.get("source_sha256")}
                    register_row(evidence_id, wrapped, candidate_all_keys.setdefault(evidence_id, set()), index + 1)
                expected = expected_counts.get(evidence_id)
                if not isinstance(expected, int) or len(rows) != expected:
                    problems.append(f"{evidence_id} has {len(rows)} records; contract expects {expected}")
            except (OSError, UnicodeError, json.JSONDecodeError) as exc:
                problems.append(f"Unable to read pinned review evidence {evidence_id}: {exc}")
    key_sets = [candidate_all_keys.get(name, set()) for name in candidate_index_ids]
    expected_candidate_total = contract.get("review_evidence_expected_candidate_records")
    if len(key_sets) == 3 and not (key_sets[0] == key_sets[1] == key_sets[2]):
        problems.append("Pinned candidate inventories disagree")
    if len(key_sets) == 3 and len(key_sets[0]) != expected_candidate_total:
        problems.append("Pinned candidate ledgers do not match the declared candidate total")
    provenance_names = ("plantvillage_previous29_provenance", "plantvillage_extra9_provenance", "olid_source_provenance", "cucumber_source_provenance")
    provenance_keys = set().union(*(candidate_all_keys.get(name, set()) for name in provenance_names))
    disposition_keys = candidate_all_keys.get("candidate_dispositions", set())
    if provenance_keys != disposition_keys:
        problems.append("Source provenance inventories do not exactly cover the candidate disposition inventory")
    if not problems:
        for key in disposition_keys:
            disposition = candidate_indexes["candidate_dispositions"].get(key)
            queue_row = candidate_indexes["annotation_review_queue"].get(key)
            constraint_row = candidate_indexes["candidate_split_constraints"].get(key)
            if not isinstance(queue_row, dict):
                problems.append(f"Candidate missing from annotation review queue: {key}")
                continue
            provenance_name = {"PlantVillage": "plantvillage_previous29_provenance" if queue_row.get("proposed_existing_id") is not None else "plantvillage_extra9_provenance",
                              "OLID": "olid_source_provenance", "CucumberZenodo": "cucumber_source_provenance"}.get(key[0])
            provenance_wrapper = provenance_indexes.get(provenance_name, {}).get(key) if provenance_name else None
            provenance = provenance_wrapper.get("provenance") if isinstance(provenance_wrapper, dict) else None
            if not isinstance(disposition, dict) or not isinstance(queue_row, dict) or not isinstance(constraint_row, dict) or not isinstance(provenance, dict):
                problems.append(f"Candidate lacks a complete provenance join: {key}")
                continue
            if disposition.get("source_class") != queue_row.get("source_class"):
                problems.append(f"Candidate source-class mismatch in review queues: {key}")
            if disposition.get("decision") != queue_row.get("overlap_decision"):
                problems.append(f"Candidate overlap disposition mismatch between pinned ledgers: {key}")
            if constraint_row.get("split_rule") != queue_row.get("split_rule") or constraint_row.get("decision") != queue_row.get("overlap_decision"):
                problems.append(f"Candidate split-constraint rule/disposition mismatch between pinned ledgers: {key}")
            if constraint_row.get("component_id") != queue_row.get("component_id"):
                problems.append(f"Candidate closure component mismatch between pinned ledgers: {key}")
            if constraint_row.get("publisher_leaf_group") != queue_row.get("publisher_leaf_group"):
                problems.append(f"Candidate publisher leaf-group mismatch between pinned ledgers: {key}")
            provenance_class = provenance.get("source_class")
            if provenance_class is None and isinstance(provenance.get("source_files"), dict):
                color_meta = provenance["source_files"].get("color", {})
                provenance_class = color_meta.get("category")
            elif key[0] == "CucumberZenodo" and isinstance(provenance.get("source_class"), str):
                provenance_class = "cucumber__" + provenance["source_class"]
            if provenance_class != queue_row.get("source_class"):
                problems.append(f"Candidate source-class mismatch against source provenance: {key}")
            source_hash = queue_row.get("source_sha256")
            if isinstance(source_hash, dict):
                source_hash = source_hash.get("color")
            provenance_hash = provenance.get("source_sha256")
            if isinstance(provenance_hash, dict):
                provenance_hash = provenance_hash.get("color")
            elif key[0] == "CucumberZenodo":
                source_signature = provenance.get("source_signature", {})
                provenance_hash = source_signature.get("source_sha256") if isinstance(source_signature, dict) else None
                if provenance_hash is None:
                    provenance_hash = provenance.get("source_sha256")
            if source_hash != provenance_hash or not is_sha256(source_hash):
                problems.append(f"Candidate original-image SHA mismatch across provenance ledgers: {key}")
            expected_source_member = provenance_wrapper.get("source_member")
            expected_payload_path = provenance_wrapper.get("source_payload_path")
            if not isinstance(expected_source_member, str) or not expected_source_member:
                problems.append(f"Candidate lacks original member path: {key}")
            if not isinstance(expected_payload_path, str) or not expected_payload_path:
                problems.append(f"Candidate lacks safe payload reference: {key}")
            if key[0] == "PlantVillage":
                try:
                    source_member_path = PurePosixPath(expected_source_member)
                    if source_member_path.is_absolute() or ".." in source_member_path.parts or PureWindowsPath(expected_source_member).drive:
                        problems.append(f"Unsafe PlantVillage publisher member path: {key}")
                    safe_relative_path(expected_payload_path, repo_root, repo_root / "ml-pipeline/source_candidates")
                except (TypeError, ValueError, OSError) as exc:
                    problems.append(f"Unsafe PlantVillage provenance path: {key}: {exc}")
            elif key[0] in {"OLID", "CucumberZenodo"}:
                member_path = PurePosixPath(expected_source_member.split("::", 1)[-1]) if "::" in expected_source_member else PurePosixPath("..")
                if "::" not in expected_source_member or member_path.is_absolute() or ".." in member_path.parts or PureWindowsPath(expected_source_member).drive:
                    problems.append(f"Unsafe archive member path for candidate {key}")
                try:
                    archive_payload = safe_relative_path(expected_payload_path.split("::", 1)[0], repo_root, repo_root / "ml-pipeline/source_candidates")
                    if not archive_payload.is_file():
                        problems.append(f"Original source archive missing for candidate {key}")
                except (TypeError, ValueError, OSError, IndexError) as exc:
                    problems.append(f"Unsafe candidate source archive path for {key}: {exc}")
    if problems:
        return sorted(set(problems)), {}, set(), set(), evidence_digest

    candidates = {}
    for key in accepted_candidate_keys:
        queue_row = candidate_indexes["annotation_review_queue"].get(key)
        constraint_row = candidate_indexes["candidate_split_constraints"].get(key)
        disposition_row = candidate_indexes["candidate_dispositions"].get(key)
        if not queue_row or not constraint_row or not disposition_row:
            problems.append(f"Accepted candidate is absent from one or more pinned ledgers: {key}")
            continue
        if queue_row.get("source_class") != disposition_row.get("source_class"):
            problems.append(f"Pinned source class disagrees for candidate {key}")
        component_id = constraint_row.get("component_id")
        if not isinstance(component_id, str) or not component_id:
            problems.append(f"Missing/invalid component ID for candidate {key}")
        candidate_hash = queue_row.get("source_sha256")
        if isinstance(candidate_hash, dict):
            candidate_hash = candidate_hash.get("color")
        provenance_ledger = {
            "PlantVillage": "plantvillage_previous29_provenance" if queue_row.get("proposed_existing_id") is not None else "plantvillage_extra9_provenance",
            "OLID": "olid_source_provenance",
            "CucumberZenodo": "cucumber_source_provenance",
        }.get(key[0])
        provenance_wrapper = provenance_indexes.get(provenance_ledger, {}).get(key) if provenance_ledger else None
        provenance = provenance_wrapper.get("provenance") if isinstance(provenance_wrapper, dict) else None
        if not isinstance(provenance, dict):
            problems.append(f"Candidate {key} lacks pinned original-member provenance")
            provenance = {}
        source_revision = contract.get("source_revisions", {}).get(key[0])
        source_license = contract.get("source_licenses", {}).get(key[0])
        provenance_member = provenance_wrapper.get("source_member")
        provenance_payload_path = provenance_wrapper.get("source_payload_path")
        provenance_hash = provenance_wrapper.get("source_sha256")
        if not is_sha256(candidate_hash) or candidate_hash != provenance_hash:
            problems.append(f"Candidate {key} original SHA differs between queue and source provenance")
        if not isinstance(provenance_member, str) or not provenance_member:
            problems.append(f"Candidate {key} lacks immutable original source member")
        if not isinstance(source_revision, str) or not source_revision:
            problems.append(f"Candidate {key} lacks pinned source revision")
        if not isinstance(source_license, str) or not source_license:
            problems.append(f"Candidate {key} lacks pinned source license")
        if not isinstance(provenance_payload_path, str) or not provenance_payload_path:
            problems.append(f"Candidate {key} lacks staged original payload path")
        elif key[0] == "PlantVillage":
            try:
                staged_source = safe_relative_path(provenance_payload_path, repo_root, repo_root / "ml-pipeline/source_candidates")
                if not staged_source.is_file() or sha256_file(staged_source) != candidate_hash:
                    problems.append(f"Candidate {key} staged source bytes do not match pinned SHA")
            except (ValueError, OSError) as exc:
                problems.append(f"Candidate {key} staged source path is unsafe: {exc}")
        provenance_class = provenance.get("source_class")
        if provenance_class is not None and provenance_class != queue_row.get("source_class"):
            problems.append(f"Source provenance class mismatch for candidate {key}")
        historical_splits = constraint_row.get("historical_splits", [])
        if not isinstance(historical_splits, list):
            problems.append(f"Candidate {key} has malformed historical_splits")
            historical_splits = []
        candidates[key] = {
            "source_class": queue_row.get("source_class"),
            "source_sha256": candidate_hash,
            "source_revision": source_revision,
            "source_member": provenance_member,
            "source_payload_path": provenance_payload_path,
            "source_license": source_license,
            "component_id": component_id,
            "publisher_leaf_group": constraint_row.get("publisher_leaf_group"),
            "split_rule": constraint_row.get("split_rule"),
            "historical_splits": historical_splits,
            "overlap_decision": disposition_row.get("decision"),
        }

    try:
        visual = read_json(paths["legacy_visual_quarantine"])
        conflicts = read_json(paths["inherited_split_conflicts"])
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        return [f"Unable to read pinned legacy review evidence: {exc}"], {}, set(), set(), evidence_digest
    if (not isinstance(visual, dict)
            or not isinstance(visual.get("quarantine_pending_annotation_review_for_new_base_only"), list)
            or any(not isinstance(name, str) for name in visual.get("quarantine_pending_annotation_review_for_new_base_only", []))):
        problems.append("Malformed pinned legacy visual-quarantine evidence")
        quarantined = set()
    else:
        quarantined = set(visual["quarantine_pending_annotation_review_for_new_base_only"])
    if not isinstance(conflicts, list):
        problems.append("Malformed pinned inherited split-conflicts evidence")
        conflict_names = set()
    else:
        conflict_names = set()
        for component in conflicts:
            if not isinstance(component, dict) or not isinstance(component.get("members"), list):
                problems.append("Malformed inherited split-conflict component")
                continue
            for member in component["members"]:
                if isinstance(member, str) and member.startswith("HistoricalV2:"):
                    conflict_names.add(member.split(":", 1)[1])
    if problems:
        return sorted(set(problems)), {}, quarantined, conflict_names, evidence_digest
    return [], candidates, quarantined, conflict_names, evidence_digest


def validate_candidate_acceptances(accepted_records, candidate_evidence, repo_root: Path = ROOT):
    problems = []
    known_rules = {
        "no_historical_split_restriction_physical_provenance_still_requires_review",
        "training_only_if_other_checks_pass_never_new_independent_holdout",
        "historical_holdout_family_never_train_or_new_independent_test",
        "quarantine_source_label_conflict_component",
    }
    for record_id, row in accepted_records.items():
        if row.get("origin_kind") != "new_source_record":
            continue
        if not isinstance(row.get("candidate_source"), str) or not isinstance(row.get("candidate_id"), str):
            problems.append(f"Accepted source record {record_id} lacks string candidate identity")
            continue
        key = (row["candidate_source"], row["candidate_id"])
        audit = candidate_evidence.get(key)
        if audit is None:
            problems.append(f"Accepted source record {record_id} is absent from pinned candidate audits")
            continue
        if row.get("source_id") != key[0] or row.get("source_class") != audit.get("source_class"):
            problems.append(f"Accepted source record {record_id} disagrees with pinned candidate identity/class")
        if row.get("source_sha256") != audit.get("source_sha256"):
            problems.append(f"Accepted source record {record_id} does not match the pinned original-image hash")
        if row.get("source_member") != audit.get("source_member") or row.get("source_revision") != audit.get("source_revision"):
            problems.append(f"Accepted source record {record_id} does not match pinned source member/revision")
        if row.get("source_payload_path") != audit.get("source_payload_path") or row.get("license_reference") != audit.get("source_license"):
            problems.append(f"Accepted source record {record_id} payload path/license differs from pinned provenance")
        payload_path = audit.get("source_payload_path")
        source_sha = audit.get("source_sha256")
        try:
            payload = safe_relative_path(payload_path, repo_root, repo_root / "ml-pipeline/source_candidates")
            if key[0] != "PlantVillage" and not payload.is_relative_to((repo_root / "ml-pipeline/source_candidates/yolo26s_data_v1/accepted_payloads").resolve()):
                problems.append(f"Accepted payload path is outside the controlled staging area for {record_id}")
            if not payload.is_file() or sha256_file(payload) != source_sha:
                problems.append(f"Accepted staged source file SHA mismatch for {record_id}")
        except (KeyError, TypeError, ValueError, OSError) as exc:
            problems.append(f"Unable to verify accepted original payload for {record_id}: {exc}")
        if row.get("group_id") != audit.get("component_id"):
            problems.append(f"Accepted source record {record_id} does not use the pinned closure component ID")
        if row.get("group_review_status") != "source_group_reviewed":
            problems.append(f"Accepted source record {record_id} lacks explicit source-group review")
        rule = audit.get("split_rule")
        overlap_decision = audit.get("overlap_decision")
        if rule not in known_rules:
            problems.append(f"Candidate {key} has an unknown pinned split rule")
            continue
        if overlap_decision != "no_match_in_this_screen_still_pending_semantics_boxes_groups":
            problems.append(f"Candidate {key} is duplicate/conflict/pending in the pinned overlap disposition")
            continue
        splits = row.get("split_eligibility", [])
        if rule == "training_only_if_other_checks_pass_never_new_independent_holdout" and set(splits) != {"train"}:
            problems.append(f"Candidate {key} is train-only under historical group constraints")
        elif rule == "historical_holdout_family_never_train_or_new_independent_test" and set(splits) != {"val"}:
            problems.append(f"Candidate {key} linked to historical holdout may only be declared as linked validation")
        elif rule == "quarantine_source_label_conflict_component":
            problems.append(f"Candidate {key} belongs to a quarantined conflict component")
        if row.get("source_id") == "PlantVillage":
            publisher_group = audit.get("publisher_leaf_group")
            expected_prefix = f"pv:{audit.get('source_class')}:::"
            if not isinstance(publisher_group, str) or not publisher_group.startswith(expected_prefix):
                problems.append(f"PlantVillage candidate {key} class does not match publisher leaf-map group")
    return sorted(set(problems))


def ordered_yaml_names(value):
    """Convert supported YOLO names encodings to an ordered list or raise."""
    if isinstance(value, list):
        names = value
    elif isinstance(value, dict):
        parsed = {}
        for key, name in value.items():
            try:
                index = int(key)
            except (TypeError, ValueError) as exc:
                raise ValueError("data.yaml names keys must be integer class IDs") from exc
            if index in parsed:
                raise ValueError("data.yaml contains duplicate class IDs")
            parsed[index] = name
        if set(parsed) != set(range(len(parsed))):
            raise ValueError("data.yaml class IDs must be contiguous from zero")
        names = [parsed[index] for index in range(len(parsed))]
    else:
        raise ValueError("data.yaml names must be a list or integer-keyed mapping")
    if any(not isinstance(name, str) or not name for name in names) or len(names) != len(set(names)):
        raise ValueError("data.yaml class names must be non-empty and unique")
    return names


def validate_class_map(class_map, frozen_names, frozen_manifest_sha):
    problems = []
    if not isinstance(class_map, dict) or class_map.get("schema_version") != 1:
        return ["Class map is missing or has an unsupported schema version"], []
    if class_map.get("dataset_id") != "agrovoice-leaf-v3":
        problems.append("Class map dataset_id must be agrovoice-leaf-v3")
    if class_map.get("base_dataset_id") != "dataset_clean_v2":
        problems.append("Class map base_dataset_id must be dataset_clean_v2")
    if class_map.get("status") != "locked_before_training":
        problems.append("Class map is not locked before training")
    legacy = class_map.get("legacy_classes")
    if not isinstance(legacy, list):
        return problems + ["Class map legacy_classes must be a list"], []
    if any(not isinstance(row, dict) for row in legacy):
        return problems + ["Malformed legacy class-map entries"], []
    try:
        actual_legacy = [row["name"] for row in legacy]
        legacy_ids = [row["id"] for row in legacy]
    except (TypeError, KeyError):
        return problems + ["Malformed legacy class-map entries"], []
    if len(frozen_names) < 30 or frozen_names[29] != "Cucumber leaf (condition unknown)":
        return ["Frozen clean_v2 class schema is unexpected"], []
    expected = frozen_names[:29]
    legacy_statuses = [row.get("status") for row in legacy]
    if legacy_ids != list(range(29)) or any(type(value) is not int for value in legacy_ids) or actual_legacy != expected or legacy_statuses != ["preserved_legacy_id_name"] * 29:
        problems.append("Legacy IDs 0-28 and names must exactly match frozen clean_v2")
    if frozen_manifest_sha != PINNED_BASE_MANIFEST_SHA256:
        problems.append("Frozen clean_v2 manifest differs from the pinned baseline fingerprint")
    if class_map.get("base_dataset_manifest_sha256") != PINNED_BASE_MANIFEST_SHA256:
        problems.append("Class map does not bind to the pinned frozen clean_v2 fingerprint")

    migration = class_map.get("legacy_classes_not_carried_into_new_head")
    if isinstance(migration, list) and any(not isinstance(row, dict) for row in migration):
        return problems + ["Malformed legacy cucumber migration policy"], []
    if not isinstance(migration, list) or not any(
        isinstance(row, dict)
        and row.get("legacy_id") == 29
        and row.get("name") == frozen_names[29]
        and isinstance(row.get("rule"), str)
        and "Do not automatically map to healthy" in row.get("rule", "")
        for row in migration
    ):
        problems.append("Class map must explicitly prohibit automatic healthy inference for legacy cucumber ID 29")

    new_conditions = class_map.get("new_conditions")
    if not isinstance(new_conditions, list):
        return problems + ["new_conditions must be a list"], []
    names = actual_legacy[:]
    next_id = 29
    for row in new_conditions:
        if not isinstance(row, dict):
            problems.append("Malformed new-condition class-map entry")
            continue
        if type(row.get("id")) is not int:
            problems.append("New condition class IDs must be integers")
            continue
        if row.get("id") != next_id:
            problems.append("New condition IDs must be contiguous starting at 29")
        name = row.get("name")
        if not isinstance(name, str) or not name or name in names:
            problems.append("New condition names must be non-empty and unique")
        if row.get("status") != "source_semantics_accepted":
            problems.append(f"New class {name!r} lacks source-backed semantic acceptance")
        refs = row.get("evidence_references")
        if not isinstance(refs, list) or not refs:
            problems.append(f"New class {name!r} lacks semantic evidence references")
        else:
            for reference in refs:
                if not isinstance(reference, str) or not reference.strip():
                    problems.append(f"New class {name!r} has an invalid semantic evidence reference")
        for field in ("minimum_train_groups", "minimum_val_groups", "minimum_test_groups"):
            if not isinstance(row.get(field), int) or row[field] < 1:
                problems.append(f"New class {name!r} must declare a positive {field}")
        names.append(name)
        next_id += 1
    if class_map.get("next_available_id") != next_id:
        problems.append("Class map next_available_id is inconsistent")
    return sorted(set(problems)), names


def validate_class_evidence(class_map, repo_root: Path):
    problems = []
    for row in class_map.get("new_conditions", []):
        if not isinstance(row, dict):
            continue
        for reference in row.get("evidence_references", []):
            try:
                evidence = safe_relative_path(reference, repo_root, repo_root)
                if not evidence.is_file():
                    problems.append(f"New class semantic evidence is missing: {reference}")
            except (TypeError, ValueError, OSError) as exc:
                problems.append(f"Unsafe new-class semantic evidence reference: {exc}")
    return sorted(set(problems))


def validate_acceptance_registry(registry, classes, repo_root: Path, study_dir: Path):
    problems = []
    reviewed_annotations = {}
    if not isinstance(registry, dict) or registry.get("schema_version") != 1:
        return ["Acceptance registry is missing or has an unsupported schema version"], {}, {}
    if registry.get("dataset_id") != "agrovoice-leaf-v3":
        problems.append("Acceptance registry dataset_id must be agrovoice-leaf-v3")
    locked = registry.get("status") == "locked_before_training"
    if not locked:
        problems.append("Acceptance registry is not locked before training")
    records = registry.get("records")
    if not isinstance(records, list):
        return problems + ["Acceptance registry records must be a list"], {}, {}
    by_id = {}
    allowed_decisions = {"pending", "accepted", "excluded", "quarantined"}
    accepted_required = {
        "record_id", "origin_kind", "source_id", "source_revision", "source_member", "source_payload_path", "source_sha256",
        "license_reference", "source_class", "annotation_kind", "annotation_sha256", "reviewed_annotation_path", "geometry_status",
        "semantics_status", "group_id", "split_eligibility", "decision", "class_ids", "decision_reason",
        "reviewer_id", "reviewed_utc", "evidence_references",
    }
    identity_fields = {"record_id", "origin_kind", "source_id", "source_revision", "source_member", "source_payload_path", "source_sha256", "source_class"}
    for row in records:
        if not isinstance(row, dict):
            problems.append("Acceptance registry contains a non-object record")
            continue
        missing_identity = identity_fields - set(row)
        if missing_identity:
            problems.append("Acceptance decision missing provenance fields: " + ",".join(sorted(missing_identity)))
            continue
        record_id, decision = row.get("record_id"), row.get("decision")
        if not isinstance(record_id, str) or not record_id or record_id in by_id:
            problems.append("Acceptance record IDs must be non-empty and unique")
            continue
        by_id[record_id] = row
        for field in ("source_id", "source_revision", "source_member", "source_payload_path", "source_class"):
            if not isinstance(row[field], str) or not row[field].strip():
                problems.append(f"Acceptance decision {record_id} has invalid {field}")
        if not is_sha256(row["source_sha256"]):
            problems.append(f"Acceptance decision {record_id} has invalid source SHA-256")
        member_path = PurePosixPath(row["source_member"].replace("\\", "/"))
        if member_path.is_absolute() or ".." in member_path.parts or PureWindowsPath(row["source_member"]).drive:
            problems.append(f"Acceptance decision {record_id} has unsafe source_member")
        if not isinstance(decision, str) or decision not in allowed_decisions:
            problems.append(f"Acceptance record {record_id} has an invalid decision")
            continue
        if decision == "pending":
            if locked:
                problems.append(f"Locked acceptance registry still has pending record {record_id}")
            continue
        if decision in {"excluded", "quarantined"}:
            for field in ("decision_reason", "reviewer_id", "reviewed_utc", "evidence_references"):
                if field not in row:
                    problems.append(f"Final non-accepted record {record_id} lacks {field}")
            if not isinstance(row.get("decision_reason"), str) or not row.get("decision_reason", "").strip():
                problems.append(f"Final non-accepted record {record_id} lacks a decision reason")
            if not isinstance(row.get("reviewer_id"), str) or not row.get("reviewer_id", "").strip():
                problems.append(f"Final non-accepted record {record_id} lacks reviewer_id")
            try:
                datetime.fromisoformat(row["reviewed_utc"].replace("Z", "+00:00"))
            except (KeyError, AttributeError, ValueError):
                problems.append(f"Final non-accepted record {record_id} has an invalid reviewed_utc timestamp")
            refs = row.get("evidence_references")
            if not isinstance(refs, list) or not refs:
                problems.append(f"Final non-accepted record {record_id} lacks evidence references")
            else:
                for reference in refs:
                    try:
                        evidence = safe_relative_path(reference, repo_root, repo_root)
                        if not evidence.is_file():
                            problems.append(f"Decision evidence does not exist for {record_id}: {reference}")
                    except (TypeError, ValueError, OSError) as exc:
                        problems.append(f"Unsafe decision evidence reference for {record_id}: {exc}")
            continue

        missing = accepted_required - set(row)
        if missing:
            problems.append("Accepted record missing fields: " + ",".join(sorted(missing)))
            continue
        if not isinstance(row["origin_kind"], str) or row["origin_kind"] not in {"inherited_train_record", "new_source_record"}:
            problems.append(f"Accepted record {record_id} has an invalid origin_kind")
        if row["origin_kind"] == "inherited_train_record" and (not isinstance(row.get("legacy_record_ref"), str) or not row["legacy_record_ref"].strip()):
            problems.append(f"Inherited accepted record {record_id} lacks its frozen manifest reference")
        if row["origin_kind"] == "new_source_record":
            if not isinstance(row.get("candidate_source"), str) or not row["candidate_source"].strip() or not isinstance(row.get("candidate_id"), str) or not row["candidate_id"].strip():
                problems.append(f"New-source accepted record {record_id} lacks pinned candidate identity")
            if row.get("group_review_status") != "source_group_reviewed":
                problems.append(f"New-source accepted record {record_id} lacks explicit group review")
        if not is_sha256(row["source_sha256"]) or not is_sha256(row["annotation_sha256"]):
            problems.append(f"Accepted record {record_id} has an invalid source/annotation SHA-256")
        try:
            source_payload = safe_relative_path(row["source_payload_path"], repo_root, repo_root / "ml-pipeline")
            if row["origin_kind"] == "new_source_record":
                source_root = (repo_root / "ml-pipeline/source_candidates").resolve()
                if not source_payload.is_relative_to(source_root):
                    problems.append(f"New-source original payload is outside source_candidates for {record_id}")
            else:
                expected_legacy = f"ml-pipeline/dataset_clean_v2/images/{row.get('legacy_record_ref')}"
                if row["source_payload_path"].replace("\\", "/") != expected_legacy:
                    problems.append(f"Inherited payload path is not the frozen train image for {record_id}")
            if not source_payload.is_file() or sha256_file(source_payload) != row["source_sha256"]:
                problems.append(f"Original source bytes/hash mismatch for {record_id}")
        except (KeyError, TypeError, ValueError, OSError) as exc:
            problems.append(f"Unsafe/missing original payload for {record_id}: {exc}")
        try:
            review_path = safe_relative_path(row["reviewed_annotation_path"], repo_root, study_dir / "accepted_annotations")
            if not review_path.is_file():
                problems.append(f"Reviewed annotation artifact is missing for {record_id}")
            elif sha256_file(review_path) != row["annotation_sha256"]:
                problems.append(f"Reviewed annotation artifact SHA mismatch for {record_id}")
            else:
                annotation = read_json(review_path)
                if not isinstance(annotation, dict) or annotation.get("schema_version") != 1:
                    problems.append(f"Malformed reviewed annotation artifact for {record_id}")
                else:
                    if annotation.get("record_id") != record_id or annotation.get("source_sha256") != row["source_sha256"]:
                        problems.append(f"Reviewed annotation identity/source hash mismatch for {record_id}")
                    if annotation.get("class_ids") != row["class_ids"]:
                        problems.append(f"Reviewed annotation class IDs mismatch for {record_id}")
                    if annotation.get("geometry_status") != "whole_visible_leaf_accepted" or annotation.get("semantics_status") != "source_semantics_accepted":
                        problems.append(f"Reviewed annotation lacks accepted geometry/semantics for {record_id}")
                    if annotation.get("reviewer_id") != row.get("reviewer_id") or annotation.get("reviewed_utc") != row.get("reviewed_utc"):
                        problems.append(f"Reviewed annotation reviewer/timestamp mismatch for {record_id}")
                    if annotation.get("evidence_references") != row.get("evidence_references"):
                        problems.append(f"Reviewed annotation evidence references mismatch for {record_id}")
                    boxes_xywh = annotation.get("boxes_xywh")
                    if not isinstance(boxes_xywh, list) or not boxes_xywh:
                        problems.append(f"Reviewed annotation has no boxes for {record_id}")
                    else:
                        for box in boxes_xywh:
                            if (not isinstance(box, list) or len(box) != 5
                                    or any(not isinstance(value, (int, float)) or not math.isfinite(value) for value in box)
                                    or not float(box[0]).is_integer() or int(box[0]) not in row["class_ids"]):
                                problems.append(f"Reviewed annotation has invalid box rows for {record_id}")
                                break
                    reviewed_annotations[record_id] = annotation
        except (KeyError, TypeError, ValueError, OSError, json.JSONDecodeError) as exc:
            problems.append(f"Unable to verify reviewed annotation for {record_id}: {exc}")
        member = row["source_member"]
        if not isinstance(member, str) or not member or PurePosixPath(member.replace("\\", "/")).is_absolute() or ".." in PurePosixPath(member.replace("\\", "/")).parts or PureWindowsPath(member).drive:
            problems.append(f"Accepted record {record_id} has an unsafe source_member")
        if row["geometry_status"] != "whole_visible_leaf_accepted":
            problems.append(f"Accepted record {record_id} lacks whole-visible-leaf geometry approval")
        if row["semantics_status"] != "source_semantics_accepted":
            problems.append(f"Accepted record {record_id} lacks source-backed semantic approval")
        if not isinstance(row["group_id"], str) or not row["group_id"].strip():
            problems.append(f"Accepted record {record_id} lacks group provenance")
        splits = row["split_eligibility"]
        if not isinstance(splits, list) or not splits or any(split not in SPLITS for split in splits):
            problems.append(f"Accepted record {record_id} has invalid split_eligibility")
        class_ids = row["class_ids"]
        if (not isinstance(class_ids, list) or not class_ids
                or any(not isinstance(class_id, int) or class_id < 0 or class_id >= len(classes) for class_id in class_ids)
                or len(class_ids) != len(set(class_ids))):
            problems.append(f"Accepted record {record_id} has invalid class_ids")
        for field in ("source_id", "source_revision", "license_reference", "source_class", "annotation_kind", "decision_reason", "reviewer_id"):
            if not isinstance(row[field], str) or not row[field].strip():
                problems.append(f"Accepted record {record_id} lacks {field}")
        if row["origin_kind"] == "new_source_record":
            expected_revision = registry.get("source_revisions", {}).get(row.get("source_id"))
            expected_license = registry.get("source_licenses", {}).get(row.get("source_id"))
            if row.get("source_revision") != expected_revision:
                problems.append(f"Accepted record {record_id} source revision is not pinned")
            if row.get("license_reference") != expected_license:
                problems.append(f"Accepted record {record_id} license reference is not pinned")
        refs = row["evidence_references"]
        if not isinstance(refs, list) or not refs:
            problems.append(f"Accepted record {record_id} lacks evidence references")
        else:
            for reference in refs:
                try:
                    evidence = safe_relative_path(reference, repo_root, repo_root)
                    if not evidence.is_file():
                        problems.append(f"Acceptance evidence does not exist for {record_id}: {reference}")
                except (TypeError, ValueError, OSError) as exc:
                    problems.append(f"Unsafe acceptance evidence reference for {record_id}: {exc}")
        try:
            datetime.fromisoformat(row["reviewed_utc"].replace("Z", "+00:00"))
        except (AttributeError, ValueError):
            problems.append(f"Accepted record {record_id} has an invalid reviewed_utc timestamp")
    return sorted(set(problems)), by_id, reviewed_annotations


def frozen_base_records(repo_root: Path):
    """Index frozen clean_v2 label semantics without modifying its images, labels, or manifest."""
    base = repo_root / "ml-pipeline/dataset_clean_v2"
    manifest = read_json(base / "manifest.json")
    classes = read_json(base / "classes.json")
    if not isinstance(manifest, list) or not isinstance(classes, list) or len(classes) != 30:
        raise ValueError("Frozen clean_v2 manifest/class schema is malformed")
    all_records, class29_records = {}, {}
    allowed_splits = {"train", "val", "test", "val_aux"}
    for row in manifest:
        if not isinstance(row, dict):
            raise ValueError("Frozen clean_v2 manifest contains a malformed row")
        split, filename = row.get("split"), row.get("filename")
        if split not in allowed_splits or not isinstance(filename, str):
            raise ValueError("Frozen clean_v2 record has an invalid split/filename")
        ref = f"{split}/{filename}"
        if ref in all_records:
            raise ValueError(f"Duplicate frozen clean_v2 record reference: {ref}")
        image = safe_relative_path(f"images/{ref}", base, base)
        label = safe_relative_path(f"labels/{split}/{Path(filename).stem}.txt", base, base)
        if not image.is_file() or not label.is_file():
            raise ValueError(f"Frozen clean_v2 image/label missing: {ref}")
        source_sha, annotation_sha = row.get("prepared_sha256"), row.get("label_sha256")
        if not is_sha256(source_sha) or not is_sha256(annotation_sha):
            raise ValueError(f"Frozen clean_v2 hashes missing: {ref}")
        if sha256_file(label) != annotation_sha:
            raise ValueError(f"Frozen clean_v2 label bytes changed: {ref}")
        label_classes, label_rows, label_issues = parse_label(label, len(classes))
        if label_issues:
            raise ValueError(f"Frozen clean_v2 label is malformed: {ref}: {label_issues[0]}")
        boxes = row.get("boxes")
        if not isinstance(boxes, list):
            raise ValueError(f"Frozen clean_v2 manifest boxes are malformed: {ref}")
        try:
            manifest_classes = {int(box[0]) for box in boxes if isinstance(box, list) and len(box) == 5}
        except (TypeError, ValueError, OverflowError) as exc:
            raise ValueError(f"Frozen clean_v2 manifest class IDs are malformed: {ref}") from exc
        if manifest_classes != label_classes:
            raise ValueError(f"Frozen clean_v2 boxes disagree with label-file classes: {ref}")
        item = {
            "legacy_record_ref": ref,
            "source_sha256": source_sha,
            "annotation_sha256": annotation_sha,
            "group_id": row.get("group_id"),
            "historical_split": split,
            "class_ids": sorted(label_classes),
            "source_member": ref,
        }
        all_records[ref] = item
        if 29 in label_classes:
            if sha256_file(image) != source_sha:
                raise ValueError(f"Frozen legacy cucumber image bytes changed: {ref}")
            class29_records[ref] = {**item, "legacy_class_id": 29}
    return all_records, class29_records


def validate_legacy_migrations(registry, old_records, accepted_records, classes, repo_root: Path):
    problems = []
    migrations = registry.get("legacy_migrations")
    if not isinstance(migrations, list):
        return ["Acceptance registry legacy_migrations must be a list"], {}
    decisions = {}
    allowed = {"pending", "migrate", "exclude", "quarantine"}
    common = {"legacy_record_ref", "legacy_class_id", "frozen_source_sha256", "frozen_annotation_sha256", "group_id", "decision",
              "assigned_split", "acceptance_record_id", "final_class_ids", "decision_reason", "reviewer_id", "reviewed_utc", "evidence_references"}
    for row in migrations:
        if not isinstance(row, dict) or common - set(row):
            problems.append("Malformed legacy class-29 migration decision")
            continue
        ref = row.get("legacy_record_ref")
        if not isinstance(ref, str) or ref in decisions:
            problems.append("Legacy migration references must be non-empty and unique")
            continue
        decisions[ref] = row
        if row.get("legacy_class_id") != 29 or not isinstance(row.get("decision"), str) or row.get("decision") not in allowed:
            problems.append(f"Invalid legacy class-29 migration decision: {ref}")
            continue
        original = old_records.get(ref)
        if original is None:
            problems.append(f"Migration decision references no frozen class-29 record: {ref}")
            continue
        if row.get("frozen_source_sha256") != original["source_sha256"]:
            problems.append(f"Legacy migration {ref} mismatches frozen source SHA-256")
        if row.get("frozen_annotation_sha256") != original["annotation_sha256"]:
            problems.append(f"Legacy migration {ref} mismatches frozen annotation SHA-256")
        if row.get("group_id") != original.get("group_id"):
            problems.append(f"Legacy migration {ref} mismatches frozen group_id")
        if row["decision"] == "pending":
            problems.append(f"Legacy cucumber migration remains pending: {ref}")
            continue
        for field in ("decision_reason", "reviewer_id", "reviewed_utc", "evidence_references"):
            if field not in row:
                problems.append(f"Final legacy decision {ref} lacks {field}")
        if not isinstance(row.get("decision_reason"), str) or not row.get("decision_reason", "").strip():
            problems.append(f"Final legacy decision {ref} lacks a reason")
        if not isinstance(row.get("reviewer_id"), str) or not row.get("reviewer_id", "").strip():
            problems.append(f"Final legacy decision {ref} lacks reviewer_id")
        try:
            datetime.fromisoformat(row["reviewed_utc"].replace("Z", "+00:00"))
        except (KeyError, AttributeError, ValueError):
            problems.append(f"Final legacy decision {ref} has an invalid reviewed_utc timestamp")
        evidence_refs = row.get("evidence_references")
        if not isinstance(evidence_refs, list) or not evidence_refs:
            problems.append(f"Final legacy decision {ref} lacks evidence references")
        else:
            for reference in evidence_refs:
                try:
                    evidence = safe_relative_path(reference, repo_root, repo_root)
                    if not evidence.is_file():
                        problems.append(f"Legacy migration evidence does not exist for {ref}: {reference}")
                except (TypeError, ValueError, OSError) as exc:
                    problems.append(f"Unsafe legacy migration evidence for {ref}: {exc}")
        if row["decision"] == "migrate":
            if not is_sha256(row.get("reviewed_annotation_sha256")):
                problems.append(f"Migrated legacy record {ref} lacks reviewed-annotation SHA-256")
            acceptance_id = row.get("acceptance_record_id")
            accepted = accepted_records.get(acceptance_id) if isinstance(acceptance_id, str) else None
            if not isinstance(acceptance_id, str) or accepted is None:
                problems.append(f"Migrated legacy record {ref} lacks an accepted registry record")
                continue
            final_ids = row.get("final_class_ids")
            if (not isinstance(final_ids, list) or not final_ids
                    or any(not isinstance(class_id, int) or class_id < 29 or class_id >= len(classes) for class_id in final_ids)
                    or len(final_ids) != len(set(final_ids))):
                problems.append(f"Migrated legacy record {ref} has invalid appended class IDs")
            if (accepted.get("origin_kind") != "inherited_train_record"
                    or accepted.get("legacy_record_ref") != ref
                    or accepted.get("source_id") != "dataset_clean_v2"
                    or accepted.get("source_member") != ref
                    or accepted.get("source_class") != "Cucumber leaf (condition unknown)"):
                problems.append(f"Migrated legacy record {ref} does not join to its frozen inherited acceptance record")
            for field, migration_field in (("source_sha256", "frozen_source_sha256"), ("annotation_sha256", "reviewed_annotation_sha256"), ("group_id", "group_id"), ("class_ids", "final_class_ids")):
                if accepted.get(field) != row.get(migration_field):
                    problems.append(f"Migrated legacy record {ref} mismatches accepted registry {field}")
            split = row.get("assigned_split")
            if original["historical_split"] != "train" or split != "train":
                problems.append(f"Only frozen train records may be migrated, and only into new train: {ref}")
            if split not in SPLITS or split not in accepted.get("split_eligibility", []):
                problems.append(f"Migrated legacy record {ref} has an invalid/unauthorized split")
        elif row["decision"] in {"exclude", "quarantine"}:
            if row.get("acceptance_record_id") not in (None, "") or row.get("assigned_split") not in (None, "") or row.get("final_class_ids") not in (None, []):
                problems.append(f"Excluded/quarantined legacy record {ref} cannot have a manifest assignment")
    if set(decisions) != set(old_records):
        missing = sorted(set(old_records) - set(decisions))
        extra = sorted(set(decisions) - set(old_records))
        if missing:
            problems.append(f"Legacy class-29 decisions missing for {len(missing)} frozen records")
        if extra:
            problems.append(f"Legacy class-29 decisions contain {len(extra)} unknown references")
    for acceptance_id, accepted in accepted_records.items():
        if accepted.get("origin_kind") == "inherited_train_record" and accepted.get("legacy_record_ref"):
            migration = decisions.get(accepted["legacy_record_ref"])
            if (not isinstance(migration, dict) or migration.get("decision") != "migrate"
                    or migration.get("acceptance_record_id") != acceptance_id):
                problems.append(f"Inherited accepted record {acceptance_id} lacks a matching migration decision")
    if registry.get("status") == "locked_before_training" and any(
        row.get("decision") == "pending" for row in migrations if isinstance(row, dict)
    ):
        problems.append("Locked registry contains pending legacy migration decisions")
    return sorted(set(problems)), decisions


def validate_inherited_records(accepted_records, frozen_records, class29_migrations, quarantine_refs, split_conflict_names, repo_root: Path):
    problems = []
    for acceptance_id, row in accepted_records.items():
        if row.get("origin_kind") != "inherited_train_record":
            continue
        ref = row.get("legacy_record_ref")
        original = frozen_records.get(ref)
        if original is None:
            problems.append(f"Inherited accepted record {acceptance_id} is not in frozen clean_v2")
            continue
        if original["historical_split"] != "train":
            problems.append(f"Inherited record {ref} did not originate in clean_v2 train")
        if ref in quarantine_refs or Path(ref).name in split_conflict_names:
            problems.append(f"Inherited record {ref} is in the pinned new-base quarantine")
        if row.get("source_id") != "dataset_clean_v2" or row.get("source_member") != ref:
            problems.append(f"Inherited record {ref} source identity is not the frozen base reference")
        if row.get("source_sha256") != original["source_sha256"]:
            problems.append(f"Inherited record {ref} source hash does not match frozen clean_v2")
        image_path = safe_relative_path(f"images/{ref}", repo_root / "ml-pipeline/dataset_clean_v2", repo_root / "ml-pipeline/dataset_clean_v2")
        if not image_path.is_file() or sha256_file(image_path) != original["source_sha256"]:
            problems.append(f"Inherited record {ref} bytes do not match the frozen clean_v2 image")
        if row.get("group_id") != original.get("group_id"):
            problems.append(f"Inherited record {ref} group ID does not match frozen clean_v2")
        if Path(ref).name in quarantine_refs or Path(ref).name in split_conflict_names:
            problems.append(f"Inherited record {ref} is in the pinned quarantine/split-conflict set")
        if row.get("split_eligibility") != ["train"]:
            problems.append(f"Inherited record {ref} is only eligible for new train")
        if 29 in original["class_ids"]:
            migration = class29_migrations.get(ref)
            if (not isinstance(migration, dict) or migration.get("decision") != "migrate"
                    or migration.get("acceptance_record_id") != acceptance_id
                    or row.get("class_ids") != migration.get("final_class_ids")):
                problems.append(f"Inherited class-29 record {ref} lacks a matching source-backed migration")
        elif row.get("class_ids") != original["class_ids"]:
            problems.append(f"Inherited record {ref} changed its frozen class IDs")
    return sorted(set(problems))


def parse_label(path: Path, classes_count: int):
    found = set()
    rows = []
    issues = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        parts = line.split()
        if len(parts) != 5:
            issues.append(f"Invalid label row {line_number}: expected five columns")
            continue
        try:
            cls, x, y, w, h = map(float, parts)
        except ValueError:
            issues.append(f"Invalid numeric label row {line_number}")
            continue
        if (not all(math.isfinite(value) for value in (cls, x, y, w, h))
                or not cls.is_integer() or not 0 <= cls < classes_count
                or not 0 < w <= 1 or not 0 < h <= 1
                or x - w / 2 < -0.00001 or y - h / 2 < -0.00001
                or x + w / 2 > 1.00001 or y + h / 2 > 1.00001):
            issues.append(f"Invalid box or class on label row {line_number}")
            continue
        found.add(int(cls))
        rows.append((int(cls), x, y, w, h))
    return found, rows, issues


def compare_reviewed_boxes(reviewed_boxes, label_rows, tolerance=0.00001):
    """Compare accepted normalized boxes to normalized YOLO rows as multisets."""
    if not isinstance(reviewed_boxes, list):
        return False
    if len(reviewed_boxes) != len(label_rows):
        return False
    remaining = list(label_rows)
    for box in reviewed_boxes:
        if not isinstance(box, list) or len(box) != 5:
            return False
        if (any(not isinstance(value, (int, float)) or not math.isfinite(value) for value in box)
                or not float(box[0]).is_integer()):
            return False
        expected = (int(box[0]), float(box[1]), float(box[2]), float(box[3]), float(box[4]))
        match_index = next((index for index, actual in enumerate(remaining)
                            if expected[0] == actual[0] and all(abs(expected[i] - actual[i]) <= tolerance for i in range(1, 5))), None)
        if match_index is None:
            return False
        remaining.pop(match_index)
    return not remaining


def validate(repo_root: Path = ROOT, study_dir: Path = STUDY):
    """Return blockers. This function only reads files and hashes; it never starts training."""
    repo_root = Path(repo_root).resolve()
    study_dir = Path(study_dir).resolve()
    problems = []

    required_study = {
        "plan": study_dir / "plan.json",
        "lock": study_dir / "protocol_lock.json",
        "contract": study_dir / "manifest_contract.v1.json",
        "class map": study_dir / "class_mapping.v1.json",
        "acceptance registry": study_dir / "acceptance_registry.v1.json",
    }
    missing = [label for label, path in required_study.items() if not path.is_file()]
    if missing:
        return ["Final data gate files are missing: " + ", ".join(missing)]

    try:
        plan = read_json(required_study["plan"])
        lock = read_json(required_study["lock"])
        contract = read_json(required_study["contract"])
        class_map = read_json(required_study["class map"])
        registry = read_json(required_study["acceptance registry"])
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        return [f"Unable to read a required data-gate JSON file: {exc}"]

    if not isinstance(plan, dict) or not isinstance(lock, dict):
        return ["Plan and protocol lock must be JSON objects"]
    if not isinstance(contract, dict) or contract.get("schema_version") != 1 or contract.get("dataset_id") != "agrovoice-leaf-v3":
        return ["Manifest contract is missing or has an unsupported identity/version"]
    if not isinstance(class_map, dict):
        return ["Class map must be a JSON object"]
    if not isinstance(registry, dict):
        return ["Acceptance registry must be a JSON object"]

    if plan.get("status") != "locked_before_training" or plan.get("launch_allowed") is not True:
        problems.append("Plan is not explicitly locked and launch-enabled")
    if plan.get("study") != "yolo26s_data_v1" or not str(plan.get("architecture", "")).startswith("YOLO26s"):
        problems.append("Plan must be the approved two-run YOLO26s data study")
    if plan.get("seeds") != [20261003, 20261004] or plan.get("run_count") != 2:
        problems.append("Plan seeds/run_count differ from the user-authorized two-run scope")
    recipe = plan.get("recipe")
    if not isinstance(recipe, dict) or (recipe.get("epochs"), recipe.get("patience"), recipe.get("imgsz"), recipe.get("batch"), recipe.get("optimizer"), recipe.get("lr0"), recipe.get("amp")) != (60, 15, 416, 16, "AdamW", 0.001, False):
        problems.append("Plan recipe differs from the approved draft recipe")
    if plan.get("automatic_ten_runs") is not False:
        problems.append("Automatic ten-run continuation is prohibited")
    if lock.get("status") != "locked_before_training":
        problems.append("Protocol lock status is not locked_before_training")
    if lock.get("plan_sha256") != sha256_file(required_study["plan"]):
        problems.append("Plan lock is missing or does not match plan.json")

    expected_digests = {
        "class_mapping_sha256": required_study["class map"],
        "acceptance_registry_sha256": required_study["acceptance registry"],
        "manifest_contract_sha256": required_study["contract"],
    }
    for field, path in expected_digests.items():
        if lock.get(field) != sha256_file(path):
            problems.append(f"Protocol lock {field} missing or mismatched")

    try:
        old_classes = read_json(repo_root / "ml-pipeline/dataset_clean_v2/classes.json")
        old_manifest_path = repo_root / "ml-pipeline/dataset_clean_v2/manifest.json"
        old_manifest_sha = sha256_file(old_manifest_path)
        frozen_records, old_records = frozen_base_records(repo_root)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        return problems + [f"Unable to read/verify frozen clean_v2 class contract: {exc}"]
    class_problems, classes = validate_class_map(class_map, old_classes, old_manifest_sha)
    problems.extend(class_problems)
    problems.extend(validate_class_evidence(class_map, repo_root))
    if class_problems:
        return sorted(set(problems))
    registry_problems, registry_by_id, reviewed_annotations = validate_acceptance_registry(registry, classes, repo_root, study_dir)
    problems.extend(registry_problems)
    if registry_problems:
        return sorted(set(problems))
    accepted_registry_records = {key: value for key, value in registry_by_id.items() if value.get("decision") == "accepted"}
    candidate_keys = {
        (row.get("candidate_source"), row.get("candidate_id"))
        for row in accepted_registry_records.values()
        if row.get("origin_kind") == "new_source_record"
    }
    review_problems, candidate_evidence, quarantined_legacy_names, inherited_conflict_names, review_evidence_digest = load_review_evidence(contract, repo_root, candidate_keys)
    problems.extend(review_problems)
    problems.extend(validate_candidate_acceptances(accepted_registry_records, candidate_evidence, repo_root))
    migration_problems, legacy_migration_by_ref = validate_legacy_migrations(registry, old_records, accepted_registry_records, classes, repo_root)
    problems.extend(migration_problems)
    problems.extend(validate_inherited_records(accepted_registry_records, frozen_records, legacy_migration_by_ref, quarantined_legacy_names, inherited_conflict_names, repo_root))
    frozen_base = registry.get("frozen_base")
    if old_manifest_sha != PINNED_BASE_MANIFEST_SHA256:
        problems.append("Frozen clean_v2 manifest differs from the pinned baseline fingerprint")
    if not isinstance(frozen_base, dict) or frozen_base.get("manifest_sha256") != PINNED_BASE_MANIFEST_SHA256:
        problems.append("Acceptance registry does not bind to the pinned frozen clean_v2 base")
    if registry.get("source_revisions") != contract.get("source_revisions"):
        problems.append("Acceptance registry source revisions differ from the locked contract")
    if registry.get("source_licenses") != contract.get("source_licenses"):
        problems.append("Acceptance registry source licenses differ from the locked contract")
    if lock.get("frozen_base_manifest_sha256") != old_manifest_sha:
        problems.append("Protocol lock does not bind the frozen clean_v2 base")
    if contract.get("status") != "locked_before_training":
        problems.append("Manifest contract is not locked before training")
    if lock.get("review_evidence_sha256") != review_evidence_digest:
        problems.append("Protocol lock does not bind the pinned review-evidence set")
    if contract.get("allowed_splits") != list(SPLITS):
        problems.append("Manifest contract allowed_splits must be exactly train/val/test")

    raw_dataset_dir = plan.get("dataset_directory")
    try:
        data_dir = safe_relative_path(raw_dataset_dir, repo_root, repo_root / "ml-pipeline")
    except (ValueError, OSError) as exc:
        return sorted(set(problems + [f"Unsafe dataset_directory: {exc}"]))
    required_data = ("manifest.json", "report.json", "classes.json", "data.yaml")
    absent = [name for name in required_data if not (data_dir / name).is_file()]
    if absent:
        return sorted(set(problems + ["Prepared dataset files are missing: " + ", ".join(absent)]))

    try:
        manifest_path = data_dir / "manifest.json"
        manifest = read_json(manifest_path)
        report = read_json(data_dir / "report.json")
        dataset_classes = read_json(data_dir / "classes.json")
        yaml_doc = yaml.safe_load((data_dir / "data.yaml").read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError, yaml.YAMLError) as exc:
        return sorted(set(problems + [f"Unable to read prepared dataset metadata: {exc}"]))

    manifest_sha = sha256_file(manifest_path)
    if lock.get("dataset_fingerprint") != manifest_sha or plan.get("dataset_fingerprint") != manifest_sha:
        problems.append("Plan/lock dataset fingerprint does not match manifest.json")
    if not isinstance(report, dict):
        problems.append("Dataset report must be a JSON object")
    elif report.get("status") != "audited_ready" or report.get("fingerprint") != manifest_sha:
        problems.append("Dataset report is not audited_ready or has a mismatched fingerprint")
    if lock.get("dataset_report_sha256") != sha256_file(data_dir / "report.json"):
        problems.append("Protocol lock does not bind the dataset report")
    if isinstance(report, dict):
        if not isinstance(report.get("non_independent_validation_group_ids"), list):
            problems.append("Dataset report must disclose historically linked validation groups")
        allowed_physical = contract.get("physical_independence_status_values", [])
        if report.get("physical_independence_status") not in allowed_physical:
            problems.append("Dataset report must state physical plant/session independence status")
    if not isinstance(dataset_classes, list) or dataset_classes != classes:
        problems.append("Dataset classes.json does not exactly match the canonical class mapping")
    if not isinstance(report, dict):
        report = {}

    try:
        yaml_names = ordered_yaml_names(yaml_doc.get("names") if isinstance(yaml_doc, dict) else None)
        if yaml_names != classes:
            problems.append("data.yaml names/order does not exactly match the canonical class mapping")
    except ValueError as exc:
        problems.append(f"Invalid data.yaml class map: {exc}")
    if not isinstance(yaml_doc, dict):
        problems.append("data.yaml must be a YAML object")
    else:
        if yaml_doc.get("path") not in (None, "", "."):
            problems.append("data.yaml path must be absent or '.', never point outside the prepared dataset")
        for split in SPLITS:
            expected_yaml_path = f"images/{split}"
            configured = yaml_doc.get(split)
            if not isinstance(configured, str) or configured.replace("\\", "/").rstrip("/") != expected_yaml_path:
                problems.append(f"data.yaml {split} path must be exactly {expected_yaml_path}")
            try:
                split_dir = safe_relative_path(configured, data_dir, data_dir)
                if not split_dir.is_dir():
                    problems.append(f"data.yaml {split} path is not a directory")
            except (ValueError, OSError, TypeError) as exc:
                problems.append(f"Unsafe data.yaml {split} path: {exc}")

    manifest_format = contract.get("manifest_format", {})
    required_root_fields = set(manifest_format.get("root_fields", []))
    required_record_fields = set(manifest_format.get("record_fields", []))
    if not isinstance(manifest, dict) or required_root_fields - set(manifest):
        return sorted(set(problems + ["Manifest root fields do not satisfy the locked contract"]))
    if manifest.get("schema_version") != contract.get("schema_version") or manifest.get("dataset_id") != contract.get("dataset_id"):
        problems.append("Manifest identity/version does not match the contract")
    for field, path in (("class_mapping_sha256", required_study["class map"]),
                        ("acceptance_registry_sha256", required_study["acceptance registry"]),
                        ("manifest_contract_sha256", required_study["contract"])):
        if manifest.get(field) != sha256_file(path):
            problems.append(f"Manifest {field} missing or mismatched")

    records = manifest.get("records")
    if not isinstance(records, list) or not records:
        return sorted(set(problems + ["Prepared manifest records must be a non-empty list"]))
    accepted_records = accepted_registry_records
    if not accepted_records:
        problems.append("No individually accepted source records exist")

    filenames, record_ids, used_acceptance_ids, source_hashes = set(), set(), set(), set()
    manifest_files = {split: {"images": set(), "labels": set()} for split in SPLITS}
    split_groups = {split: set() for split in SPLITS}
    class_train_groups = {class_id: set() for class_id in range(len(classes))}
    class_val_groups = {class_id: set() for class_id in range(len(classes))}
    class_test_groups = {class_id: set() for class_id in range(len(classes))}
    split_records = {split: [] for split in SPLITS}
    expected_non_independent_validation_groups = set()
    for row in records:
        if not isinstance(row, dict):
            problems.append("Manifest contains a non-object record")
            continue
        missing_fields = required_record_fields - set(row)
        if row.get("origin_kind") == "new_source_record":
            missing_fields |= set(manifest_format.get("new_source_record_extra_fields", [])) - set(row)
        if missing_fields:
            problems.append("Manifest record missing contract fields: " + ",".join(sorted(missing_fields)))
            continue
        record_id, acceptance_id = row["record_id"], row["acceptance_record_id"]
        if not isinstance(record_id, str) or not record_id or record_id in record_ids:
            problems.append("Manifest record IDs must be non-empty and unique")
            continue
        record_ids.add(record_id)
        if not isinstance(acceptance_id, str) or not acceptance_id:
            problems.append(f"Manifest record {record_id} has invalid acceptance_record_id")
            continue
        if acceptance_id in used_acceptance_ids:
            problems.append(f"Acceptance decision {acceptance_id} is reused by multiple manifest rows")
            continue
        used_acceptance_ids.add(acceptance_id)
        split = row["split"]
        if split not in SPLITS:
            problems.append(f"Unknown manifest split {split!r}; only train/val/test are allowed")
            continue
        if acceptance_id in registry_by_id and registry_by_id[acceptance_id].get("decision") in {"excluded", "quarantined"}:
            problems.append(f"Manifest record {record_id} references a non-accepted disposition")
            continue
        acceptance = accepted_records.get(acceptance_id)
        if acceptance is None:
            problems.append(f"Manifest record {record_id} does not reference an accepted registry decision")
            continue
        legacy_ref = acceptance.get("legacy_record_ref")
        if legacy_ref:
            migration = legacy_migration_by_ref.get(legacy_ref)
            if not isinstance(migration, dict) or migration.get("decision") != "migrate":
                problems.append(f"Inherited record {record_id} lacks an approved legacy class-29 migration")
            elif migration.get("acceptance_record_id") != acceptance_id or migration.get("assigned_split") != split:
                problems.append(f"Inherited record {record_id} does not match its sealed migration decision")
        legacy_source_refs = [ref for ref, old_row in old_records.items() if old_row["source_sha256"] == row.get("source_sha256")]
        for legacy_source_ref in legacy_source_refs:
            migration = legacy_migration_by_ref.get(legacy_source_ref)
            if not isinstance(migration, dict) or migration.get("decision") != "migrate" or legacy_ref != legacy_source_ref:
                problems.append(f"Frozen class-29 source {legacy_source_ref} was reused without its migration decision")
        for field in ("origin_kind", "source_id", "source_revision", "source_member", "source_sha256", "source_class", "annotation_sha256", "group_id"):
            if row.get(field) != acceptance.get(field):
                problems.append(f"Manifest record {record_id} mismatches acceptance registry field {field}")
        if row.get("origin_kind") == "new_source_record":
            for field in ("candidate_source", "candidate_id", "group_review_status"):
                if row.get(field) != acceptance.get(field):
                    problems.append(f"Manifest record {record_id} mismatches acceptance registry field {field}")
            candidate = candidate_evidence.get((row.get("candidate_source"), row.get("candidate_id")), {})
            if split == "val" and candidate.get("split_rule") == "historical_holdout_family_never_train_or_new_independent_test":
                expected_non_independent_validation_groups.add(row.get("group_id"))
        if row["class_ids"] != acceptance.get("class_ids"):
            problems.append(f"Manifest record {record_id} class_ids mismatch accepted decision")
        if split not in acceptance.get("split_eligibility", []):
            problems.append(f"Manifest record {record_id} split is not allowed by its acceptance decision")
        if not is_sha256(row["source_sha256"]) or not is_sha256(row["annotation_sha256"]):
            problems.append(f"Manifest record {record_id} has invalid source/annotation hashes")
            continue
        if row["source_sha256"] in source_hashes:
            problems.append(f"Duplicate source image SHA-256 in manifest: {record_id}")
        source_hashes.add(row["source_sha256"])
        if not isinstance(row["class_ids"], list) or not row["class_ids"] or any(
            not isinstance(class_id, int) or class_id < 0 or class_id >= len(classes) for class_id in row["class_ids"]
        ) or len(row["class_ids"]) != len(set(row["class_ids"])):
            problems.append(f"Manifest record {record_id} has invalid class_ids")
            continue
        filename = row["filename"]
        if not isinstance(filename, str) or not filename or PurePosixPath(filename.replace("\\", "/")).name != filename.replace("\\", "/") or PureWindowsPath(filename).name != filename:
            problems.append(f"Unsafe manifest filename in record {record_id}")
            continue
        filename_key = filename.casefold()
        if filename_key in filenames:
            problems.append(f"Duplicate manifest filename: {filename}")
            continue
        filenames.add(filename_key)
        if not is_sha256(row["prepared_sha256"]) or not is_sha256(row["label_sha256"]):
            problems.append(f"Manifest record {record_id} has invalid prepared-file hashes")
            continue
        try:
            image_path = safe_relative_path(f"images/{split}/{filename}", data_dir, data_dir)
            label_path = safe_relative_path(f"labels/{split}/{Path(filename).stem}.txt", data_dir, data_dir)
        except (ValueError, OSError) as exc:
            problems.append(f"Unsafe image/label path in record {record_id}: {exc}")
            continue
        if not image_path.is_file() or not label_path.is_file():
            problems.append(f"Missing image or label for manifest record {record_id}")
            continue
        manifest_files[split]["images"].add(filename.casefold())
        manifest_files[split]["labels"].add(f"{Path(filename).stem}.txt".casefold())
        if sha256_file(image_path) != row["prepared_sha256"] or sha256_file(label_path) != row["label_sha256"]:
            problems.append(f"Prepared image/label SHA mismatch for record {record_id}")
        label_classes, label_rows, label_issues = parse_label(label_path, len(classes))
        problems.extend(f"{record_id}: {issue}" for issue in label_issues)
        if label_classes != set(row["class_ids"]):
            problems.append(f"Manifest class_ids do not match label contents for record {record_id}")
        annotation = reviewed_annotations.get(acceptance_id)
        if annotation is None or not compare_reviewed_boxes(annotation.get("boxes_xywh"), label_rows):
            problems.append(f"Prepared labels do not reproduce accepted reviewed boxes for record {record_id}")
        split_groups[split].add(row["group_id"])
        split_records[split].append(row)
        for class_id in label_classes:
            if split == "train":
                class_train_groups[class_id].add(row["group_id"])
            elif split == "val":
                class_val_groups[class_id].add(row["group_id"])
            else:
                class_test_groups[class_id].add(row["group_id"])

    for split in SPLITS:
        for modality in ("images", "labels"):
            directory = data_dir / modality / split
            if not directory.is_dir():
                problems.append(f"Missing prepared {modality}/{split} directory")
                continue
            try:
                actual = set()
                for child in directory.iterdir():
                    if not child.is_file():
                        problems.append(f"Unexpected directory/non-file in {modality}/{split}: {child.name}")
                        continue
                    actual.add(child.name.casefold())
                expected = manifest_files[split][modality]
                if actual != expected:
                    extras = sorted(actual - expected)[:5]
                    missing_files = sorted(expected - actual)[:5]
                    problems.append(f"{modality}/{split} directory differs from manifest (extras={extras}, missing={missing_files})")
            except OSError as exc:
                problems.append(f"Unable to inventory {modality}/{split}: {exc}")

    if used_acceptance_ids != set(accepted_records):
        unused = sorted(set(accepted_records) - used_acceptance_ids)[:5]
        problems.append(f"Acceptance registry has accepted rows absent from the dataset manifest: {unused}")

    for legacy_ref, migration in legacy_migration_by_ref.items():
        if migration.get("decision") == "migrate" and migration.get("acceptance_record_id") not in used_acceptance_ids:
            problems.append(f"Migrated legacy record {legacy_ref} is not present in the prepared manifest")
        if migration.get("decision") in {"exclude", "quarantine"} and migration.get("acceptance_record_id") in used_acceptance_ids:
            problems.append(f"Excluded/quarantined legacy record {legacy_ref} appears in the prepared manifest")

    for left, right in (("train", "val"), ("train", "test"), ("val", "test")):
        if split_groups[left] & split_groups[right]:
            problems.append(f"Group leakage between {left} and {right}")
    reported_linked = report.get("non_independent_validation_group_ids") if isinstance(report, dict) else None
    if (not isinstance(reported_linked, list) or any(not isinstance(group, str) for group in reported_linked)
            or set(reported_linked) != expected_non_independent_validation_groups):
        problems.append("Dataset report linked-validation group disclosure does not match pinned historical constraints")
    for class_id in range(29):
        if not class_train_groups[class_id]:
            problems.append(f"Legacy class {class_id} has no accepted training group")
    for new_class in class_map.get("new_conditions", []):
        class_id = new_class["id"]
        if len(class_train_groups[class_id]) < new_class["minimum_train_groups"]:
            problems.append(f"New class {class_id} lacks declared training-group support")
        if len(class_val_groups[class_id]) < new_class["minimum_val_groups"]:
            problems.append(f"New class {class_id} lacks declared validation-group support")
        if len(class_test_groups[class_id]) < new_class["minimum_test_groups"]:
            problems.append(f"New class {class_id} lacks declared test-group support")

    test_sealed_utc = lock.get("test_sealed_utc")
    try:
        datetime.fromisoformat(test_sealed_utc.replace("Z", "+00:00"))
    except (AttributeError, ValueError):
        problems.append("Protocol lock lacks a valid test_sealed_utc timestamp")
    if lock.get("test_manifest_sha256") != test_manifest_sha256(split_records["test"]):
        problems.append("Locked test manifest hash does not match test rows")
    if not isinstance(plan.get("selection_rule"), str) or not plan["selection_rule"].strip():
        problems.append("Plan must define the model-selection rule before test evaluation")

    protected = lock.get("protected_sources", {})
    if not isinstance(protected, dict):
        problems.append("Protocol lock protected_sources must be an object")
    else:
        for relative_name, digest in protected.items():
            try:
                protected_path = safe_relative_path(relative_name, repo_root / "ml-pipeline", repo_root / "ml-pipeline")
            except (ValueError, OSError) as exc:
                problems.append(f"Unsafe protected-source path: {exc}")
                continue
            if not protected_path.is_file() or not is_sha256(digest) or sha256_file(protected_path) != digest:
                problems.append(f"Protected source missing or changed: {relative_name}")

    return sorted(set(problems))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, help="Optional new evidence file, restricted to the study directory")
    parser.add_argument("--overwrite-evidence", action="store_true", help="Allow replacing an existing evidence file")
    args = parser.parse_args()
    problems = validate()
    result = {"status": "blocked" if problems else "passed", "training_started": False, "problems": problems}
    if args.evidence:
        try:
            evidence_path = safe_relative_path(str(args.evidence), STUDY, STUDY)
            if evidence_path.exists() and not args.overwrite_evidence:
                raise ValueError("evidence file already exists; pass --overwrite-evidence to replace it")
            evidence_path.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=evidence_path.parent, delete=False) as stream:
                json.dump(result, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
                temporary = Path(stream.name)
            os.replace(temporary, evidence_path)
        except (OSError, ValueError) as exc:
            result["status"] = "blocked"
            result["problems"] = sorted(set(result["problems"] + [f"Evidence output refused: {exc}"]))
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(2 if result["problems"] else 0)


if __name__ == "__main__":
    main()
