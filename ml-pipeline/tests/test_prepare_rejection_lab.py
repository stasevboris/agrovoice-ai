"""A private verifier bundle must stay attached to its evaluated detector and policy."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'android-app/prepare_rejection_lab.py'
SPEC = importlib.util.spec_from_file_location('prepare_rejection_lab', SCRIPT)
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class RejectionLabBundleTest(unittest.TestCase):
    def test_bundle_keeps_fixed_policy_and_refuses_different_detector(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run, study, data = [root / name for name in ['run', 'study', 'data']]
            for path in [run / 'weights', study, data]:
                path.mkdir(parents=True)
            detector = run / 'weights/best.onnx'
            detector.write_bytes(b'detector')
            verifier = study / 'dino_verifier.onnx'
            verifier.write_bytes(b'verifier')
            def write(path, value):
                path.write_text(json.dumps(value), encoding='utf-8')
            write(run / 'android_manifest_entry.json', dict(id='old', name='Old', sha256=builder.sha256(detector),
                family='yolo', side=416, crops=['apple'], classes=['apple__healthy'], labels_ru=['Healthy']))
            write(data / 'report.json', dict(detector_sha256=builder.sha256(detector), classes=['apple', '__reject__']))
            write(study / 'export_report.json', dict(sha256=builder.sha256(verifier)))
            write(study / 'frozen_selection.json', dict(selected_name='dino_norm_mlp_crop_vs_reject',
                selection_partition='cal', audit_read_for_selection=False, policy=dict(threshold=0.9958845687447002)))
            entry = builder.prepare(run, study, data, root / 'bundle')
            self.assertEqual(entry['verifier']['threshold'], 0.9958845687447002)
            self.assertEqual(entry['verifier']['detector_sha256'], builder.sha256(detector))
            self.assertEqual((root / 'bundle/models/experimental_dino.onnx').read_bytes(), b'verifier')
            detector.write_bytes(b'different')
            with self.assertRaisesRegex(ValueError, 'detector'):
                builder.prepare(run, study, data, root / 'bad_bundle')
