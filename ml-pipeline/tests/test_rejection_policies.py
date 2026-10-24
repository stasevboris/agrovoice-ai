import importlib.util
from pathlib import Path
import sys
import unittest

import numpy as np

SOURCE = Path(__file__).resolve().parents[1] / 'gpu_kit/rejection'
sys.path.insert(0, str(SOURCE))


def load():
    spec = importlib.util.spec_from_file_location('rejection_policies', SOURCE / 'policies.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PolicyTest(unittest.TestCase):
    def setUp(self):
        self.module = load()

    def test_knownness_does_not_force_agreement_with_another_known_class(self):
        logits = np.log(np.array([[.2, .7, .1], [.05, .05, .9]]))
        scores = self.module.logit_scores(logits, [0, 0], 'knownness')
        np.testing.assert_allclose(scores, [.9, .1])

    def test_pairwise_odds_only_compare_detector_crop_and_reject(self):
        logits = np.log(np.array([[.2, .7, .1]]))
        np.testing.assert_allclose(self.module.logit_scores(logits, [0], 'crop_vs_reject'), [2 / 3])

    def test_large_logits_do_not_overflow(self):
        scores = self.module.logit_scores(np.array([[10000., 10001., 9999.]]), [0], 'knownness')
        self.assertTrue(np.isfinite(scores).all())

    def test_missing_or_nonfinite_score_is_rejected(self):
        with self.assertRaises(ValueError):
            self.module.logit_scores(np.array([[np.nan, 0]]), [0], 'knownness')

    def test_select_does_not_trade_known_coverage_for_lower_unknown(self):
        baseline = dict(field=dict(tp=100, fp=40), acceptance={'unknown':dict(images=10, accepted=8)})
        points = [dict(threshold=.1, metrics=dict(field=dict(tp=96, fp=30), acceptance={'unknown':dict(images=10, accepted=4)})),
                  dict(threshold=.9, metrics=dict(field=dict(tp=60, fp=10), acceptance={'unknown':dict(images=10, accepted=0)}))]
        result = self.module.select_point(points, baseline)
        self.assertEqual(.1, result['threshold'])

    def test_no_improvement_and_one_worse_unknown_source_are_not_candidates(self):
        baseline = dict(field=dict(tp=100, fp=40), acceptance={'a':dict(images=10, accepted=8), 'b':dict(images=5, accepted=0)})
        point = dict(threshold=.1, metrics=dict(field=dict(tp=98, fp=30), acceptance={'a':dict(images=10, accepted=1), 'b':dict(images=5, accepted=1)}))
        self.assertIsNone(self.module.select_point([point], baseline))
        self.assertIsNone(self.module.select_point([dict(threshold=0,metrics=baseline)], baseline))

    def test_empty_or_nan_calibration_cannot_form_threshold_grid(self):
        for scores in [[], [np.nan], [np.inf]]:
            with self.assertRaises(ValueError):
                self.module.thresholds(np.asarray(scores))

    def test_threshold_grid_keeps_accept_all_boundary(self):
        values = np.array([.2, .4, .8])
        grid = self.module.thresholds(values)
        self.assertLessEqual(min(grid), min(values))
        self.assertGreater(max(grid), max(values))

    def test_nearest_neighbor_uses_unit_features_and_separates_known_from_outlier(self):
        import torch
        query = torch.tensor([[4., 0], [0., 2]])
        known = torch.tensor([[1., 0], [.9, .1]])
        outlier = torch.tensor([[0., 1], [.1, .9]])
        scores = self.module.neighbor_scores(query, known, outlier, k=1, device='cpu')
        self.assertGreater(scores[0], scores[1])
        scaled = self.module.neighbor_scores(query * 3, known, outlier, k=1, device='cpu')
        np.testing.assert_allclose(scores, scaled, atol=1e-6)

    def test_audit_scores_cannot_change_calibration_thresholds(self):
        frames = []
        for name, part, source in [('a', 'cal', 'field'), ('b', 'cal', 'unknown'), ('c', 'audit', 'field')]:
            frames.append(dict(partition=part, source=source,
                               ground_truth=[dict(crop='apple',box=[0,0,10,10])] if source=='field' else [],
                               predictions=[dict(sample_id=name,crop='apple',box=[0,0,10,10],score=.9)]))
        first = self.module.calibrate(frames,dict(a=.9,b=.1,c=.8),['apple','__reject__'])
        second = self.module.calibrate(frames,dict(a=.9,b=.1,c=-999),['apple','__reject__'])
        self.assertEqual(first, second)
        self.assertEqual(first['policy']['metrics']['field']['tp'], 1)

    def test_fixed_threshold_evaluation_does_not_search_test_scores(self):
        row = dict(partition='audit',source='field',ground_truth=[dict(crop='apple',box=[0,0,10,10])],
                   predictions=[dict(sample_id='a',crop='apple',box=[0,0,10,10],score=.9)])
        from evaluate import score
        measured = self.module.frozen_evaluation([row],dict(a=.2),['apple','__reject__'],.8)
        self.assertEqual(measured['threshold'],.8)
        self.assertEqual(measured['gated']['field']['tp'],0)
        self.assertEqual(measured['baseline']['field']['tp'],1)


if __name__ == '__main__':
    unittest.main()
