"""Pinned TTS archives: local reuse, hash rejection and safe extraction, without network."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "android-app/fetch_assets.py"
SPEC = importlib.util.spec_from_file_location("fetch_tts_assets", SCRIPT)
fetch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fetch)


class FetchTtsAssetsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.local = self.root / "local"
        self.local.mkdir()
        self.payload = b"voice graph"
        self.make_archive("denis/model.onnx")

    def make_archive(self, member):
        archive = self.local / "voice.tar.bz2"
        with tarfile.open(archive, "w:bz2") as bundle:
            item = tarfile.TarInfo(member)
            item.size = len(self.payload)
            bundle.addfile(item, io.BytesIO(self.payload))
        (self.local / "runtime.aar").write_bytes(b"runtime")
        manifest = {
            "model": "denis",
            "runtime": {"file": "runtime.aar", "source": "https://invalid/runtime",
                        "sha256": hashlib.sha256(b"runtime").hexdigest()},
            "archive": {"file": "voice.tar.bz2", "source": "https://invalid/voice",
                        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()},
            "files": [{"path": "model.onnx", "sha256": hashlib.sha256(self.payload).hexdigest()}],
        }
        self.manifest = self.root / "manifest.json"
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")

    def run_fetch(self, check=False):
        with patch.multiple(fetch, TTS_MANIFEST=self.manifest, ASSETS=self.root / "assets",
                            APP=self.root, CACHE=self.root / "cache", create=True), \
                patch.object(fetch, "download", side_effect=AssertionError("unexpected network")):
            return fetch.fetch_tts(check, self.local)

    def test_local_sources_are_verified_and_check_is_read_only(self):
        self.assertTrue(self.run_fetch(check=True))
        self.assertFalse((self.root / "assets").exists())
        self.assertEqual([], self.run_fetch())
        self.assertEqual(self.payload, (self.root / "assets/tts/denis/model.onnx").read_bytes())
        self.assertEqual(b"runtime", (self.root / "app/libs/runtime.aar").read_bytes())
        self.assertEqual([], self.run_fetch(check=True))

    def test_corrupted_archive_never_installs_model(self):
        (self.local / "voice.tar.bz2").write_bytes(b"wrong")
        self.assertTrue(self.run_fetch())
        self.assertFalse((self.root / "assets/tts/denis/model.onnx").exists())

    def test_archive_traversal_never_writes_outside_model(self):
        self.make_archive("denis/../../escaped")
        self.assertTrue(self.run_fetch())
        self.assertFalse((self.root / "escaped").exists())


if __name__ == "__main__":
    unittest.main()
