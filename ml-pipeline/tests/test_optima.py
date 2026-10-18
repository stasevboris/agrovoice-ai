import importlib.util
from pathlib import Path
import sys
import unittest

EXTERNAL = Path(__file__).resolve().parents[1] / "external"
sys.path.insert(0, str(EXTERNAL))
spec = importlib.util.spec_from_file_location("prepare_optima", EXTERNAL / "prepare_optima.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OptimaGeometryTest(unittest.TestCase):
    def test_valid_native_box(self):
        self.assertEqual(module.parse_boxes("0 0.5 0.5 0.2 0.4\n"), [[0.5, 0.5, 0.2, 0.4]])

    def test_unknown_native_class(self):
        with self.assertRaises(ValueError):
            module.parse_boxes("1 0.5 0.5 0.2 0.4")

    def test_outside_image(self):
        with self.assertRaises(ValueError):
            module.parse_boxes("0 0.9 0.5 0.5 0.4")

    def test_nonfinite_box(self):
        with self.assertRaises(ValueError):
            module.parse_boxes("0 nan 0.5 0.2 0.4")
