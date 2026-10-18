import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "gpu_kit/alternatives/configure_yolo_pilot.py"
spec = importlib.util.spec_from_file_location("configure_yolo_pilot", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PilotConfigTest(unittest.TestCase):
    def prepare(self, root, overlap=False):
        data = root / "data"
        (data / "annotations").mkdir(parents=True)
        (data / "taxonomy_v1.json").write_text(json.dumps({"classes": [{"id": 0, "code": "crop__condition"}]}))
        for name in ("train", "negative", "val"):
            (data / f"{name}.jpg").write_bytes(b"fixture")
        for name, images in {"train": ["train"], "train_with_negatives": ["train", "negative"],
                             "val_field": ["train" if overlap else "val"]}.items():
            (data / "annotations" / f"{name}.json").write_text(json.dumps({"images": [{"file_name": f"{image}.jpg"} for image in images]}))
        return data

    def test_same_validation_without_test(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            report = module.configure(self.prepare(root), root / "config")
            baseline = (root / "config/train.yaml").read_text()
            negatives = (root / "config/train_with_negatives.yaml").read_text()
            self.assertEqual(baseline.splitlines()[1], negatives.splitlines()[1])
            self.assertNotIn("test:", baseline)
            self.assertEqual(report["counts"]["train_with_negatives"], 2)

    def test_overlap_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                module.configure(self.prepare(root, overlap=True), root / "config")
