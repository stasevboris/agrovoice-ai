"""Tests for prepare_model_release.py with a synthetic run directory."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_model_release as release  # noqa: E402


def make_run(folder: Path, name="run_s", family="yolo", status="done", corrupt=False) -> Path:
    run = folder/name
    (run/"weights").mkdir(parents=True)
    (run/"eval").mkdir()
    graph = b"onnx-bytes"
    (run/"weights/best.onnx").write_bytes(graph)
    entry = dict(id=name, name="YOLO26S allcrops v1", family=family, dataset="allcrops_v1", side=416, seed=1,
                 sha256=hashlib.sha256(b"other" if corrupt else graph).hexdigest(),
                 classes=["potato__healthy", "grape__healthy"], labels_ru=["Картофель: здоровый", "Виноград: здоровый"],
                 crops=["grape", "potato"], crops_ru={"grape": "Виноград", "potato": "Картофель"})
    (run/"android_manifest_entry.json").write_text(json.dumps(entry, ensure_ascii=False), encoding="utf-8")
    (run/"run_summary.json").write_text(json.dumps(dict(status=status, gpu="RTX 5090", train_minutes=50)), encoding="utf-8")
    metrics = dict(map50=.5, map50_95=.3, per_class_map50_95={"potato__healthy": .25})
    (run/"eval/evaluation.json").write_text(json.dumps(dict(field_test=metrics, lab_test=metrics)), encoding="utf-8")
    return run


class PrepareReleaseTest(unittest.TestCase):
    def test_release_files_and_card_numbers_come_from_evaluation(self):
        with tempfile.TemporaryDirectory() as folder:
            run = make_run(Path(folder))
            out = Path(folder)/"out"
            release.main([str(run), "--tag", "t", "--out", str(out)])
            names = sorted(p.name for p in out.iterdir())
            self.assertEqual(names, ["ATTRIBUTION.md", "LICENSE", "MODEL_CARD.md", "RELEASE_NOTES.md", "SHA256SUMS.txt",
                                     "run_s.manifest_entry.json", "run_s.onnx"])
            card = (out/"MODEL_CARD.md").read_text(encoding="utf-8")
            self.assertIn("0.500 / 0.300", card)
            self.assertIn("| Картофель: здоровый | 0.25 | 0.25 |", card)
            self.assertNotIn("Виноград: здоровый |", card)  # not a priority crop

    def test_rejects_non_yolo_unfinished_and_mismatched_runs(self):
        with tempfile.TemporaryDirectory() as folder:
            for kwargs in (dict(name="deim", family="detr"), dict(name="stopped", status="stopped"), dict(name="bad", corrupt=True)):
                run = make_run(Path(folder), **kwargs)
                with self.assertRaises(SystemExit):
                    release.load_run(run)


if __name__ == "__main__":
    unittest.main()
