import csv
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_plantdoc_clean import build_dataset, convert_boxes, orient_box


def image_bytes(color):
    buffer = io.BytesIO()
    Image.new("RGB", (20, 10), color).save(buffer, "PNG")
    return buffer.getvalue()


def csv_bytes(rows):
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=["filename", "width", "height", "class", "xmin", "ymin", "xmax", "ymax"])
    writer.writeheader()
    writer.writerows(rows)
    return buffer.getvalue().encode()


class DatasetRegressionTests(unittest.TestCase):
    def make_archive(self, path):
        train = [
            ("A.jpg", "healthy", (10, 20, 30)),
            ("a.jpg", "healthy", (30, 20, 10)),
            ("d1.jpg", "disease", (90, 20, 30)),
            ("d2.jpg", "disease", (90, 20, 50)),
            ("url.jpg?w=20&h=10", "healthy", (50, 60, 70)),
            ("leak.jpg", "disease", (1, 2, 3)),
        ]
        test = [("test.jpg", "disease", (1, 2, 3))]
        with zipfile.ZipFile(path, "w") as z:
            for split, items in (("TRAIN", train), ("TEST", test)):
                rows = []
                for filename, cls, color in items:
                    z.writestr(f"source/{split}/{filename}", image_bytes(color))
                    rows.append(dict(filename=filename.split("?")[0], width=20, height=10,
                                     **{"class": cls}, xmin=1, ymin=1, xmax=18, ymax=8))
                z.writestr("source/"+("train_labels.csv" if split=="TRAIN" else "test_labels.csv"), csv_bytes(rows))

    def test_case_names_queries_and_held_out_deduplication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_archive(root/"data.zip")
            report = build_dataset(root/"data.zip", root/"clean")
            manifest = json.loads((root/"clean/manifest.json").read_text())
            self.assertEqual(sum(r["images"] for r in report["splits"].values()), 6)
            self.assertEqual(len(report["case_collision_groups"]), 1)
            self.assertEqual(len(report["resolved_filenames"]), 1)
            self.assertEqual(len(report["duplicate_groups"]), 1)
            self.assertEqual(report["duplicate_groups"][0]["kept_partition"], "test")
            members = {r["member"] for r in manifest}
            self.assertIn("source/TRAIN/A.jpg", members)
            self.assertIn("source/TRAIN/a.jpg", members)
            self.assertNotIn("source/TRAIN/leak.jpg", members)
            hashes = {s: {r["pixel_hash"] for r in manifest if r["split"]==s} for s in ("train","val","test")}
            self.assertFalse(hashes["train"] & hashes["val"])
            self.assertFalse((hashes["train"] | hashes["val"]) & hashes["test"])
            self.assertFalse(report["splits"]["train"]["absent_classes"])
            self.assertFalse(report["splits"]["val"]["absent_classes"])
            filenames = [r["filename"].casefold() for r in manifest]
            self.assertEqual(len(filenames), len(set(filenames)))
            second = build_dataset(root/"data.zip", root/"second")
            self.assertEqual(report["fingerprint"], second["fingerprint"])
            with self.assertRaises(FileExistsError):
                build_dataset(root/"data.zip", root/"clean")

    def test_exif_rotation_transforms_all_four_corners(self):
        self.assertEqual(orient_box((1,2,4,6), 10,20,6), (14,1,18,4))
        self.assertEqual(orient_box((1,2,4,6), 10,20,8), (2,6,6,9))
        self.assertEqual(orient_box((1,2,4,6), 10,20,2), (6,2,9,6))

    def test_resized_annotation_is_scaled(self):
        row = dict(width=200, height=100, xmin=20, ymin=10, xmax=180, ymax=90, **{"class":"leaf"})
        issues = []
        boxes = convert_boxes([row], (20,10), (20,10), 1, {"leaf":0}, issues, "sample")
        self.assertEqual(boxes, [(0,0.5,0.5,0.8,0.8)])
        self.assertEqual(issues[0]["kind"], "resized_source")

    def test_unrelated_dimensions_are_excluded(self):
        row = dict(width=10, height=100, xmin=1, ymin=1, xmax=9, ymax=90, **{"class":"leaf"})
        issues = []
        self.assertEqual(convert_boxes([row], (20,10), (20,10), 1, {"leaf":0}, issues, "sample"), [])
        self.assertEqual(issues[0]["kind"], "ambiguous_dimensions")


if __name__ == "__main__":
    unittest.main()
