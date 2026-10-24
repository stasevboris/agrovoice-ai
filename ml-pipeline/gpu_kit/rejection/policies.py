"""Rejection scores and calibration-only selection; no prediction relabeling."""
import numpy as np


def logit_scores(logits, detector_classes, mode):
    logits = np.asarray(logits, dtype=np.float64)
    detector_classes = np.asarray(detector_classes, dtype=int)
    if logits.ndim != 2 or len(logits) != len(detector_classes) or not np.isfinite(logits).all():
        raise ValueError('Expected finite logits for every detector proposal')
    if (detector_classes < 0).any() or (detector_classes >= logits.shape[1] - 1).any():
        raise ValueError('Detector crop outside supported classes')
    shifted = logits - logits.max(1, keepdims=True)
    probabilities = np.exp(shifted)
    probabilities /= probabilities.sum(1, keepdims=True)
    if mode == 'knownness':
        return 1 - probabilities[:, -1]
    if mode == 'crop_vs_reject':
        difference = logits[:, -1] - logits[np.arange(len(logits)), detector_classes]
        return np.exp(-np.logaddexp(0, difference))
    raise ValueError('Unknown score mode')


def thresholds(values):
    values = np.asarray(values, dtype=float)
    if not values.size or not np.isfinite(values).all():
        raise ValueError('Nonempty finite calibration scores required')
    return sorted(set(np.quantile(values, np.linspace(0, 1, 51)).tolist()
                      + [float(np.nextafter(values.min(), -np.inf)),
                         float(np.nextafter(values.max(), np.inf))]))


def unknown_macro(metrics):
    rates = [value['accepted'] / value['images'] for value in metrics['acceptance'].values() if value['images']]
    if not rates:
        raise ValueError('Unknown calibration frames required')
    return float(np.mean(rates))


def select_point(points, baseline, min_retention=.95):
    if baseline['field']['tp'] <= 0 or not 0 < min_retention <= 1:
        raise ValueError('Known validation and valid retention required')
    candidates = []
    before = unknown_macro(baseline)
    for point in points:
        measured = point['metrics']
        if measured['field']['tp'] < min_retention * baseline['field']['tp']:
            continue
        if measured['field']['fp'] > baseline['field']['fp']:
            continue
        if set(measured['acceptance']) != set(baseline['acceptance']):
            raise ValueError('Unknown sources differ from baseline')
        if any(measured['acceptance'][source]['images'] != value['images']
               or measured['acceptance'][source]['accepted'] > value['accepted']
               for source, value in baseline['acceptance'].items()):
            continue
        if unknown_macro(measured) >= before:
            continue
        candidates.append(point)
    if not candidates:
        return None
    return min(candidates, key=lambda point: (unknown_macro(point['metrics']),
                                             point['metrics']['field']['fp'],
                                             -point['metrics']['field']['tp'], point['threshold']))


def neighbor_scores(query, known, outlier=None, k=10, device='cpu', check_stop=lambda: None):
    import torch
    if k < 1 or not len(known) or (outlier is not None and not len(outlier)):
        raise ValueError('Neighbor banks must be nonempty')
    known = torch.nn.functional.normalize(known.float(), dim=1).to(device)
    if outlier is not None:
        outlier = torch.nn.functional.normalize(outlier.float(), dim=1).to(device)
    scores = []
    with torch.inference_mode():
        for offset in range(0, len(query), 128):
            check_stop()
            batch = torch.nn.functional.normalize(query[offset:offset + 128].float(), dim=1).to(device)
            similarity = (batch @ known.T).topk(min(k, len(known)), dim=1).values[:, -1]
            if outlier is not None:
                similarity -= (batch @ outlier.T).topk(min(k, len(outlier)), dim=1).values[:, -1]
            scores.append(similarity.cpu().numpy())
    result = np.concatenate(scores)
    if not np.isfinite(result).all():
        raise RuntimeError('Non-finite neighbor score')
    return result


def calibrate(frames, scores, crops):
    from evaluate import score
    ids = {prediction['sample_id'] for frame in frames if frame['partition'] == 'cal'
           for prediction in frame['predictions']}
    grid = thresholds(np.array([scores[identity] for identity in sorted(ids)]))
    baseline = score(frames, {}, None, 'cal', crops)
    points = [dict(threshold=threshold, metrics=score(frames, scores, threshold, 'cal', crops))
              for threshold in grid]
    return dict(policy=select_point(points, baseline), baseline=baseline, points=points)


def frozen_evaluation(frames, scores, crops, threshold):
    from evaluate import score
    if not np.isfinite(threshold):
        raise ValueError('Frozen finite threshold required')
    return dict(threshold=threshold, baseline=score(frames, {}, None, 'audit', crops),
                gated=score(frames, scores, threshold, 'audit', crops), threshold_tuned=False)
