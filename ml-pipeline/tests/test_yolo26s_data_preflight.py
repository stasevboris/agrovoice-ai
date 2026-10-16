import json
from pathlib import Path
import sys
import tempfile
import unittest

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import preflight_yolo26s_data_v1 as gate


LEGACY_NAMES = [
    "Apple Scab Leaf", "Apple leaf", "Apple rust leaf", "Bell_pepper leaf",
    "Bell_pepper leaf spot", "Blueberry leaf", "Cherry leaf", "Corn Gray leaf spot",
    "Corn leaf blight", "Corn rust leaf", "Peach leaf", "Potato leaf",
    "Potato leaf early blight", "Potato leaf late blight", "Raspberry leaf",
    "Soyabean leaf", "Squash Powdery mildew leaf", "Strawberry leaf",
    "Tomato Early blight leaf", "Tomato Septoria leaf spot", "Tomato leaf",
    "Tomato leaf bacterial spot", "Tomato leaf late blight", "Tomato leaf mosaic virus",
    "Tomato leaf yellow virus", "Tomato mold leaf", "Tomato two spotted spider mites leaf",
    "grape leaf", "grape leaf black rot",
]
OLD_NAMES = LEGACY_NAMES + ["Cucumber leaf (condition unknown)"]


def dump_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fixture_evidence_digest(contract):
    rows = [{"id": row["id"], "path": row["path"], "sha256": row["sha256"]} for row in contract["review_evidence"]]
    return gate.canonical_json_sha256(sorted(rows, key=lambda row: row["id"]))


