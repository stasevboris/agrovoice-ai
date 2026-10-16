"""Tests for android-app/fetch_assets.py in a temporary assets folder (no network)."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[2]/"android-app/fetch_assets.py"
spec = importlib.util.spec_from_file_location("fetch_assets", SCRIPT)
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class FetchAssetsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        root = Path(self.folder.name)
        self.assets = root/"assets"
        (self.assets/"models").mkdir(parents=True)
        (self.assets/"voice").mkdir()
        self.local = root/"local"
        self.local.mkdir()
        self.good, self.other = b"graph-a", b"graph-b"
        manifest = {"models": [
            {"id": "a", "asset": "models/a.onnx", "sha256": digest(self.good)},
            {"id": "b", "asset": "models/b.onnx", "sha256": digest(self.other),
             "classifier": {"asset": "models/c.onnx", "sha256": digest(b"clf")}}]}
        (self.assets/"models/manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (root/"release.json").write_text(json.dumps({"repository": "u/r", "tag": "t", "files": ["a.onnx"]}), encoding="utf-8")
        self.saved = {name: getattr(fetch, name) for name in ("ASSETS", "MODEL_MANIFEST", "VOICE_MANIFEST", "RELEASE_CONFIG", "CACHE")}
        fetch.ASSETS = self.assets
        fetch.MODEL_MANIFEST = self.assets/"models/manifest.json"
        fetch.VOICE_MANIFEST = self.assets/"voice/manifest.json"
        fetch.RELEASE_CONFIG = root/"release.json"
        fetch.CACHE = root/"cache"

    def tearDown(self):
        for name, value in self.saved.items():
            setattr(fetch, name, value)
        self.folder.cleanup()

    def test_release_url_only_for_published_files(self):
        config = {"repository": "u/r", "tag": "t", "files": ["a.onnx"]}
        self.assertEqual(fetch.release_url(config, "models/a.onnx"), "https://github.com/u/r/releases/download/t/a.onnx")
        self.assertIsNone(fetch.release_url(config, "models/b.onnx"))

    def test_local_copy_verified_and_unpublished_reported(self):
        (self.local/"a.onnx").write_bytes(self.good)
        (self.local/"b.onnx").write_bytes(b"corrupted")
        problems = fetch.fetch_models(check_only=False, local_dir=self.local)
        self.assertEqual((self.assets/"models/a.onnx").read_bytes(), self.good)
        self.assertFalse((self.assets/"models/b.onnx").exists(), "corrupted copy must be removed")
        self.assertEqual(len(problems), 2)  # b: wrong SHA, c: neither published nor local

    def test_check_only_does_not_write(self):
        problems = fetch.fetch_models(check_only=True, local_dir=self.local)
        self.assertEqual(len(problems), 3)
        self.assertEqual(list((self.assets/"models").glob("*.onnx")), [])

    def test_voice_extracted_from_cached_archive_and_verified(self):
        files = {"am/final.mdl": b"acoustic", "README": b"readme"}
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as bundle:
            for path, data in files.items():
                bundle.writestr(f"vosk-test/{path}", data)
        archive = buffer.getvalue()
        manifest = {"model": "vosk-test", "source": "https://invalid.example/never-downloaded.zip",
                    "archive_sha256": digest(archive),
                    "files": [{"path": p, "sha256": digest(d)} for p, d in files.items()]}
        fetch.VOICE_MANIFEST.write_text(json.dumps(manifest), encoding="utf-8")
        fetch.CACHE.mkdir()
        (fetch.CACHE/"vosk-test.zip").write_bytes(archive)
        self.assertEqual(fetch.fetch_voice(check_only=False), [])
        self.assertEqual((self.assets/"voice/ru/am/final.mdl").read_bytes(), b"acoustic")
        self.assertEqual(fetch.fetch_voice(check_only=True), [])


if __name__ == "__main__":
    unittest.main()
