"""Unit tests for the ext_v2 addon packer: combined yaml paths, smoke lists, dataset plans."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pack_ext_addon as addon  # noqa: E402

NAMES = {0: "a__healthy", 1: "a__rust"}


def make_v1(folder: Path, name: str):
    folder.mkdir(parents=True)
    (folder/"data.yaml").write_text(yaml.safe_dump({"train": "images/train", "val": "val_select.txt", "test": "images/test", "names": NAMES}))
    for domain in ("field", "lab"):
        config = {"train": "images/train", "val": f"val_{domain}.txt", "test": f"test_{domain}.txt", "names": NAMES}
        (folder/f"data_{domain}.yaml").write_text(yaml.safe_dump(config))
    (folder/"taxonomy_v1.json").write_text("{}")
    (folder/"report.json").write_text(json.dumps({"dataset": name, "fingerprint": name, "splits": {"train": 10}}))


def make_ext(folder: Path, lab_val=""):
    folder.mkdir(parents=True)
    report = {"dataset": "ext_v2", "fingerprint": "ext",
              "parts": {"part01": {"images": 3}, "part02": {"images": 2}, "val": {"images": 1}, "test": {"images": 1}}}
    (folder/"report.json").write_text(json.dumps(report))
    (folder/"val_field.txt").write_text("./images/val/x.jpg\n")
    (folder/"val_lab.txt").write_text(lab_val)


class CombinedYamlTest(unittest.TestCase):
    def test_paths_point_to_sibling_datasets(self):
        with tempfile.TemporaryDirectory() as tmp:
            v1 = Path(tmp)/"v1"
            make_v1(v1, "allcrops_v1")
            files = addon.combined_yamls(v1, "allcrops_v1", ["../ext_v2/images/part01"], "../ext_v2/val.txt",
                                         "../ext_v2/test.txt", {"field": ("../ext_v2/val_field.txt", "../ext_v2/test_field.txt")})
        self.assertEqual(files["data.yaml"]["train"], ["../allcrops_v1/images/train", "../ext_v2/images/part01"])
        self.assertEqual(files["data.yaml"]["val"], ["../allcrops_v1/val_select.txt", "../ext_v2/val.txt"])
        self.assertEqual(files["data_field.yaml"]["val"], "../allcrops_v1/val_field.txt")
        self.assertEqual(files["data_ext_field.yaml"]["test"], "../ext_v2/test_field.txt")
        self.assertNotIn("data_ext_lab.yaml", files)
        self.assertEqual(files["data_lab.yaml"]["names"], NAMES)


class PlanTest(unittest.TestCase):
    def test_one_dataset_per_part_count_plus_smoke(self):
        with tempfile.TemporaryDirectory() as tmp:
            make_v1(Path(tmp)/"v1", "allcrops_v1")
            make_v1(Path(tmp)/"smoke", "allcrops_v1_smoke")
            make_ext(Path(tmp)/"ext", lab_val="./images/val/y.jpg\n")
            plans = addon.plan_datasets(Path(tmp)/"v1", Path(tmp)/"smoke", Path(tmp)/"ext")
        self.assertEqual(sorted(plans), ["allcrops_v2_all", "allcrops_v2_p1", "allcrops_v2_smoke"])
        first = yaml.safe_load(plans["allcrops_v2_p1"]["data.yaml"])
        self.assertEqual(first["train"][-1], "../ext_v2/images/part01")
        report = json.loads(plans["allcrops_v2_all"]["report.json"])
        self.assertEqual(report["ext_train_images"], 5)
        self.assertEqual(report["dataset"], "allcrops_v2_all")
        self.assertIn("data_ext_lab.yaml", plans["allcrops_v2_smoke"])
        fingerprints = {json.loads(p["report.json"])["fingerprint"] for p in plans.values()}
        self.assertEqual(len(fingerprints), 3)


class SmokeListTest(unittest.TestCase):
    def test_one_image_per_class_from_part01_and_val(self):
        rows = [
            {"image_path": "images/part01/a1.jpg", "class": "a", "split": "train", "domain": "field", "part": "part01"},
            {"image_path": "images/part01/a2.jpg", "class": "a", "split": "train", "domain": "field", "part": "part01"},
            {"image_path": "images/part02/b1.jpg", "class": "b", "split": "train", "domain": "field", "part": "part02"},
            {"image_path": "images/val/a3.jpg", "class": "a", "split": "val", "domain": "lab", "part": None},
            {"image_path": "images/test/a4.jpg", "class": "a", "split": "test", "domain": "field", "part": None},
        ]
        lists = addon.smoke_lists(rows)
        self.assertEqual(lists["smoke_train.txt"], ["images/part01/a1.jpg"])
        self.assertEqual(lists["smoke_val_lab.txt"], ["images/val/a3.jpg"])
        self.assertEqual(lists["smoke_val_field.txt"], [])


if __name__ == "__main__":
    unittest.main()
