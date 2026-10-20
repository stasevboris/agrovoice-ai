import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "gpu_kit/alternatives/threshold_mobile_comparison.py"
sys.path.insert(0, str(SOURCE.parent))
spec = importlib.util.spec_from_file_location("threshold_mobile_comparison", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ThresholdComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.taxonomy = self.root / "taxonomy.json"
        self.write(self.taxonomy, {"classes": [{"crop": crop} for crop in ("apple", "corn", "orange")]})
        self.annotations = self.root / "val_field.json"
        self.write(self.annotations, {
            "images": [{"id": i, "file_name": f"images/val/{i}.jpg", "domain": "field"} for i in (1, 2)],
            "annotations": [
                {"image_id": 1, "category_id": 0, "bbox": [0, 0, 10, 10]},
                {"image_id": 1, "category_id": 2, "bbox": [20, 20, 10, 10]},
                {"image_id": 2, "category_id": 0, "bbox": [0, 0, 10, 10]},
            ],
        })
        self.run = self.root / "run"
        self.run.mkdir()
        def prediction(crop, score, box):
            return {"crop": crop, "score": score, "box": box}
        self.rows = [
            {"source": "field", "id": "1", "image": "images/val/1.jpg", "predictions": [
                prediction("corn", .9, [0, 0, 10, 10]),
                prediction("orange", .8, [20, 20, 30, 30])]},
            {"source": "field", "id": "2", "image": "images/val/2.jpg", "predictions": [
                prediction("apple", .5, [0, 0, 10, 10])]},
            {"source": "unknown", "id": "a", "image": "images/validation/a.jpg", "predictions": [
                prediction("apple", .75, [0, 0, 10, 10]), prediction("corn", .5, [20, 20, 30, 30])]},
            {"source": "unknown", "id": "b", "image": "images/val/b.jpg", "predictions": []},
        ]
        self.report = {
            "status": "complete", "test_evaluated": False, "common_crops": ["apple", "corn"],
            "field_annotations": str(self.annotations), "field_annotations_sha256": module.sha256(self.annotations),
            "taxonomy_sha256": module.sha256(self.taxonomy), "planned_images": {"field": 2, "unknown": 2},
            "protocol": {"confidence": .25, "nms_iou": .7, "matching_iou": .5,
                         "native_class_nms": True, "crop_mapping_after_native_winner": True},
            "models": [{"name": "baseline", "sha256": "a" * 64, "complete": True,
                        "class_crops": ["apple", "corn", "orange"],
                        "field": {"images": 2, "gt": 2, "tp": 1, "fp": 1, "fn": 1,
                                  "localized": 2, "ignored_gt": 1, "ignored_predictions": 1},
                        "acceptance": {"unknown": {"images": 2, "accepted": 1}}}],
        }
        self.save()

    def write(self, path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def save(self, folder=None):
        folder = folder or self.run
        self.write(folder / "report.json", self.report)
        (folder / "predictions.jsonl").write_text("".join(
            json.dumps(dict(row, model="baseline")) + "\n" for row in self.rows), encoding="utf-8")

    def test_threshold_tradeoff_counts_images_and_preserves_unsupported_gt(self):
        result = module.analyze([self.run], self.taxonomy)
        points = {point["threshold"]: point for point in result["models"][0]["thresholds"]}
        self.assertEqual(points[.25]["field"]["recall"], .5)
        self.assertEqual(points[.75]["field"]["recall"], 0)
        self.assertEqual(points[.75]["field"]["ignored_predictions"], 1)
        self.assertEqual(points[.75]["acceptance"]["unknown"], {"images": 2, "accepted": 1, "rate": .5})
        self.assertEqual(points[.9]["acceptance"]["unknown"]["accepted"], 0)
        self.assertEqual(points[.9]["field"]["fp"], 1)  # The cutoff is inclusive.
        self.assertEqual(points[.95]["field"]["fn"], 2)

    def test_no_predictions_has_undefined_precision_and_zero_recall(self):
        result = module.analyze([self.run], self.taxonomy)
        field = result["models"][0]["thresholds"][-1]["field"]
        self.assertEqual(field["tp"] + field["fp"], 0)
        self.assertIsNone(field["precision"])
        self.assertEqual(field["recall"], 0)
        self.assertEqual(field["f1"], 0)

    def test_repeated_model_is_deduplicated_by_name_and_weights(self):
        other = self.root / "other"
        other.mkdir()
        self.save(other)
        result = module.analyze([self.run, other], self.taxonomy)
        self.assertEqual(len(result["models"]), 1)
        self.assertEqual(len(result["models"][0]["source_runs"]), 2)
        self.assertEqual(len(result["inputs"]), 2)
        self.assertEqual(result["inputs"][0]["predictions_sha256"], module.sha256(self.run / "predictions.jsonl"))

    def test_current_runner_per_model_decode_metadata_is_supported(self):
        self.report["protocol"]["native_class_nms"] = "see each model decode_policy"
        for policy in (
            {"native_class_nms": True, "output": "cxcywh_class_scores", "box_mapping": "letterbox_scale"},
            {"native_class_nms": False, "output": "xyxy_score_class", "box_mapping": "rounded_axis_scales"},
        ):
            with self.subTest(policy=policy):
                self.report["models"][0]["decode_policy"] = policy
                self.save()
                result = module.analyze([self.run], self.taxonomy)
                self.assertEqual(result["models"][0]["decode_policy"], policy)

    def test_current_runner_requires_supported_decode_policy(self):
        self.report["protocol"]["native_class_nms"] = "see each model decode_policy"
        for policy in (None, {"native_class_nms": True, "output": "xyxy_score_class"}):
            self.report["models"][0]["decode_policy"] = policy
            self.save()
            with self.subTest(policy=policy), self.assertRaises(ValueError):
                module.analyze([self.run], self.taxonomy)

    def test_incomplete_duplicate_and_test_inputs_fail_closed(self):
        original = copy.deepcopy(self.report)
        for change in ({"status": "partial"}, {"test_evaluated": True}, {"planned_images": {"field": 2, "unknown": 3}}):
            self.report = dict(original, **change)
            self.save()
            with self.subTest(change=change), self.assertRaises(ValueError):
                module.analyze([self.run], self.taxonomy)
        self.report = original
        self.rows.append(self.rows[-1])
        self.save()
        with self.assertRaises(ValueError):
            module.analyze([self.run], self.taxonomy)

    def test_changed_annotation_hash_and_wrong_base_cutoff_are_rejected(self):
        self.report["field_annotations_sha256"] = "0" * 64
        self.save()
        with self.assertRaises(ValueError):
            module.analyze([self.run], self.taxonomy)
        self.report["field_annotations_sha256"] = module.sha256(self.annotations)
        self.report["protocol"]["confidence"] = .5
        self.save()
        with self.assertRaises(ValueError):
            module.analyze([self.run], self.taxonomy)

    def test_same_denominators_but_different_images_are_rejected(self):
        other = self.root / "other"
        other.mkdir()
        self.rows[-1] = dict(self.rows[-1], id="other", image="images/val/other.jpg")
        self.save(other)
        with self.assertRaises(ValueError):
            module.analyze([self.run, other], self.taxonomy)

    def test_same_model_with_different_predictions_is_not_silently_deduplicated(self):
        other = self.root / "other"
        other.mkdir()
        self.rows[2]["predictions"][0]["score"] = .8
        self.save(other)
        with self.assertRaises(ValueError):
            module.analyze([self.run, other], self.taxonomy)

    def test_equal_names_with_different_weights_remain_separate(self):
        other = self.root / "other"
        other.mkdir()
        self.report["models"][0]["sha256"] = "b" * 64
        self.save(other)
        result = module.analyze([self.run, other], self.taxonomy)
        self.assertEqual(len(result["models"]), 2)

    def test_model_completion_and_actual_validation_paths_are_required(self):
        self.report["models"][0]["complete"] = False
        self.save()
        with self.assertRaises(ValueError):
            module.analyze([self.run], self.taxonomy)
        self.report["models"][0]["complete"] = True
        self.rows[-1]["image"] = "images/test/b.jpg"
        self.save()
        with self.assertRaises(ValueError):
            module.analyze([self.run], self.taxonomy)

    def test_summary_must_match_actual_saved_predictions(self):
        self.report["models"][0]["acceptance"]["unknown"]["accepted"] = 2
        self.save()
        with self.assertRaisesRegex(ValueError, "baseline"):
            module.analyze([self.run], self.taxonomy)


if __name__ == "__main__":
    unittest.main()
