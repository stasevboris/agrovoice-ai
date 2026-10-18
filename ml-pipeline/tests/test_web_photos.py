import importlib.util
from pathlib import Path
import sys
import unittest

EXTERNAL = Path(__file__).resolve().parents[1] / "external"
sys.path.insert(0, str(EXTERNAL))
spec = importlib.util.spec_from_file_location("prepare_web_photos", EXTERNAL / "prepare_web_photos.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WebPhotoReviewTest(unittest.TestCase):
    def setUp(self):
        self.row = {"width": 500, "height": 375, "license_code": "cc-by", "disease_label": None}
        self.decision = {"accept": True, "reason": "Visible leaf", "box_xyxy": [10, 20, 300, 350]}

    def test_valid_partial_box(self):
        module.validate_review(self.row, self.decision)

    def test_disease_inference_rejected(self):
        with self.assertRaises(ValueError):
            module.validate_review(dict(self.row, disease_label="healthy"), self.decision)

    def test_out_of_image_box_rejected(self):
        with self.assertRaises(ValueError):
            module.validate_review(self.row, dict(self.decision, box_xyxy=[10, 20, 501, 350]))

    def test_unknown_license_rejected(self):
        with self.assertRaises(ValueError):
            module.validate_review(dict(self.row, license_code="cc-by-nc"), self.decision)


if __name__ == "__main__":
    unittest.main()
