"""Select a gate on calibration frames; expose audit only after the policy is frozen."""
from collections import Counter, defaultdict
from pathlib import Path
import sys
import numpy as np
from core import acceptance_scores, choose_policy

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'alternatives'))
from compare_mobile_detectors import COUNTS, score_frame


def score(frames, scores, threshold, part, crops):
    field = Counter(dict.fromkeys(COUNTS, 0))
    acceptance = defaultdict(lambda: dict(images=0, accepted=0))
    for frame in frames:
        if frame['partition'] != part:
            continue
        predictions = [prediction for prediction in frame['predictions']
                       if threshold is None or scores[prediction['sample_id']] >= threshold]
        if frame['source'] == 'field':
            measured = score_frame(frame['ground_truth'], predictions, set(crops[:-1]))
            field.update({key: measured[key] for key in COUNTS})
        else:
            acceptance[frame['source']]['images'] += 1
            acceptance[frame['source']]['accepted'] += bool(predictions)
    result = dict(field)
    answered = field['tp'] + field['fp']
    result.update(precision=field['tp'] / answered if answered else None,
                  recall=field['tp'] / field['gt'] if field['gt'] else None,
                  f1=2 * field['tp'] / max(1, 2 * field['tp'] + field['fp'] + field['fn']))
    return dict(field=result, acceptance=dict(acceptance))


def evaluate(frames, rows, probabilities, crops, min_retention=.95):
    if len(rows) != len(probabilities):
        raise ValueError('Every prepared sample must have one verifier probability row')
    lookup = {row['id']: index for index, row in enumerate(rows)}
    scores = {}
    for frame in frames:
        for prediction in frame['predictions']:
            index = lookup[prediction['sample_id']]
            scores[prediction['sample_id']] = float(acceptance_scores(
                probabilities[index:index + 1], [crops.index(prediction['crop'])])[0])
    baseline = score(frames, scores, None, 'cal', crops)
    baseline_tp = baseline['field']['tp']
    points = []
    for threshold in [float(value) for value in np.linspace(0, 1, 21)] + [.99]:
        measured = score(frames, scores, threshold, 'cal', crops)
        rates = [row['accepted'] / row['images'] for row in measured['acceptance'].values() if row['images']]
        if not rates:
            raise ValueError('Unknown calibration frames required')
        points.append(dict(threshold=threshold, retained_tp=measured['field']['tp'],
                           unknown_acceptance=float(np.mean(rates)), metrics=measured))
    policy = choose_policy(points, baseline_tp, min_retention)
    # No audit results are consulted during threshold selection.
    audit_baseline = score(frames, scores, None, 'audit', crops)
    audit = score(frames, scores, policy['threshold'], 'audit', crops) if policy is not None else None
    status = 'no_feasible_policy'
    if policy is not None:
        before = audit_baseline['acceptance']
        after = audit['acceptance']
        enough_data = audit_baseline['field']['tp'] > 0 and bool(before)
        retained = audit['field']['tp'] >= min_retention * audit_baseline['field']['tp']
        improved = enough_data and np.mean([after[key]['accepted'] / after[key]['images'] for key in before]) < np.mean(
            [before[key]['accepted'] / before[key]['images'] for key in before])
        status = 'candidate' if enough_data and retained and improved else 'audit_not_improved'
    return dict(policy_status=status,
                policy=policy, proposed_min_known_tp_retention=min_retention,
                calibration_baseline=baseline, calibration_sweep=points,
                audit_baseline=audit_baseline, audit_gated=audit, test_evaluated=False,
                limitations=['Fixed detector proposals, verifier crop inference; not a phone benchmark.',
                             'Known TP retention is relative to detector baseline, not all GT.',
                             'An audit degradation can invalidate a policy selected on calibration.',
                             'Source-level unknown proxies and historical detector-selection bias remain.'])
