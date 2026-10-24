import importlib.util
import sys
from pathlib import Path
import unittest
import tempfile
import subprocess
from datetime import datetime, timezone, timedelta

import numpy as np

SOURCE = Path(__file__).resolve().parents[1] / 'gpu_kit/rejection'
sys.path.insert(0, str(SOURCE))


def load_core():
    spec = importlib.util.spec_from_file_location('rejection_core', SOURCE / 'core.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RejectionPolicyTest(unittest.TestCase):
    def setUp(self):
        self.core = load_core()

    def test_one_observation_never_crosses_partitions(self):
        rows = [dict(group='plant-a', partition='train', image_sha256='a'),
                dict(group='plant-a', partition='cal', image_sha256='b')]
        with self.assertRaisesRegex(ValueError, 'group'):
            self.core.validate_partitions(rows)

    def test_duplicate_pixels_cannot_cross_partitions(self):
        rows = [dict(group='a', partition='train', image_sha256='same'),
                dict(group='b', partition='audit', image_sha256='same')]
        with self.assertRaisesRegex(ValueError, 'image'):
            self.core.validate_partitions(rows)

    def test_test_rows_cannot_enter_prepared_training_package(self):
        with self.assertRaisesRegex(ValueError, 'partition'):
            self.core.validate_partitions([dict(group='a', partition='test', image_sha256='a')])

    def test_partition_is_stable_for_entire_capture_group(self):
        self.assertEqual(self.core.partition('camera-day'), self.core.partition('camera-day'))
        self.assertEqual({'cal', 'audit'}, {self.core.partition(str(i)) for i in range(50)})

    def test_unknown_plant_is_rejection_exposure_not_a_nonleaf_label(self):
        self.assertEqual('unsupported_scene_weak', self.core.reject_reason('DeepWeeds'))
        self.assertEqual('unsupported_scene_weak', self.core.reject_reason('Makerere_ibean'))
        self.assertEqual('reviewed_nonplant', self.core.reject_reason('OpenImages'))

    def test_verifier_may_reject_but_never_replace_detector_crop(self):
        probabilities = np.array([[.9, .05, .05], [.05, .9, .05], [.1, .1, .8]])
        result = self.core.acceptance_scores(probabilities, [0, 0, 1])
        np.testing.assert_allclose(result, [.9, -1, -1])

    def test_unmatched_leaf_is_not_invented_as_negative(self):
        prediction = dict(box=[0, 0, 10, 10], crop='tomato')
        self.assertIsNone(self.core.matched_crop(prediction, []))
        self.assertEqual('apple', self.core.matched_crop(prediction, [dict(box=[0, 0, 10, 10], crop='apple')]))

    def test_no_feasible_retention_does_not_claim_calibrated_success(self):
        points = [dict(threshold=.1, retained_tp=90, unknown_acceptance=.1)]
        self.assertIsNone(self.core.choose_policy(points, baseline_tp=100, min_retention=.95))

    def test_threshold_selection_preserves_known_leaves_before_rejecting_unknown(self):
        points = [dict(threshold=.1, retained_tp=100, unknown_acceptance=.8),
                  dict(threshold=.6, retained_tp=96, unknown_acceptance=.3),
                  dict(threshold=.9, retained_tp=60, unknown_acceptance=.01)]
        self.assertEqual(.6, self.core.choose_policy(points, 100, .95)['threshold'])

    def test_empty_calibration_cannot_choose_threshold(self):
        with self.assertRaises(ValueError):
            self.core.choose_policy([], 0, .95)

    def test_head_training_and_selection_exclude_audit_and_unlabelled_proposals(self):
        rows = [dict(partition='train', label=0), dict(partition='cal', label=1),
                dict(partition='audit', label=0), dict(partition='train', label=None)]
        self.assertEqual([0], self.core.labelled_indices(rows, 'train'))
        self.assertEqual([1], self.core.labelled_indices(rows, 'cal'))
        with self.assertRaises(ValueError):
            self.core.labelled_indices(rows, 'audit')

    def test_home_deadline_cannot_roll_over_to_tomorrow(self):
        now = datetime(2026, 10, 23, 12, tzinfo=timezone(timedelta(hours=3)))
        soft, hard = self.core.execution_window('2026-10-23T23:00:00+03:00', now, home=True)
        self.assertLess(soft, hard)
        self.assertLess(hard, datetime(2026, 10, 23, 23, tzinfo=now.tzinfo).timestamp())
        for deadline in ['2026-10-24T23:00:00+03:00', '2026-10-23T23:30:00+03:00', '2026-10-23T23:00:00']:
            with self.assertRaises(ValueError):
                self.core.execution_window(deadline, now, home=True)

    def test_home_accepts_earlier_same_day_deadline(self):
        now = datetime(2026, 10, 24, 9, tzinfo=timezone(timedelta(hours=3)))
        soft, hard = self.core.execution_window('2026-10-24T21:00:00+03:00', now, home=True)
        deadline = datetime(2026, 10, 24, 21, tzinfo=now.tzinfo).timestamp()
        self.assertEqual(deadline - 20 * 60, soft)
        self.assertEqual(deadline - 5 * 60, hard)

    def test_audit_cannot_change_selected_threshold(self):
        spec = importlib.util.spec_from_file_location('rejection_evaluate', SOURCE / 'evaluate.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        frames = []
        for identity, part, source in [('a', 'cal', 'field'), ('b', 'cal', 'beans'), ('c', 'audit', 'field')]:
            frames.append(dict(partition=part, source=source,
                               ground_truth=[dict(crop='apple', box=[0, 0, 10, 10])] if source == 'field' else [],
                               predictions=[dict(crop='apple', score=.9, box=[0, 0, 10, 10], sample_id=identity)]))
        rows = [dict(id=name) for name in ['a', 'b', 'c']]
        probabilities = np.array([[.9, .1], [.1, .9], [.9, .1]])
        first = module.evaluate(frames, rows, probabilities, ['apple', '__reject__'])
        probabilities[2] = [.01, .99]
        second = module.evaluate(frames, rows, probabilities, ['apple', '__reject__'])
        self.assertEqual(first['policy'], second['policy'])
        self.assertEqual(first['audit_gated']['field']['tp'], 1)
        self.assertEqual(second['audit_gated']['field']['tp'], 0)
        self.assertNotEqual('candidate', second['policy_status'])

    def test_full_mobilenet_export_schema_without_optimizer(self):
        import torch
        from torchvision import models
        spec = importlib.util.spec_from_file_location('rejection_export', SOURCE / 'export_model.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        torch.set_num_threads(2)
        base = models.mobilenet_v3_small(weights=None).eval()
        encoder = torch.nn.Sequential(base.features, base.avgpool, torch.nn.Flatten())
        head = torch.nn.Linear(576, 16).eval()
        with tempfile.TemporaryDirectory() as temporary:
            result = module.export_mobile(encoder, head, Path(temporary) / 'untrained_schema.onnx', 2)
            self.assertEqual([1, 16], result['shape'])
            self.assertLess(result['parity_max_abs_diff'], 1e-4)

    def test_expired_cli_cannot_start_or_create_training_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'run'
            result = subprocess.run([sys.executable, '-X', 'utf8', str(SOURCE / 'train.py'),
                                     '--device', 'cpu', '--out', str(output),
                                     '--deadline', '2000-01-01T23:00:00+03:00'],
                                    capture_output=True, text=True, encoding='utf-8')
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(output.exists())

    def test_collect_preserves_resume_state_and_refuses_active_lock(self):
        spec = importlib.util.spec_from_file_location('rejection_collect', SOURCE / 'collect.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'data').mkdir()
            (root / 'data/report.json').write_text('{}')
            (root / 'runs/model').mkdir(parents=True)
            (root / 'runs/model/last.pt').write_bytes(b'head and optimizer fixture')
            lock = root / 'runs/model/run.lock'
            lock.write_text('running fixture')
            with self.assertRaises(RuntimeError):
                module.collect(root, root)
            lock.rename(lock.with_name('run.lock.saved'))
            report = module.collect(root, root)
            import zipfile
            with zipfile.ZipFile(report['archive']) as archive:
                self.assertEqual(b'head and optimizer fixture', archive.read('runs/model/last.pt'))


if __name__ == '__main__':
    unittest.main()
