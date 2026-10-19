"""Unit tests for the all-crops dataset builder and the GPU kit queue (no images are written)."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_allcrops_v1 as build  # noqa: E402


class TaxonomyTest(unittest.TestCase):
    def setUp(self):
        self.taxonomy = build.Taxonomy()

    def test_ids_contiguous_and_codes_unique(self):
        ids = [c["id"] for c in self.taxonomy.classes]
        self.assertEqual(ids, list(range(len(ids))))
        self.assertEqual(len({c["code"] for c in self.taxonomy.classes}), len(ids))

    def test_every_class_has_russian_label_and_known_crop(self):
        crops = self.taxonomy.data["crops_ru"]
        for c in self.taxonomy.classes:
            self.assertTrue(c["ru"].strip(), c["code"])
            self.assertIn(c["crop"], crops, c["code"])
            self.assertTrue(c["code"].startswith(c["crop"]+"__"), c["code"])

    def test_plantdoc_legacy_ids_all_mapped(self):
        for legacy in range(30):
            self.assertIsNotNone(self.taxonomy.class_id("PlantDocLegacy", str(legacy)))

    def test_unknown_source_class_is_an_error(self):
        with self.assertRaises(KeyError):
            self.taxonomy.class_id("PlantVillage", "Banana___healthy")

    def test_explicit_exclusion_returns_none(self):
        self.assertIsNone(self.taxonomy.class_id("OLID", "tomato__JAS_MIT"))

    @unittest.skipUnless(build.QUEUE.exists(), "review queue not available")
    def test_every_queue_class_is_mapped_or_excluded(self):
        seen = {(r["source"], r["source_class"]) for r in build.read_jsonl(build.QUEUE)}
        for source, source_class in seen:
            self.taxonomy.class_id(source, source_class)  # raises if unmapped


class SplitTest(unittest.TestCase):
    def record(self, **kw):
        base = dict(split_rule="no_historical_split_restriction", overlap_decision="no_match", historical_splits=[],
                    component_id="split:abc")
        return base | kw

    def test_quarantine_is_excluded(self):
        self.assertIsNone(build.assign_split(self.record(split_rule="quarantine_source_label_conflict_component"), 1))

    def test_historical_holdout_goes_to_val_never_train(self):
        self.assertEqual(build.assign_split(self.record(historical_splits=["val_aux"]), 1), "val")

    def test_historical_train_family_stays_train(self):
        self.assertEqual(build.assign_split(self.record(historical_splits=["train"]), 1), "train")

    def test_same_component_same_split_and_rough_proportions(self):
        splits = [build.assign_split(self.record(component_id=f"split:{i}"), 7) for i in range(4000)]
        self.assertEqual(splits[:50], [build.assign_split(self.record(component_id=f"split:{i}"), 7) for i in range(50)])
        self.assertAlmostEqual(splits.count("train")/len(splits), .8, delta=.03)
        self.assertAlmostEqual(splits.count("test")/len(splits), .1, delta=.03)


class BoxTest(unittest.TestCase):
    def test_union_covers_all_boxes_and_stays_inside_image(self):
        box = build.union_box([[.2, .2, .1, .1], [.8, .7, .2, .2]], margin=.03)
        left, top, right, bottom = box[0]-box[2]/2, box[1]-box[3]/2, box[0]+box[2]/2, box[1]+box[3]/2
        self.assertLessEqual(left, .15); self.assertLessEqual(top, .15)
        self.assertGreaterEqual(right, .9); self.assertGreaterEqual(bottom, .8)
        self.assertTrue(0 <= left and right <= 1 and 0 <= top and bottom <= 1)

    def test_zenodo_lesion_classes_become_one_flagged_leaf_box(self):
        boxes, method = build.zenodo_boxes("downy_mildew", [[0, .3, .3, .05, .05], [0, .6, .6, .05, .05]])
        self.assertEqual(len(boxes), 1)
        self.assertEqual(method, "lesion_union_auto_needs_review")

    def test_zenodo_anthracnose_keeps_large_leaf_boxes(self):
        boxes, method = build.zenodo_boxes("anthracnose", [[0, .5, .5, .8, .8], [0, .2, .2, .05, .05]])
        self.assertEqual(boxes, [[.5, .5, .8, .8]])
        self.assertEqual(method, "source_leaf_boxes")

    def test_clip_box_keeps_box_inside(self):
        box = build.clip_box([.95, .5, .2, .4])
        self.assertAlmostEqual(box[0]+box[2]/2, 1.0)
        self.assertTrue(build.valid_box(box))


class ListsTest(unittest.TestCase):
    def test_val_select_keeps_all_field_and_caps_lab_per_class(self):
        import tempfile
        records = [dict(image=f"pd_{i}.jpg", split="val", source="PlantDoc", classes=[1]) for i in range(10)]
        records += [dict(image=f"pv_{i}.jpg", split="val", source="PlantVillage", classes=[2]) for i in range(60)]
        records += [dict(image=f"pv_t{i}.jpg", split="test", source="PlantVillage", classes=[2]) for i in range(5)]
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder)
            count = build.write_lists(out, records, build.Taxonomy(), seed=1)
            selected = (out/"val_select.txt").read_text(encoding="utf-8").split()
            self.assertEqual(count, 10+build.LAB_VAL_SELECT_PER_CLASS)
            self.assertEqual(sum("pd_" in line for line in selected), 10)
            self.assertIn("val: val_select.txt", (out/"data.yaml").read_text(encoding="utf-8"))
            self.assertEqual(len((out/"test_lab.txt").read_text(encoding="utf-8").split()), 5)


class KitQueueTest(unittest.TestCase):
    def test_queue_runs_are_well_formed(self):
        queue = json.loads((build.ROOT/"ml-pipeline/gpu_kit/queue.json").read_text(encoding="utf-8"))
        names = [run["name"] for run in queue["runs"]]
        self.assertEqual(len(names), len(set(names)))
        for run in queue["runs"]:
            self.assertIn(run["model"], ("yolo26n.pt", "yolo26s.pt", "yolo26m.pt"))
            self.assertGreater(run["hours"], 0)

    @unittest.skipUnless((build.ROOT / "ml-pipeline/yolo26s.pt").exists(), "Local model weights are not included in git")
    def test_local_queue_weights_are_complete(self):
        queue = json.loads((build.ROOT / "ml-pipeline/gpu_kit/queue.json").read_text(encoding="utf-8"))
        for run in queue["runs"]:
            self.assertTrue((build.ROOT/"ml-pipeline"/run["model"]).exists(), run["model"])


if __name__ == "__main__":
    unittest.main()
