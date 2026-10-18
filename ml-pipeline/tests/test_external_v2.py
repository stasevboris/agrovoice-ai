"""Unit tests for the external dataset builder (box policy, parts, mapping) without images."""
import json
from pathlib import Path
import sys
import unittest

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "external"))
import build_external_v2 as ext  # noqa: E402


def record(code, closeup=False):
    return {"id": "x", "class": code, "closeup": closeup, "domain": "field"}


class LeafBoxPolicyTest(unittest.TestCase):
    def test_source_boxes_are_used_as_is(self):
        item = record("tomato__late_blight") | {"source_boxes": [[0.5, 0.5, 0.4, 0.4]]}
        boxes, region, decision = ext.leaf_boxes(item, [])
        self.assertEqual(boxes, [[0.5, 0.5, 0.4, 0.4]])
        self.assertIsNone(region)
        self.assertEqual(decision, "source_leaf_boxes")

    def test_nothing_detected_closeup_gets_full_frame(self):
        boxes, _, decision = ext.leaf_boxes(record("corn__healthy", closeup=True), [])
        self.assertEqual(boxes, [ext.FULL_FRAME_BOX])
        self.assertEqual(decision, "closeup_full_frame")

    def test_nothing_detected_is_rejected(self):
        boxes, _, decision = ext.leaf_boxes(record("potato__late_blight"), [])
        self.assertEqual(boxes, [])
        self.assertEqual(decision, "no_leaf_detected")

    def test_healthy_photo_keeps_every_leaf(self):
        found = [[0.2, 0.2, 0.2, 0.2, 0.9], [0.7, 0.7, 0.05, 0.05, 0.5]]
        boxes, region, _ = ext.leaf_boxes(record("soybean__healthy"), found)
        self.assertEqual(len(boxes), 2)
        self.assertIsNone(region)

    def test_disease_photo_crops_to_dominant_leaf(self):
        found = [[0.3, 0.5, 0.4, 0.6, 0.9], [0.9, 0.1, 0.1, 0.1, 0.6]]
        boxes, region, decision = ext.leaf_boxes(record("potato__late_blight"), found)
        self.assertEqual(decision, "detector_dominant_leaves_cropped")
        self.assertEqual(len(boxes), 1)
        self.assertLess(region[2], 0.85)  # the small leaf on the right is cut away

    def test_disease_photo_with_small_leaf_inside_crop_is_rejected(self):
        found = [[0.5, 0.5, 0.8, 0.8, 0.9], [0.5, 0.5, 0.1, 0.1, 0.6]]
        boxes, _, decision = ext.leaf_boxes(record("potato__late_blight"), found)
        self.assertEqual(boxes, [])
        self.assertEqual(decision, "unlabelled_leaf_inside_crop")

    def test_reframe_maps_box_into_crop(self):
        boxes = ext.reframe([[0.5, 0.5, 0.2, 0.2]], [0.4, 0.4, 0.6, 0.6])
        np.testing.assert_allclose(boxes[0], [0.5, 0.5, 1.0, 1.0])


class PartsAndHashTest(unittest.TestCase):
    def test_parts_are_class_balanced(self):
        train = [{"id": f"a{i}", "class": "a", "domain": "lab"} for i in range(10)]
        train += [{"id": f"b{i}", "class": "b", "domain": "field"} for i in range(10)]
        ext.assign_parts(train, part_size=4)
        first = [r["class"] for r in train if r["part"] == "part01"]
        self.assertEqual(sorted(first), ["a", "a", "b", "b"])

    def test_field_images_come_first_within_class(self):
        train = [{"id": "lab", "class": "a", "domain": "lab"}, {"id": "field", "class": "a", "domain": "field"}]
        ordered = ext.assign_parts(train, part_size=1)
        self.assertEqual(ordered[0]["id"], "field")

    def test_min_distance_finds_rotated_copy(self):
        index = np.array([0b1011, 1 << 40], dtype=np.uint64)
        queries = np.array([[0xFFFF] + [0b1010] + [0] * 6], dtype=np.uint64)
        self.assertEqual(int(ext.min_distance(queries, index)[0]), 1)

    def test_duplicate_groups(self):
        hashes = np.array([[0] * 8, [1] * 8, [2 ** 64 - 1] * 8], dtype=np.uint64)
        groups = ext.group_duplicates(hashes)
        self.assertEqual(groups[0], groups[1])
        self.assertNotEqual(groups[0], groups[2])


class MappingTest(unittest.TestCase):
    def test_every_mapped_class_is_in_taxonomy(self):
        mapping = json.loads((Path(ext.HERE) / "mapping_v2.json").read_text(encoding="utf-8"))
        codes = ext.taxonomy_codes()
        for source, spec in mapping["sources"].items():
            tables = [spec.get("folders", {})]
            tables += [spec[k]["map"] for k in ("csv_labels", "yolo_boxes") if k in spec]
            for table in tables:
                for code in table.values():
                    self.assertIn(code, codes, f"{source}: {code}")
            self.assertIn(spec["domain"], ("field", "lab"))


if __name__ == "__main__":
    unittest.main()