class LeafV3PreflightTests(unittest.TestCase):
    def make_ready_fixture(self, temp_root):
        root = Path(temp_root) / "repo"
        study = root / "docs/experiments/yolo26s_data_v1"
        data = root / "ml-pipeline/dataset_leaf_v3"
        frozen = root / "ml-pipeline/dataset_clean_v2"
        frozen.mkdir(parents=True)
        dump_json(frozen / "classes.json", OLD_NAMES)
        dump_json(frozen / "manifest.json", [])
        frozen_sha = gate.sha256_file(frozen / "manifest.json")
        previous_pinned_sha = gate.PINNED_BASE_MANIFEST_SHA256
        gate.PINNED_BASE_MANIFEST_SHA256 = frozen_sha
        self.addCleanup(setattr, gate, "PINNED_BASE_MANIFEST_SHA256", previous_pinned_sha)

        contract = {
            "schema_version": 1,
            "dataset_id": "agrovoice-leaf-v3",
            "status": "locked_before_training",
            "allowed_splits": list(gate.SPLITS),
            "manifest_format": {
                "root_fields": ["schema_version", "dataset_id", "class_mapping_sha256", "acceptance_registry_sha256", "manifest_contract_sha256", "records"],
                "record_fields": ["record_id", "acceptance_record_id", "origin_kind", "source_id", "source_revision", "source_member", "source_payload_path", "source_sha256", "source_class", "annotation_sha256", "class_ids", "group_id", "split", "filename", "prepared_sha256", "label_sha256"],
                "new_source_record_extra_fields": ["candidate_source", "candidate_id", "group_review_status"],
            },
            "review_evidence_expected_candidate_records": 31,
            "review_evidence_expected_counts": {"annotation_review_queue": 31, "candidate_split_constraints": 31, "candidate_dispositions": 31, "plantvillage_previous29_provenance": 31, "plantvillage_extra9_provenance": 0, "olid_source_provenance": 0, "cucumber_source_provenance": 0},
            "source_revisions": {"PlantVillage": "test-pinned-revision", "OLID": "OLID-I-1.1.0", "CucumberZenodo": "Zenodo-16816441"},
            "source_licenses": {"PlantVillage": "CC-BY-SA-3.0", "OLID": "CC-BY-4.0", "CucumberZenodo": "CC-BY-4.0"},
            "review_evidence": [],
            "report_fields": ["status", "fingerprint", "non_independent_validation_group_ids", "physical_independence_status"],
            "physical_independence_status_values": ["source_verified", "approximate_group_constraints_only", "not_established"],
        }
        class_map = {
            "schema_version": 1,
            "dataset_id": "agrovoice-leaf-v3",
            "status": "locked_before_training",
            "base_dataset_id": "dataset_clean_v2",
            "base_dataset_manifest_sha256": frozen_sha,
            "legacy_classes": [{"id": i, "name": name, "status": "preserved_legacy_id_name"} for i, name in enumerate(LEGACY_NAMES)],
            "legacy_classes_not_carried_into_new_head": [{"legacy_id": 29, "name": OLD_NAMES[29], "status": "per_record_migration_or_exclusion_pending", "rule": "Do not automatically map to healthy."}],
            "new_conditions": [],
            "next_available_id": 29,
        }
        evidence_dir = root / "evidence"
        evidence_dir.mkdir()
        evidence = evidence_dir / "review.txt"
        evidence.write_text("synthetic unit-test evidence\n", encoding="utf-8")
        evidence_ref = evidence.relative_to(root).as_posix()
        accepted_annotation_dir = study / "accepted_annotations"
        accepted_annotation_dir.mkdir(parents=True)

        for split in gate.SPLITS:
            (data / "images" / split).mkdir(parents=True)
            (data / "labels" / split).mkdir(parents=True)

        records = []
        acceptance_rows = []
        source_stage = root / "ml-pipeline/source_candidates/test_fixture"
        source_stage.mkdir(parents=True)
        def add_record(record_id, split, class_ids, group_id):
            source_bytes = f"source:{record_id}".encode()
            source_sha = gate.sha256_bytes(source_bytes)
            source_payload = source_stage / f"{record_id}.jpg"
            source_payload.write_bytes(source_bytes)
            filename = f"{record_id}.jpg"
            candidate_id = record_id
            image_path = data / "images" / split / filename
            label_path = data / "labels" / split / f"{record_id}.txt"
            image_path.write_bytes(f"prepared-image:{record_id}".encode())
            label_rows = [f"{class_id} 0.5 0.5 0.4 0.4" for class_id in class_ids]
            label_path.write_text("\n".join(label_rows) + "\n", encoding="utf-8")
            annotation = {
                "schema_version": 1,
                "record_id": record_id,
                "source_sha256": source_sha,
                "class_ids": class_ids,
                "boxes_xywh": [[class_id, 0.5, 0.5, 0.4, 0.4] for class_id in class_ids],
                "geometry_status": "whole_visible_leaf_accepted",
                "semantics_status": "source_semantics_accepted",
                "reviewer_id": "unit-test",
                "reviewed_utc": "2026-10-12T10:00:00+00:00",
                "evidence_references": [evidence_ref],
            }
            annotation_path = accepted_annotation_dir / f"{record_id}.json"
            dump_json(annotation_path, annotation)
            annotation_sha = gate.sha256_file(annotation_path)
            accepted = {
                "record_id": record_id,
                "origin_kind": "new_source_record",
                "source_id": "PlantVillage",
                "source_revision": contract["source_revisions"]["PlantVillage"],
                "source_member": f"raw/color/test_class/{filename}",
                "source_payload_path": source_payload.relative_to(root).as_posix(),
                "source_sha256": source_sha,
                "candidate_source": "PlantVillage",
                "candidate_id": candidate_id,
                "group_review_status": "source_group_reviewed",
                "license_reference": contract["source_licenses"]["PlantVillage"],
                "source_class": LEGACY_NAMES[class_ids[0]],
                "annotation_kind": "reviewed_whole_leaf_xywh",
                "annotation_sha256": annotation_sha,
                "reviewed_annotation_path": annotation_path.relative_to(root).as_posix(),
                "geometry_status": "whole_visible_leaf_accepted",
                "semantics_status": "source_semantics_accepted",
                "group_id": group_id,
                "split_eligibility": [split],
                "decision": "accepted",
                "class_ids": class_ids,
                "decision_reason": "Synthetic fixture only",
                "reviewer_id": "unit-test",
                "reviewed_utc": "2026-10-12T10:00:00+00:00",
                "evidence_references": [evidence_ref],
            }
            acceptance_rows.append(accepted)
            records.append({
                "record_id": record_id,
                "acceptance_record_id": record_id,
                "origin_kind": accepted["origin_kind"],
                "source_id": accepted["source_id"],
                "source_revision": accepted["source_revision"],
                "source_member": accepted["source_member"],
                "source_payload_path": accepted["source_payload_path"],
                "source_sha256": source_sha,
                "source_class": accepted["source_class"],
                "candidate_source": accepted["candidate_source"],
                "candidate_id": accepted["candidate_id"],
                "group_review_status": accepted["group_review_status"],
                "annotation_sha256": annotation_sha,
                "class_ids": class_ids,
                "group_id": group_id,
                "split": split,
                "filename": filename,
                "prepared_sha256": gate.sha256_file(image_path),
                "label_sha256": gate.sha256_file(label_path),
            })

        for class_id in range(29):
            add_record(f"train-{class_id}", "train", [class_id], f"train-group-{class_id}")
        add_record("validation-0", "val", [0], "validation-group-0")
        add_record("test-0", "test", [0], "test-group-0")

        names_yaml = "\n".join(f"  {i}: {json.dumps(name, ensure_ascii=False)}" for i, name in enumerate(LEGACY_NAMES))
        (data / "data.yaml").write_text(
            "train: images/train\nval: images/val\ntest: images/test\nnames:\n" + names_yaml + "\n",
            encoding="utf-8",
        )
        dump_json(data / "classes.json", LEGACY_NAMES)

        review_dir = study / "review_evidence_fixture"
        review_dir.mkdir(parents=True)
        queue_rows, constraint_rows, disposition_rows = [], [], []
        for record, accepted in zip(records, acceptance_rows):
            key = {"source": accepted["candidate_source"], "id": accepted["candidate_id"]}
            queue_rows.append({**key, "source_class": accepted["source_class"], "source_sha256": accepted["source_sha256"], "proposed_existing_id": accepted["class_ids"][0], "component_id": accepted["group_id"], "publisher_leaf_group": f"pv:{accepted['source_class']}:::test-group", "split_rule": "no_historical_split_restriction_physical_provenance_still_requires_review", "overlap_decision": "no_match_in_this_screen_still_pending_semantics_boxes_groups", "pending": []})
            constraint_rows.append({**key, "source_class": accepted["source_class"], "component_id": accepted["group_id"],
                                    "publisher_leaf_group": f"pv:{accepted['source_class']}:::test-group",
                                    "decision": "no_match_in_this_screen_still_pending_semantics_boxes_groups",
                                    "split_rule": "no_historical_split_restriction_physical_provenance_still_requires_review",
                                    "historical_splits": [], "assigned_split": None})
            disposition_rows.append({**key, "source_class": accepted["source_class"],
                                     "decision": "no_match_in_this_screen_still_pending_semantics_boxes_groups",
                                     "training_accepted": False})
        pv_provenance = []
        for accepted in acceptance_rows:
            pv_provenance.append({
                "id": accepted["candidate_id"],
                "source_class": accepted["source_class"],
                "source_files": {"color": {"source_path": accepted["source_member"], "candidate_path": accepted["source_payload_path"]}},
                "source_sha256": {"color": accepted["source_sha256"]},
            })
        evidence_payloads = {
            "annotation_review_queue": ("review_evidence_fixture/annotation_review_queue.jsonl", queue_rows),
            "candidate_split_constraints": ("review_evidence_fixture/candidate_split_constraints.jsonl", constraint_rows),
            "candidate_dispositions": ("review_evidence_fixture/candidate_dispositions.jsonl", disposition_rows),
            "plantvillage_previous29_provenance": ("review_evidence_fixture/pv_previous.jsonl", pv_provenance),
            "plantvillage_extra9_provenance": ("review_evidence_fixture/pv_extra.jsonl", []),
            "olid_source_provenance": ("review_evidence_fixture/olid.jsonl", []),
        }
        evidence_specs = []
        for evidence_id, (relative, rows) in evidence_payloads.items():
            target = study / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
            evidence_specs.append({"id": evidence_id, "path": (target.relative_to(root).as_posix()), "sha256": gate.sha256_file(target)})
        legacy_visual = study / "review_evidence_fixture/legacy_visual.json"
        inherited_conflicts = study / "review_evidence_fixture/inherited_conflicts.json"
        dump_json(legacy_visual, {"quarantine_pending_annotation_review_for_new_base_only": []})
        dump_json(inherited_conflicts, [])
        cucumber_inventory = review_dir / "cucumber.json"
        dump_json(cucumber_inventory, {"images": []})
        evidence_specs.extend([
            {"id": "cucumber_source_provenance", "path": cucumber_inventory.relative_to(root).as_posix(), "sha256": gate.sha256_file(cucumber_inventory)},
            {"id": "legacy_visual_quarantine", "path": legacy_visual.relative_to(root).as_posix(), "sha256": gate.sha256_file(legacy_visual)},
            {"id": "inherited_split_conflicts", "path": inherited_conflicts.relative_to(root).as_posix(), "sha256": gate.sha256_file(inherited_conflicts)},
        ])
        contract["review_evidence_expected_candidate_records"] = len(records)
        contract["review_evidence"] = evidence_specs
        dump_json(study / "manifest_contract.v1.json", contract)
        dump_json(study / "class_mapping.v1.json", class_map)
        registry = {"schema_version": 1, "dataset_id": "agrovoice-leaf-v3", "status": "locked_before_training",
                    "source_revisions": contract["source_revisions"], "source_licenses": contract["source_licenses"],
                    "frozen_base": {"dataset_id": "dataset_clean_v2", "manifest_sha256": frozen_sha},
                    "legacy_migrations": [],
                    "records": acceptance_rows}
        dump_json(study / "acceptance_registry.v1.json", registry)
        manifest = {
            "schema_version": 1,
            "dataset_id": "agrovoice-leaf-v3",
            "class_mapping_sha256": gate.sha256_file(study / "class_mapping.v1.json"),
            "acceptance_registry_sha256": gate.sha256_file(study / "acceptance_registry.v1.json"),
            "manifest_contract_sha256": gate.sha256_file(study / "manifest_contract.v1.json"),
            "records": records,
        }
        dump_json(data / "manifest.json", manifest)
        dump_json(data / "report.json", {"status": "audited_ready", "fingerprint": gate.sha256_file(data / "manifest.json"),
                                         "non_independent_validation_group_ids": [],
                                         "physical_independence_status": "approximate_group_constraints_only"})
        plan = {"status": "locked_before_training", "launch_allowed": True,
                "study": "yolo26s_data_v1", "architecture": "YOLO26s Detect ordinary multiclass head",
                "seeds": [20261003, 20261004], "run_count": 2,
                "automatic_ten_runs": False,
                "recipe": {"epochs": 60, "patience": 15, "imgsz": 416, "batch": 16, "optimizer": "AdamW", "lr0": 0.001, "amp": False},
                "dataset_directory": "ml-pipeline/dataset_leaf_v3",
                "dataset_fingerprint": gate.sha256_file(data / "manifest.json"),
                "selection_rule": "Select by locked validation metric only; inspect sealed test once."}
        dump_json(study / "plan.json", plan)
        lock = {
            "status": "locked_before_training",
            "plan_sha256": gate.sha256_file(study / "plan.json"),
            "dataset_fingerprint": gate.sha256_file(data / "manifest.json"),
            "dataset_report_sha256": gate.sha256_file(data / "report.json"),
            "frozen_base_manifest_sha256": frozen_sha,
            "class_mapping_sha256": gate.sha256_file(study / "class_mapping.v1.json"),
            "acceptance_registry_sha256": gate.sha256_file(study / "acceptance_registry.v1.json"),
            "manifest_contract_sha256": gate.sha256_file(study / "manifest_contract.v1.json"),
            "review_evidence_sha256": fixture_evidence_digest(contract),
            "test_manifest_sha256": gate.test_manifest_sha256([row for row in records if row["split"] == "test"]),
            "test_sealed_utc": "2026-10-12T10:01:00+00:00",
            "protected_sources": {},
        }
        dump_json(study / "protocol_lock.json", lock)
        return root, study, data

    def reseal(self, root, study, data):
        """Recompute synthetic fixture seals after a deliberate test mutation."""
        manifest_path = data / "manifest.json"
        manifest = gate.read_json(manifest_path)
        manifest["class_mapping_sha256"] = gate.sha256_file(study / "class_mapping.v1.json")
        manifest["acceptance_registry_sha256"] = gate.sha256_file(study / "acceptance_registry.v1.json")
        manifest["manifest_contract_sha256"] = gate.sha256_file(study / "manifest_contract.v1.json")
        dump_json(manifest_path, manifest)
        fingerprint = gate.sha256_file(manifest_path)
        report = gate.read_json(data / "report.json")
        report["fingerprint"] = fingerprint
        dump_json(data / "report.json", report)
        plan = gate.read_json(study / "plan.json")
        plan["dataset_fingerprint"] = fingerprint
        dump_json(study / "plan.json", plan)
        lock = gate.read_json(study / "protocol_lock.json")
        lock.update({
            "plan_sha256": gate.sha256_file(study / "plan.json"),
            "dataset_fingerprint": fingerprint,
            "dataset_report_sha256": gate.sha256_file(data / "report.json"),
            "class_mapping_sha256": gate.sha256_file(study / "class_mapping.v1.json"),
            "acceptance_registry_sha256": gate.sha256_file(study / "acceptance_registry.v1.json"),
            "manifest_contract_sha256": gate.sha256_file(study / "manifest_contract.v1.json"),
            "review_evidence_sha256": fixture_evidence_digest(gate.read_json(study / "manifest_contract.v1.json")),
            "test_manifest_sha256": gate.test_manifest_sha256([row for row in manifest["records"] if row["split"] == "test"]),
        })
        dump_json(study / "protocol_lock.json", lock)

    def test_current_draft_without_final_lock_blocks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            study = root / "docs/experiments/yolo26s_data_v1"
            study.mkdir(parents=True)
            dump_json(study / "manifest_contract.v1.json", {"schema_version": 1})
            dump_json(study / "class_mapping.v1.json", {})
            dump_json(study / "acceptance_registry.v1.json", {})
            blockers = gate.validate(root, study)
            self.assertTrue(any("plan" in blocker.lower() or "lock" in blocker.lower() for blocker in blockers))

    def test_synthetic_fully_reviewed_fixture_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root, study, data = self.make_ready_fixture(directory)
            self.assertEqual(gate.validate(root, study), [])

    def test_unknown_manifest_split_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root, study, data = self.make_ready_fixture(directory)
            manifest = gate.read_json(data / "manifest.json")
            manifest["records"][0]["split"] = "val_aux"
            dump_json(data / "manifest.json", manifest)
            self.reseal(root, study, data)
            self.assertTrue(any("Unknown manifest split" in blocker for blocker in gate.validate(root, study)))

    def test_yaml_class_mapping_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root, study, data = self.make_ready_fixture(directory)
            yaml_path = data / "data.yaml"
            yaml_doc = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))
            yaml_doc["names"][0] = "wrong class"
            yaml_path.write_text(yaml.safe_dump(yaml_doc, sort_keys=False, allow_unicode=True), encoding="utf-8")
            self.assertTrue(any("data.yaml names/order" in blocker for blocker in gate.validate(root, study)))

    def test_yaml_split_path_must_match_manifest_split(self):
        with tempfile.TemporaryDirectory() as directory:
            root, study, data = self.make_ready_fixture(directory)
            yaml_path = data / "data.yaml"
            yaml_doc = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))
            yaml_doc["val"] = "images/train"
            yaml_path.write_text(yaml.safe_dump(yaml_doc, sort_keys=False, allow_unicode=True), encoding="utf-8")
            self.assertTrue(any("data.yaml val path must be exactly images/val" in blocker for blocker in gate.validate(root, study)))

    def test_unmanifested_image_file_blocks(self):
        with tempfile.TemporaryDirectory() as directory:
            root, study, data = self.make_ready_fixture(directory)
            (data / "images/train/unmanifested.jpg").write_bytes(b"not in manifest")
            self.assertTrue(any("images/train directory differs from manifest" in blocker for blocker in gate.validate(root, study)))

    def test_prepared_label_box_must_match_accepted_annotation(self):
        with tempfile.TemporaryDirectory() as directory:
            root, study, data = self.make_ready_fixture(directory)
            label = data / "labels/train/train-0.txt"
            label.write_text("0 0.1 0.1 0.4 0.4\\n", encoding="utf-8")
            manifest = gate.read_json(data / "manifest.json")
            row = next(item for item in manifest["records"] if item["record_id"] == "train-0")
            row["label_sha256"] = gate.sha256_file(label)
            dump_json(data / "manifest.json", manifest)
            self.reseal(root, study, data)
            self.assertTrue(any("do not reproduce accepted reviewed boxes" in blocker for blocker in gate.validate(root, study)))

    def test_candidate_historical_split_rules_fail_closed(self):
        key = ("PlantVillage", "pv-candidate")
        accepted = {"a": {"origin_kind": "new_source_record", "candidate_source": key[0], "candidate_id": key[1],
                           "source_id": key[0], "source_class": "Potato___healthy", "source_sha256": "a"*64,
                           "source_revision": "rev1", "source_member": "raw/color/example.jpg", "source_payload_path": "ml-pipeline/source_candidates/example.jpg", "group_id": "component-1",
                           "group_review_status": "source_group_reviewed", "split_eligibility": ["train"]}}
        with tempfile.TemporaryDirectory() as source_dir:
            source_root = Path(source_dir) / "repo"
            staged = source_root / "ml-pipeline/source_candidates/example.jpg"
            staged.parent.mkdir(parents=True)
            staged.write_bytes(b"candidate-source-bytes")
            accepted["a"]["source_sha256"] = gate.sha256_file(staged)
            evidence = {key: {"source_class": "Potato___healthy", "source_sha256": gate.sha256_file(staged), "source_revision": "rev1",
                          "source_member": "raw/color/example.jpg", "source_payload_path": "ml-pipeline/source_candidates/example.jpg", "component_id": "component-1",
                          "publisher_leaf_group": "pv:Potato___healthy:::16.0",
                          "split_rule": "training_only_if_other_checks_pass_never_new_independent_holdout",
                          "historical_splits": ["train"], "overlap_decision": "no_match_in_this_screen_still_pending_semantics_boxes_groups"}}
            self.assertEqual(gate.validate_candidate_acceptances(accepted, evidence, source_root), [])
            accepted["a"]["split_eligibility"] = ["test"]
            self.assertTrue(any("train-only under historical group constraints" in blocker for blocker in gate.validate_candidate_acceptances(accepted, evidence, source_root)))
            evidence[key]["split_rule"] = "historical_holdout_family_never_train_or_new_independent_test"
            accepted["a"]["split_eligibility"] = ["test"]
            self.assertTrue(any("declared as linked validation" in blocker for blocker in gate.validate_candidate_acceptances(accepted, evidence, source_root)))

    def test_pending_record_cannot_be_used_by_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root, study, data = self.make_ready_fixture(directory)
            registry_path = study / "acceptance_registry.v1.json"
            registry = gate.read_json(registry_path)
            registry["records"][0]["decision"] = "pending"
            dump_json(registry_path, registry)
            self.reseal(root, study, data)
            blockers = gate.validate(root, study)
            self.assertTrue(any("pending record" in blocker or "does not reference an accepted" in blocker for blocker in blockers))

    def test_group_leakage_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root, study, data = self.make_ready_fixture(directory)
            manifest_path = data / "manifest.json"
            manifest = gate.read_json(manifest_path)
            train = next(row for row in manifest["records"] if row["split"] == "train" and row["class_ids"] == [0])
            val = next(row for row in manifest["records"] if row["split"] == "val")
            val["group_id"] = train["group_id"]
            registry_path = study / "acceptance_registry.v1.json"
            registry = gate.read_json(registry_path)
            val_acceptance = next(row for row in registry["records"] if row["record_id"] == val["acceptance_record_id"])
            val_acceptance["group_id"] = train["group_id"]
            dump_json(registry_path, registry)
            dump_json(manifest_path, manifest)
            self.reseal(root, study, data)
            self.assertTrue(any("Group leakage" in blocker for blocker in gate.validate(root, study)))

    def test_legacy_cucumber_id_29_requires_and_accepts_explicit_exclusion(self):
        with tempfile.TemporaryDirectory() as directory:
            root, study, data = self.make_ready_fixture(directory)
            frozen = root / "ml-pipeline/dataset_clean_v2"
            (frozen / "images/train").mkdir(parents=True)
            (frozen / "labels/train").mkdir(parents=True)
            image_bytes = b"legacy-cucumber-image"
            label_bytes = b"29 0.5 0.5 0.4 0.4\n"
            image_path = frozen / "images/train/legacy-cucumber.jpg"
            label_path = frozen / "labels/train/legacy-cucumber.txt"
            image_path.write_bytes(image_bytes)
            label_path.write_bytes(label_bytes)
            old_row = {
                "split": "train",
                "filename": "legacy-cucumber.jpg",
                "boxes": [[29, 0.5, 0.5, 0.4, 0.4]],
                "prepared_sha256": gate.sha256_file(image_path),
                "label_sha256": gate.sha256_file(label_path),
                "group_id": "legacy-cucumber-group",
            }
            dump_json(frozen / "manifest.json", [old_row])
            frozen_sha = gate.sha256_file(frozen / "manifest.json")
            gate.PINNED_BASE_MANIFEST_SHA256 = frozen_sha
            class_map_path = study / "class_mapping.v1.json"
            class_map = gate.read_json(class_map_path)
            class_map["base_dataset_manifest_sha256"] = frozen_sha
            dump_json(class_map_path, class_map)
            registry_path = study / "acceptance_registry.v1.json"
            registry = gate.read_json(registry_path)
            registry["frozen_base"]["manifest_sha256"] = frozen_sha
            dump_json(registry_path, registry)
            lock_path = study / "protocol_lock.json"
            lock = gate.read_json(lock_path)
            lock["frozen_base_manifest_sha256"] = frozen_sha
            dump_json(lock_path, lock)
            self.reseal(root, study, data)

            blockers = gate.validate(root, study)
            self.assertTrue(any("Legacy class-29 decisions missing for 1" in blocker for blocker in blockers))

            registry = gate.read_json(registry_path)
            registry["legacy_migrations"] = [{
                "legacy_record_ref": "train/legacy-cucumber.jpg",
                "legacy_class_id": 29,
                "frozen_source_sha256": old_row["prepared_sha256"],
                "frozen_annotation_sha256": old_row["label_sha256"],
                "group_id": old_row["group_id"],
                "decision": "exclude",
                "assigned_split": None,
                "acceptance_record_id": None,
                "final_class_ids": [],
                "decision_reason": "Synthetic fixture: migration evidence intentionally unavailable",
                "reviewer_id": "unit-test",
                "reviewed_utc": "2026-10-12T10:02:00+00:00",
                "evidence_references": ["evidence/review.txt"],
            }]
            dump_json(registry_path, registry)
            self.reseal(root, study, data)
            self.assertEqual(gate.validate(root, study), [])

    def test_migrated_legacy_cucumber_record_requires_exact_join(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "migration-evidence.txt"
            evidence.write_text("synthetic source-backed migration evidence\n", encoding="utf-8")
            ref = "train/legacy-cucumber.jpg"
            source_sha = "a" * 64
            annotation_sha = "b" * 64
            original = {ref: {"source_sha256": source_sha, "annotation_sha256": annotation_sha, "group_id": "leaf-group-1", "historical_split": "train"}}
            accepted = {"accepted-legacy-1": {
                "origin_kind": "inherited_train_record",
                "legacy_record_ref": ref,
                "source_id": "dataset_clean_v2",
                "source_member": ref,
                "source_class": OLD_NAMES[29],
                "source_sha256": source_sha,
                "annotation_sha256": "c" * 64,
                "group_id": "leaf-group-1",
                "legacy_record_ref": ref,
                "source_id": "dataset_clean_v2",
                "source_member": ref,
                "source_class": OLD_NAMES[29],
                "class_ids": [29],
                "split_eligibility": ["train"],
            }}
            registry = {"status": "locked_before_training", "legacy_migrations": [{
                "legacy_record_ref": ref,
                "legacy_class_id": 29,
                "frozen_source_sha256": source_sha,
                "frozen_annotation_sha256": annotation_sha,
                "group_id": "leaf-group-1",
                "decision": "migrate",
                "assigned_split": "train",
                "acceptance_record_id": "accepted-legacy-1",
                "final_class_ids": [29],
                "reviewed_annotation_sha256": "c" * 64,
                "decision_reason": "Synthetic fixture only",
                "reviewer_id": "unit-test",
                "reviewed_utc": "2026-10-12T10:03:00+00:00",
                "evidence_references": ["migration-evidence.txt"],
            }]}
            problems, decisions = gate.validate_legacy_migrations(registry, original, accepted, OLD_NAMES[:29] + ["Cucumber healthy"], root)
            self.assertEqual(problems, [])
            self.assertEqual(decisions[ref]["decision"], "migrate")

    def test_unsafe_relative_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                gate.safe_relative_path("../outside", root, root)
            with self.assertRaises(ValueError):
                gate.safe_relative_path(r"C:\outside", root, root)


if __name__ == "__main__":
    unittest.main()
