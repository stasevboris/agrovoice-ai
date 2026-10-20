import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest

import numpy as np
from PIL import Image

try:
    import onnx
    import onnxruntime
    from onnx import TensorProto, helper, numpy_helper
except ImportError:
    onnx = None


SOURCE = Path(__file__).resolve().parents[1] / "gpu_kit/alternatives/compare_mobile_detectors.py"
sys.path.insert(0, str(SOURCE.parent))
spec = importlib.util.spec_from_file_location("compare_mobile_detectors", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MobileComparisonTest(unittest.TestCase):
    def test_detr_keeps_duplicate_queries_without_nms(self):
        output = np.zeros((1, 300, 6), dtype=np.float32)
        output[0, :2] = [[10, 10, 20, 20, .8, 0], [10, 10, 20, 20, .9, 0]]
        rows = module.decode_detr(output, (100, 100, 1, 0, 0), 1)
        self.assertEqual(len(rows), 2)
        np.testing.assert_allclose(rows[:, 4], [.9, .8])

    def test_detr_uses_rounded_axis_scales_and_preserves_class_id(self):
        output = np.zeros((1, 300, 6), dtype=np.float32)
        output[0, 0] = [0, 69, 208, 207.5, .9, 1]
        rows = module.decode_detr(output, (3, 2, 416 / 3, 0, 69), 2)
        np.testing.assert_allclose(rows[0, :4], [0, 0, 1.5, 1])
        self.assertEqual(rows[0, 5], 1)

    def test_detr_discards_unknown_classes_and_invalid_boxes(self):
        output = np.zeros((1, 300, 6), dtype=np.float32)
        output[0, :4] = [[10, 10, 20, 20, .9, -1], [10, 10, 20, 20, .9, 2],
                         [20, 10, 10, 20, .9, 0], [10, 10, 20, 20, .25, 1]]
        rows = module.decode_detr(output, (100, 100, 1, 0, 0), 2)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0, 5], 1)

    def test_detr_rejects_wrong_shape_and_nonfinite_output(self):
        for shape in ((1, 6, 300), (1, 299, 6), (300, 6)):
            with self.subTest(shape=shape), self.assertRaises(ValueError):
                module.decode_detr(np.zeros(shape, dtype=np.float32), (100, 100, 1, 0, 0), 1)
        output = np.zeros((1, 300, 6), dtype=np.float32)
        output[0, 0, 0] = np.nan
        with self.assertRaises(ValueError):
            module.decode_detr(output, (100, 100, 1, 0, 0), 1)

    def test_cli_rejects_unbounded_runtime_before_creating_output(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "comparison"
            for seconds in ("nan", "inf", "0"):
                with self.subTest(seconds=seconds):
                    result = subprocess.run(
                        [sys.executable, "-X", "utf8", str(SOURCE), "--candidate", "missing=missing.onnx",
                         "--app-manifest", str(Path(folder) / "missing_manifest.json"),
                         "--out", str(output), "--max-seconds", seconds],
                        capture_output=True, text=True, encoding="utf-8",
                    )
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertIn("max-seconds", result.stderr)
                    self.assertFalse(output.exists())

    def test_native_winner_is_preserved_before_crop_mapping(self):
        output = np.array([[[20], [20], [10], [10], [.8], [.9]]], dtype=np.float32)
        rows = module.decode(output, (100, 100, 1, 0, 0))
        self.assertEqual(int(rows[0, 5]), 1)
        predictions = module.crop_predictions(rows, ["apple", "orange"])
        result = module.score_frame(
            [{"crop": "apple", "box": [15, 15, 25, 25]}], predictions, {"apple"})
        self.assertEqual((result["tp"], result["fp"], result["fn"]), (0, 1, 1))
        self.assertEqual(result["confusion"], {"apple->orange": 1})

    def test_nms_uses_native_classes_even_when_crops_are_equal(self):
        output = np.array([[[20, 20, 20], [20, 20, 20], [10, 10, 10], [10, 10, 10],
                            [.9, .8, .1], [.1, .1, .7]]], dtype=np.float32)
        rows = module.decode(output, (100, 100, 1, 0, 0))
        self.assertEqual(rows[:, 5].tolist(), [0, 1])
        result = module.score_frame(
            [{"crop": "apple", "box": [15, 15, 25, 25]}],
            module.crop_predictions(rows, ["apple", "apple"]), {"apple"})
        self.assertEqual((result["tp"], result["fp"], result["fn"]), (1, 1, 0))

    def test_missed_and_wrong_crops_count_as_false_negatives(self):
        ground_truth = [{"crop": "apple", "box": [0, 0, 10, 10]},
                        {"crop": "corn", "box": [20, 20, 30, 30]}]
        result = module.score_frame(ground_truth, [], {"apple", "corn"})
        self.assertEqual((result["gt"], result["localized"], result["fn"]), (2, 0, 2))
        self.assertEqual(result["confusion"], {"apple->__missed__": 1, "corn->__missed__": 1})

    def test_matching_includes_exact_iou_threshold(self):
        result = module.score_frame(
            [{"crop": "apple", "box": [0, 0, 3, 1]}],
            [{"crop": "apple", "box": [1, 0, 4, 1], "score": .9}], {"apple"})
        self.assertEqual(result["tp"], 1)

    def test_unsupported_gt_is_ignored_without_ignoring_background_predictions(self):
        gt = [{"crop": "orange", "box": [0, 0, 10, 10]}]
        predictions = [{"crop": "orange", "box": [0, 0, 10, 10], "score": .9},
                       {"crop": "orange", "box": [20, 20, 30, 30], "score": .8}]
        result = module.score_frame(gt, predictions, {"apple"})
        self.assertEqual((result["ignored_gt"], result["ignored_predictions"], result["fp"]), (1, 1, 1))

    def test_invalid_boxes_and_padding_only_boxes_are_not_accepted(self):
        output = np.array([[[5, 20, 20], [5, 20, 20], [4, -5, 5], [4, 5, 5],
                            [.9, .9, .1]]], dtype=np.float32)
        self.assertEqual(len(module.decode(output, (50, 50, 1, 10, 10))), 0)

    def test_preprocessing_preserves_thin_image_and_rgb_order(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "thin.png"
            Image.new("RGB", (1, 2000), (0, 255, 0)).save(path)
            batch, geometry = module.preprocess(path)
        self.assertEqual(batch.shape, (1, 3, 416, 416))
        self.assertEqual(geometry, (1, 2000, .208, 207, 0))
        np.testing.assert_array_equal(batch[0, :, 0, 207], [0, 1, 0])

    def test_legacy_unknown_condition_maps_only_to_crop(self):
        taxonomy = {"classes": [{"code": "cucumber__healthy", "crop": "cucumber"}],
                    "source_mapping": {"PlantDocLegacy": {"0": "cucumber__healthy"}}}
        self.assertEqual(module.legacy_crops(["Cucumber leaf (condition unknown)"], taxonomy), ["cucumber"])

    def test_unknown_loader_uses_only_validation_rows(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for dataset, filename in (("dataset_nonplant_openimages_v2", "manifest.jsonl"),
                                      ("dataset_unknown_beans_screened_v1", "val.jsonl"),
                                      ("dataset_unknown_deepweeds_v1", "val.jsonl")):
                target = root / dataset
                target.mkdir()
                rows = [{"image": f"{split}.jpg", "split": split} for split in ("train", "val", "test")]
                (target / filename).write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
            sources = module.validation_sources(root)
            self.assertTrue(all([Path(path).name for path in paths] == ["val.jpg"]
                                for paths in sources.values()))


@unittest.skipIf(onnx is None, "Synthetic ONNX smoke requires optional ONNX dependencies")
class MobileComparisonSmokeTest(unittest.TestCase):
    def add_detr(self, args):
        output = np.zeros((1, 300, 6), dtype=np.float32)
        output[0, :2] = [[104, 104, 312, 312, .9, 0], [104, 104, 312, 312, .8, 0]]
        graph = helper.make_graph(
            [helper.make_node("Constant", [], ["output0"], value=numpy_helper.from_array(output))],
            "constant_detr", [helper.make_tensor_value_info("images", TensorProto.FLOAT, [1, 3, 416, 416])],
            [helper.make_tensor_value_info("output0", TensorProto.FLOAT, [1, 300, 6])],
        )
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
        model.ir_version = 9
        path = args.app_manifest.parent / "detr.onnx"
        onnx.save(model, path)
        manifest = json.loads(args.app_manifest.read_text())
        manifest["models"].append({"id": "detr", "name": "Synthetic DETR", "family": "detr", "side": 416,
                                   "asset": "models/detr.onnx", "sha256": module.sha256(path),
                                   "provisional": True, "license": "Synthetic fixture"})
        args.app_manifest.write_text(json.dumps(manifest), encoding="utf-8")
        args.candidate = []
        args.app_alternative = ["detr"]

    def fixture(self, root):
        assets = root / "assets/models"
        assets.mkdir(parents=True)
        output = np.zeros((1, 5, 3549), dtype=np.float32)
        output[0, :, 0] = [208, 208, 208, 208, .9]
        graph = helper.make_graph(
            [helper.make_node("Constant", [], ["output0"], value=numpy_helper.from_array(output))],
            "constant_detector", [helper.make_tensor_value_info("images", TensorProto.FLOAT, [1, 3, 416, 416])],
            [helper.make_tensor_value_info("output0", TensorProto.FLOAT, [1, 5, 3549])],
        )
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
        model.ir_version = 9
        onnx.save(model, assets / "app.onnx")
        manifest = {"classes": ["Apple leaf"], "models": [{"id": "app", "family": "yolo",
                    "asset": "models/app.onnx", "sha256": module.sha256(assets / "app.onnx")}]}
        (assets / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        taxonomy = {"classes": [{"id": 0, "code": "apple__healthy", "crop": "apple"}],
                    "source_mapping": {"PlantDocLegacy": {"0": "apple__healthy"}}}
        (root / "taxonomy.json").write_text(json.dumps(taxonomy), encoding="utf-8")
        data = root / "data"
        (data / "annotations").mkdir(parents=True)
        (data / "images/val").mkdir(parents=True)
        Image.new("RGB", (100, 100), (0, 255, 0)).save(data / "images/val/leaf.png")
        field = {"images": [{"id": 1, "file_name": "images/val/leaf.png", "domain": "field"}],
                 "annotations": [{"image_id": 1, "category_id": 0, "bbox": [25, 25, 50, 50]}]}
        (data / "annotations/val_field.json").write_text(json.dumps(field), encoding="utf-8")
        for dataset, filename in (("dataset_nonplant_openimages_v2", "manifest.jsonl"),
                                  ("dataset_unknown_beans_screened_v1", "val.jsonl"),
                                  ("dataset_unknown_deepweeds_v1", "val.jsonl")):
            target = root / dataset
            target.mkdir()
            Image.new("RGB", (100, 100), (255, 0, 0)).save(target / "unknown.png")
            (target / filename).write_text(json.dumps({"image": "unknown.png", "split": "val"}), encoding="utf-8")
        return SimpleNamespace(app_manifest=assets / "manifest.json", app_model="app",
                               candidate=[f"candidate={assets / 'app.onnx'}"], taxonomy=root / "taxonomy.json",
                               data=data, unknown_root=root, out=root / "comparison", max_seconds=30,
                               deadline=None, threads=1)

    def test_report_and_predictions_use_same_frames_for_both_models(self):
        with tempfile.TemporaryDirectory() as folder:
            args = self.fixture(Path(folder))
            report = module.run(args)
            stored = json.loads((args.out / "report.json").read_text(encoding="utf-8"))
            records = [json.loads(line) for line in (args.out / "predictions.jsonl").read_text().splitlines()]
        self.assertEqual(stored["status"], "complete")
        self.assertFalse(stored["test_evaluated"])
        self.assertEqual(len(records), 8)
        for model in report["models"]:
            self.assertEqual((model["field"]["tp"], model["field"]["fp"], model["field"]["fn"]), (1, 0, 0))
            self.assertTrue(all(result == {"images": 1, "accepted": 1} for result in model["acceptance"].values()))
        self.assertEqual(report["models"][0]["sha256"], report["models"][1]["sha256"])

    def test_bundled_detr_cli_without_yolo_candidate_records_native_decode(self):
        with tempfile.TemporaryDirectory() as folder:
            args = self.fixture(Path(folder))
            self.add_detr(args)
            result = subprocess.run(
                [sys.executable, "-X", "utf8", str(SOURCE), "--app-manifest", str(args.app_manifest),
                 "--app-model", "app", "--app-alternative", "detr", "--taxonomy", str(args.taxonomy),
                 "--data", str(args.data), "--unknown-root", str(args.unknown_root), "--out", str(args.out),
                 "--max-seconds", "30", "--threads", "1"],
                capture_output=True, text=True, encoding="utf-8",
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads((args.out / "report.json").read_text())
        self.assertEqual([model["family"] for model in report["models"]], ["yolo", "detr"])
        detr = report["models"][1]
        self.assertFalse(detr["decode_policy"]["native_class_nms"])
        self.assertEqual((detr["field"]["tp"], detr["field"]["fp"], detr["field"]["fn"]), (1, 1, 0))
        self.assertEqual(detr["license"], "Synthetic fixture")
        self.assertTrue(detr["provisional"])

    def test_bundled_alternative_must_match_its_manifest_hash(self):
        with tempfile.TemporaryDirectory() as folder:
            args = self.fixture(Path(folder))
            self.add_detr(args)
            (args.app_manifest.parent / "detr.onnx").write_bytes(b"damaged")
            with self.assertRaisesRegex(ValueError, "SHA256"):
                module.run(args)
            self.assertFalse(args.out.exists())

    def test_expired_deadline_writes_partial_report_without_inference(self):
        with tempfile.TemporaryDirectory() as folder:
            args = self.fixture(Path(folder))
            args.deadline = "2000-01-01T00:00:00+00:00"
            report = module.run(args)
            self.assertEqual(report["status"], "partial")
            self.assertEqual(report["models"], [])
            self.assertEqual((args.out / "predictions.jsonl").read_text(), "")

    def test_missing_candidate_is_rejected_before_any_output(self):
        with tempfile.TemporaryDirectory() as folder:
            args = self.fixture(Path(folder))
            args.candidate = [f"missing={Path(folder) / 'missing.onnx'}"]
            with self.assertRaises(FileNotFoundError):
                module.run(args)
            self.assertFalse(args.out.exists())


if __name__ == "__main__":
    unittest.main()
