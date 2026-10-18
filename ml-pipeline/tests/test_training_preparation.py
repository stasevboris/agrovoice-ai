"""Data semantics, split isolation and conversions, using tiny CPU fixtures."""

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

from PIL import Image

ML = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML / "external"))
sys.path.insert(0, str(ML / "assistant"))
import prepare_corpus as assistant
import prepare_nonplant as nonplant
import prepare_selected as selected
import prepare_vision_alternatives as vision
import screen_unknown
import prepare_deepweeds
import attach_nonplant
sys.path.insert(0, str(ML / "gpu_kit/alternatives"))
import configure_dfine


def row(name="a.jpg", split="train", sha="a", group="a", **kwargs):
    return dict(image=name, split=split, image_sha256=sha, component_id=group,
                label_sha256="label", geometry="plantdoc_source_box", **kwargs)


class SplitTests(unittest.TestCase):
    def test_exact_duplicate_cross_split_quarantines_both(self):
        kept, rejected = vision.select_rows([row(), row("b.jpg", "test", sha="a", group="b")])
        self.assertEqual(kept, [])
        self.assertEqual({r["reason"] for r in rejected}, {"cross_split_group"})

    def test_component_does_not_cross_splits(self):
        kept, _ = vision.select_rows([row(), row("b.jpg", "val", sha="b")])
        self.assertEqual(kept, [])

    def test_conflicting_annotations_are_not_silently_chosen(self):
        first = row()
        second = dict(row("b.jpg"), label_sha256="different")
        kept, rejected = vision.select_rows([first, second])
        self.assertFalse(kept)
        self.assertTrue(all(r["reason"] == "conflicting_labels_same_image" for r in rejected))

    def test_repeats_and_auto_lesion_boxes_do_not_enter(self):
        kept, rejected = vision.select_rows([row(), row("repeat.jpg", repeat=1),
            dict(row("lesion.jpg", sha="b", group="b"), geometry="lesion_union_auto_needs_review")])
        self.assertEqual(len(kept), 1)
        self.assertEqual(len(rejected), 2)

    def test_unknown_transitive_group_cross_split_is_quarantined(self):
        rows = [{"image": str(i), "split": s, "source_label": "healthy"}
                for i, s in enumerate(["train", "train", "test"])]
        kept, excluded = screen_unknown.select_groups(rows, [0, 0, 0], [20, 20, 20])
        self.assertFalse(kept)
        self.assertEqual(len(excluded), 3)

    def test_unknown_group_touching_known_source_is_quarantined(self):
        rows = [{"image": str(i), "split": "train", "source_label": "healthy"} for i in range(2)]
        kept, excluded = screen_unknown.select_groups(rows, [0, 0], [20, 6])
        self.assertFalse(kept)
        self.assertTrue(all(r["reason"] == "near_duplicate_existing_source" for r in excluded))


