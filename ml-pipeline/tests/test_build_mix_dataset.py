"""Тесты смеси allcrops_v1 и веб-фото: веб-val не участвует в ранней остановке, имена не конфликтуют."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ml-pipeline"))

import build_mix_dataset as mix  # noqa: E402

LISTS = ("val_select.txt", "val_field.txt", "test_field.txt", "val_lab.txt", "test_lab.txt")
YAMLS = ("data.yaml", "data_field.yaml", "data_lab.yaml")


def make_file(path: Path, text="x\n"):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def make_base(root: Path):
    for split, name in (("train", "base_train"), ("val", "base_val"), ("test", "base_test")):
        make_file(root / "images" / split / f"{name}.jpg")
        make_file(root / "labels" / split / f"{name}.txt", "0 0.5 0.5 0.2 0.2\n")
    for name in LISTS:
        make_file(root / name, "./images/val/base_val.jpg\n")
    for name in YAMLS:
        make_file(root / name, "train: images/train\nval: val_select.txt\n")
    make_file(root / "taxonomy_v1.json", "{}\n")
    make_file(root / "classes.json", "[]\n")
    make_file(root / "manifest.jsonl", "")
    report = dict(fingerprint="base-fingerprint", splits=dict(train=1, val=1, test=1), per_class=[
        dict(id=0, code="apple__healthy", ru="Яблоня, здорова", train_images=1, val_images=1, test_images=1),
        dict(id=1, code="apple__scab", ru="Яблоня, парша", train_images=0, val_images=0, test_images=0),
    ])
    make_file(root / "report.json", json.dumps(report))


def make_web(root: Path):
    for split, sha in (("train", "aaa"), ("train", "bbb"), ("val", "ccc")):
        make_file(root / "images" / split / f"{sha}.jpg")
        make_file(root / "labels" / split / f"{sha}.txt", "1 0.5 0.5 0.3 0.3\n")
    manifest = [{"sha256": "aaa", "class": "apple__scab"}, {"sha256": "bbb", "class": "apple__healthy"},
                {"sha256": "ccc", "class": "apple__scab"}]
    make_file(root / "manifest.jsonl", "".join(json.dumps(row) + "\n" for row in manifest))
    make_file(root / "report.json", json.dumps(dict(fingerprint="web-fingerprint", geometry={"detected": 3})))


class MixDatasetTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.base, self.web, self.out = self.root / "base", self.root / "web", self.root / "out"
        make_base(self.base)
        make_web(self.web)

    def tearDown(self):
        self.temporary.cleanup()

    def build(self, *extra):
        mix.main(["--base", str(self.base), "--web", str(self.web), "--out", str(self.out), *extra])
        return json.loads((self.out / "report.json").read_text(encoding="utf-8"))

    def test_web_train_is_merged_with_prefixed_names(self):
        self.build()
        names = sorted(path.name for path in (self.out / "images" / "train").iterdir())
        self.assertEqual(names, ["base_train.jpg", "web_aaa.jpg", "web_bbb.jpg"])
        self.assertTrue((self.out / "labels" / "train" / "web_aaa.txt").exists())
        self.assertTrue((self.out / "images" / "val" / "web_ccc.jpg").exists())

    def test_web_val_is_listed_but_not_the_early_stopping_set(self):
        self.build()
        self.assertEqual((self.out / "val_web.txt").read_text(encoding="utf-8").split(),
                         ["./images/val/web_ccc.jpg"])
        self.assertEqual((self.out / "val_select.txt").read_text(encoding="utf-8").split(),
                         ["./images/val/base_val.jpg"])
        self.assertNotIn("val_web", (self.out / "data.yaml").read_text(encoding="utf-8"))

    def test_report_counts_web_per_class(self):
        report = self.build()
        self.assertEqual(report["splits"], dict(train=1, val=1, test=1, web_train=2, web_val=1))
        counts = {row["code"]: row["web_train_images"] for row in report["per_class"]}
        self.assertEqual(counts, {"apple__healthy": 1, "apple__scab": 1})
        self.assertEqual(report["validation"].count("web val"), 1)

    def test_refuses_to_overwrite_and_fingerprint_is_stable(self):
        first = self.build()["fingerprint"]
        with self.assertRaises(SystemExit):
            mix.main(["--base", str(self.base), "--web", str(self.web), "--out", str(self.out)])
        self.assertEqual(self.build("--overwrite")["fingerprint"], first)


if __name__ == "__main__":
    unittest.main()
