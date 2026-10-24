"""Flavor packaging must keep real inference and omit laboratory weights from field."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[2] / "android-app/fetch_assets.py"
SPEC = importlib.util.spec_from_file_location("flavor_assets", SCRIPT)
fetch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fetch)


class FlavorAssetsTest(unittest.TestCase):
    def test_field_keeps_baseline_and_original_class_order(self):
        manifest = json.loads(fetch.MODEL_MANIFEST.read_text(encoding="utf-8"))
        selected = fetch.flavor_manifest(manifest, "field")
        self.assertEqual([model["id"] for model in selected["models"]], ["yolo26s"])
        self.assertEqual(selected["classes"], manifest["classes"])
        self.assertEqual(selected["labels_ru"], manifest["labels_ru"])
        self.assertEqual(len(fetch.model_files(selected)), 1)
        self.assertEqual(fetch.flavor_manifest(manifest, "lab"), manifest)

    def test_missing_baseline_is_an_error_not_an_empty_manifest(self):
        with self.assertRaisesRegex(ValueError, "yolo26s"):
            fetch.flavor_manifest({"models": []}, "field")

    def test_offline_import_uses_verified_original_tree(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source/app/src/main/assets/models/a.onnx"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"real-local-graph")
            target = root / "target/models/a.onnx"
            fetch.import_local_file(root / "source", "models/a.onnx", target, fetch.sha256(source))
            self.assertEqual(target.read_bytes(), source.read_bytes())
            source.write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                fetch.import_local_file(root / "source", "models/a.onnx", target, "0" * 64)
            self.assertEqual(target.read_bytes(), b"real-local-graph")

    def test_runtime_only_does_not_fetch_models_or_voice(self):
        with patch.object(fetch, "fetch_runtime", return_value=[]) as runtime:
            with patch.object(fetch, "fetch_models") as models, patch.object(fetch, "fetch_voice") as voice:
                self.assertEqual(fetch.main(["--runtime-only", "--check"]), 0)
        runtime.assert_called_once()
        models.assert_not_called()
        voice.assert_not_called()


class StagedAssetsTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("prepare_flavor_assets", SCRIPT.with_name("prepare_flavor_assets.py"))
        self.prepare = importlib.util.module_from_spec(spec)
        with patch.dict("sys.modules", {"fetch_assets": fetch}):
            spec.loader.exec_module(self.prepare)
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.source = self.root / "assets"
        self.output = self.root / "generated"
        for directory in ("models", "voice", "tts"):
            (self.source / directory).mkdir(parents=True)
        self.manifest = {"classes": ["source-class"], "labels_ru": ["source-label"], "models": [
            {"id": "yolo26s", "asset": "models/s.onnx", "sha256": "a" * 64},
            {"id": "experimental", "asset": "models/lab.onnx", "sha256": "b" * 64}]}
        (self.source / "models/manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        (self.source / "voice/manifest.json").write_text('{"files": []}', encoding="utf-8")
        (self.source / "tts/manifest.json").write_text('{"model": "tts", "files": []}', encoding="utf-8")
        self.patch = patch.multiple(fetch, ASSETS=self.source,
                                    MODEL_MANIFEST=self.source / "models/manifest.json",
                                    VOICE_MANIFEST=self.source / "voice/manifest.json",
                                    TTS_MANIFEST=self.source / "tts/manifest.json")
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.runtime = patch.object(fetch, "fetch_runtime", return_value=[])
        self.runtime.start()
        self.addCleanup(self.runtime.stop)

    def test_compile_staging_allows_missing_but_packaging_rejects_it(self):
        self.assertEqual(self.prepare.prepare("field", self.output), [])
        self.assertEqual(self.prepare.prepare("field", self.output, check=True), ["models/s.onnx"])

    def test_field_excludes_unlisted_weights_and_rejects_corruption(self):
        (self.source / "models/s.onnx").write_bytes(b"bad")
        (self.source / "models/lab.onnx").write_bytes(b"experimental")
        self.prepare.prepare("field", self.output)
        self.assertFalse((self.output / "models/lab.onnx").exists())
        self.assertIn("models/s.onnx", self.prepare.prepare("field", self.output, check=True))

    def test_packaging_rejects_manifest_drift(self):
        self.prepare.prepare("field", self.output)
        (self.output / "models/manifest.json").write_text('{"models": []}', encoding="utf-8")
        self.assertIn("models/manifest.json", self.prepare.prepare("field", self.output, check=True))

    def test_canonical_taxonomy_is_available_in_both_flavors_without_reordering(self):
        expected = json.loads((SCRIPT.parent.parent / "ml-pipeline/allcrops/taxonomy_v1.json").read_text(encoding="utf-8"))
        for flavor in ("field", "lab"):
            self.prepare.prepare(flavor, self.output)
            copied = json.loads((self.output / "taxonomies/taxonomy_v1.json").read_text(encoding="utf-8"))
            self.assertEqual(expected, copied)
            self.assertEqual((SCRIPT.parent.parent / "ml-pipeline/allcrops/taxonomy_v1.json").read_bytes(),
                             (self.output / "taxonomies/taxonomy_v1.json").read_bytes())

    def test_private_verifier_bundle_is_explicit_lab_only_and_verified(self):
        bundle = self.root / "experiment"
        (bundle / "models").mkdir(parents=True)
        for name in ["detector", "verifier"]:
            (bundle / "models" / (name + ".onnx")).write_bytes(name.encode())
        entry = {"id": "private", "family": "yolo", "asset": "models/detector.onnx",
                 "sha256": fetch.sha256(bundle / "models/detector.onnx"),
                 "verifier": {"asset": "models/verifier.onnx", "sha256": fetch.sha256(bundle / "models/verifier.onnx")}}
        (bundle / "entry.json").write_text(json.dumps(entry), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "lab"):
            self.prepare.prepare("field", self.output, experiment=bundle)
        self.prepare.prepare("lab", self.output, experiment=bundle)
        self.assertEqual((self.output / "models/verifier.onnx").read_bytes(), b"verifier")
        staged = json.loads((self.output / "models/manifest.json").read_text())
        self.assertEqual(staged["models"][-1], entry)
        (bundle / "models/verifier.onnx").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "SHA"):
            self.prepare.prepare("lab", self.output, experiment=bundle)


if __name__ == "__main__":
    unittest.main()