class FormatTests(unittest.TestCase):
    def test_fleurs_quotes_are_literal_not_csv_escaping(self):
        text = '1\ta.wav\t"цитата"\t"текст\tт е к с т\t16000\tFEMALE\n'
        record = selected.fleurs_records(text)[0]
        self.assertEqual(record["text"], '"цитата"')
        self.assertEqual(record["num_samples"], 16000)

    def test_bad_fleurs_row_fails(self):
        with self.assertRaises(ValueError):
            selected.fleurs_records("a\tb\n")

    def test_missing_label_is_not_negative(self):
        rows = [{"ImageID": "positive", "LabelName": "/m/05s2s", "Confidence": "1"},
                {"ImageID": "missing", "LabelName": "something_else", "Confidence": "0"},
                {"ImageID": "negative", "LabelName": "/m/05s2s", "Confidence": "0"}]
        self.assertEqual(nonplant.eligible_ids(rows), {"negative"})

    def test_author_assignment_is_stable(self):
        self.assertEqual(nonplant.author_split("author1"), nonplant.author_split("author1"))

    def test_deepweeds_session_ignores_adjacent_capture_time(self):
        self.assertEqual(prepare_deepweeds.session_group("20160928-140314-0.jpg"),
                         prepare_deepweeds.session_group("20160928-140337-0.jpg"))
        self.assertNotEqual(prepare_deepweeds.session_group("20160928-140337-0.jpg"),
                            prepare_deepweeds.session_group("20160928-140337-1.jpg"))
        self.assertNotIn("8", prepare_deepweeds.SPECIES_SPLITS)

    def test_coco_and_crops_keep_each_leaf_class_and_crlf(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "source"
            (source / "images/train").mkdir(parents=True)
            (source / "labels/train").mkdir(parents=True)
            image = source / "images/train/two.jpg"
            Image.new("RGB", (100, 80), "green").save(image)
            text = "0 0.25 0.5 0.5 1\n1 0.75 0.5 0.5 1\n"
            (source / "labels/train/two.txt").write_bytes(text.replace("\n", "\r\n").encode())
            classes = [{"id": 0, "code": "crop__a", "crop": "crop"},
                       {"id": 1, "code": "crop__b", "crop": "crop"}]
            (source / "taxonomy_v1.json").write_text(json.dumps({"classes": classes}))
            item = row("two.jpg", sha=hashlib.sha256(image.read_bytes()).hexdigest())
            item.update(label_sha256=hashlib.sha256(text.encode()).hexdigest(), boxes=2, source="PlantDoc")
            (source / "manifest.jsonl").write_text(json.dumps(item) + "\n")
            out = Path(tmp) / "output"
            report = vision.build(source, out)
            coco = json.loads((out / "annotations/train.json").read_text())
            self.assertEqual([a["category_id"] for a in coco["annotations"]], [0, 1])
            self.assertEqual(coco["annotations"][1]["bbox"], [50.0, 0, 50.0, 80])
            self.assertEqual(report["counts"]["train_crops"], 2)
            self.assertTrue((out / "labels/train/two.txt").exists())

    def test_invalid_boxes_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bad.txt"
            for text in ("0 nan 0.5 0.2 0.2", "9 0.5 0.5 0.2 0.2", "0 0 0 1 1", ""):
                path.write_text(text)
                with self.assertRaises(ValueError):
                    vision.read_boxes(path, 100, 100, 2)

    def test_negative_augmentation_never_imports_val_test(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            positive = base / "positive"
            negative = base / "negative"
            (positive / "annotations").mkdir(parents=True)
            negative.mkdir()
            coco = {"images": [{"id": 5, "file_name": "images/train/a.jpg"}],
                    "annotations": [], "categories": [{"id": 0, "name": "leaf"}]}
            (positive / "annotations/train.json").write_text(json.dumps(coco))
            (positive / "annotations/val.json").write_text("unchanged")
            (positive / "data.yaml").write_text("train: images/train\nval: images/val\n")
            (negative / "report.json").write_text('{"training_ready": true}')
            rows = []
            for split in ("train", "val", "test"):
                Image.new("RGB", (10, 10)).save(negative / f"{split}.jpg")
                rows.append({"split": split, "image": f"{split}.jpg"})
            (negative / "manifest.jsonl").write_text("\n".join(json.dumps(r) for r in rows))
            report = attach_nonplant.attach(positive, negative)
            self.assertEqual(report["added_train_negatives"], 1)
            self.assertEqual((positive / "annotations/val.json").read_text(), "unchanged")
            self.assertFalse(report["val_test_modified"])

    def test_dfine_override_preserves_ids_and_disables_coco_remap(self):
        import yaml
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo = root / "D FINE"
            base = repo / "configs/dfine/dfine_hgnetv2_n_coco.yml"
            base.parent.mkdir(parents=True)
            base.write_text("num_classes: 80\n")
            data = root / "data"
            (data / "annotations").mkdir(parents=True)
            (data / "taxonomy_v1.json").write_text('{"classes": [{"id": 0}, {"id": 1}]}')
            for name in ("train", "val_field"):
                (data / "annotations" / f"{name}.json").write_text("{}")
            result = yaml.safe_load(configure_dfine.configure(repo, data, root / "override.yml"))
            self.assertFalse(result["remap_mscoco_category"])
            self.assertEqual(result["num_classes"], 2)
            self.assertTrue(result["val_dataloader"]["dataset"]["ann_file"].endswith("val_field.json"))


class AssistantTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.allowed = assistant.actions_from_kotlin(assistant.VOICE_SOURCE.read_text(encoding="utf-8"))
        cls.scenarios = json.loads(assistant.SOURCE.read_text(encoding="utf-8"))["scenarios"]
        cls.rows = assistant.build_records(cls.scenarios, cls.allowed)

    def test_all_scenarios_valid_and_groups_disjoint(self):
        group_splits = {}
        for row in self.rows:
            self.assertEqual(group_splits.setdefault(row["group"], row["split"]), row["split"])
            assistant.validate_target(row["state"], row["target"], self.allowed)

    def test_note_is_always_literal(self):
        for row in self.rows:
            if row["state"]["screen"] == "note":
                self.assertEqual(row["target"]["actions"], [])
                self.assertEqual(row["target"]["text"], row["utterance"])

    def test_duplicate_utterance_cannot_leak(self):
        first = self.scenarios[0]
        duplicate = dict(first, id="leak", split="test")
        with self.assertRaises(ValueError):
            assistant.build_records([first, duplicate], self.allowed)

    def test_invalid_action_and_missing_result_rejected(self):
        state = self.rows[0]["state"]
        for action in ("DELETE_ALL", "SAVE_INSPECTION"):
            with self.assertRaises(ValueError):
                assistant.validate_target(state, {"decision": "execute", "actions": [{"action": action, "value": ""}]}, self.allowed)


if __name__ == "__main__":
    unittest.main()
