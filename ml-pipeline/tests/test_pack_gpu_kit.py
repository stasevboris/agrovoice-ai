"""The GPU kit must hold its datasets exactly where queue.json, train_queue.py and check_env.py look for them."""
import json
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pack_gpu_kit as pack  # noqa: E402

QUEUE = json.loads((pack.ROOT/"ml-pipeline/gpu_kit/queue.json").read_text(encoding="utf-8"))
SPEC = importlib.util.spec_from_file_location("package_results", pack.ROOT/"ml-pipeline/gpu_kit/package_results.py")
results_pack = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(results_pack)


def make_dataset(folder: Path):
    (folder/"labels/train").mkdir(parents=True)
    (folder/"labels/train/a.txt").write_text("0 0.5 0.5 0.2 0.2\n")
    report = {"dataset": folder.name, "fingerprint": folder.name, "splits": {"train": {"images": 1}, "web_train": 0}}
    (folder/"report.json").write_text(json.dumps(report))


class PackGpuKitTest(unittest.TestCase):
    def test_results_archive_keeps_resumable_checkpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            kit = Path(tmp)
            weights = kit/"runs/interrupted/weights"
            weights.mkdir(parents=True)
            (weights/"last_resumable.pt").write_bytes(b"optimizer and epoch fixture")
            (weights/"epoch5.pt").write_bytes(b"redundant snapshot")
            with mock.patch.object(results_pack, "KIT", kit):
                results_pack.main()
            with zipfile.ZipFile(next(kit.glob("results_*.zip"))) as archive:
                self.assertEqual(b"optimizer and epoch fixture",
                                 archive.read("runs/interrupted/weights/last_resumable.pt"))
                self.assertNotIn("runs/interrupted/weights/epoch5.pt", archive.namelist())

    def test_datasets_land_on_queue_paths_and_archive_is_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            dataset = Path(tmp)/"dataset_allcrops_web_v1"
            smoke = Path(tmp)/"dataset_allcrops_v1_smoke"
            make_dataset(dataset)
            make_dataset(smoke)
            out = Path(tmp)/"out"
            argv = ["pack_gpu_kit.py", "--dataset", str(dataset), "--smoke-dataset", str(smoke), "--out", str(out)]
            with mock.patch.object(sys, "argv", argv):
                pack.main()
            archive = next(out.glob("*.zip"))
            kit = archive.stem
            with zipfile.ZipFile(archive) as opened:
                self.assertIsNone(opened.testzip())
                names = set(opened.namelist())
            self.assertIn(f"{kit}/{QUEUE['dataset']}/report.json", names)
            self.assertIn(f"{kit}/{QUEUE['smoke_dataset']}/report.json", names)
            self.assertIn(f"{kit}/kit_manifest.json", names)
            self.assertTrue((out/f"{kit}.manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
