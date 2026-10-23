"""Policies shared by preparation, training and evaluation of the rejection pilot."""
import hashlib
from datetime import datetime, timedelta, timezone
import numpy as np

PARTITIONS = {'train', 'cal', 'audit'}


def partition(group):
    return 'cal' if int(hashlib.sha256(group.encode()).hexdigest()[:8], 16) % 2 == 0 else 'audit'


def validate_partitions(rows):
    groups = {}
    images = {}
    for row in rows:
        part = row['partition']
        if part not in PARTITIONS:
            raise ValueError('Invalid partition; test is not a training input')
        for field, registry in [('group', groups), ('image_sha256', images)]:
            key = row[field]
            if key in registry and registry[key] != part:
                raise ValueError(f'{field} crosses partitions')
            registry[key] = part


def reject_reason(source):
    return 'reviewed_nonplant' if source == 'OpenImages' else 'unsupported_scene_weak'


def acceptance_scores(probabilities, detector_classes):
    probabilities = np.asarray(probabilities)
    classes = np.asarray(detector_classes, dtype=int)
    if probabilities.ndim != 2 or len(probabilities) != len(classes):
        raise ValueError('Probability/label shape mismatch')
    if not np.isfinite(probabilities).all() or np.any(classes < 0) or np.any(classes >= probabilities.shape[1] - 1):
        raise ValueError('Invalid probabilities or detector crop')
    selected = probabilities[np.arange(len(classes)), classes]
    agrees = probabilities.argmax(1) == classes
    return np.where(agrees, selected, -1.0)


def matched_crop(prediction, annotations):
    box = np.asarray(prediction['box'], dtype=float)
    best = None
    best_iou = .5
    for annotation in annotations:
        other = np.asarray(annotation['box'], dtype=float)
        intersection = np.maximum(0, np.minimum(box[2:], other[2:]) - np.maximum(box[:2], other[:2])).prod()
        union = np.maximum(0, box[2:] - box[:2]).prod() + np.maximum(0, other[2:] - other[:2]).prod() - intersection
        iou = intersection / max(union, 1e-9)
        if iou >= best_iou:
            best_iou = iou
            best = annotation['crop']
    return best


def choose_policy(points, baseline_tp, min_retention):
    if baseline_tp <= 0 or not 0 < min_retention <= 1:
        raise ValueError('Nonempty known calibration and valid retention are required')
    feasible = [point for point in points if point['retained_tp'] >= baseline_tp * min_retention]
    if not feasible:
        return None
    return min(feasible, key=lambda point: (point['unknown_acceptance'], -point['retained_tp'], point['threshold']))


def labelled_indices(rows, part):
    if part not in {'train', 'cal'}:
        raise ValueError('Only train/cal are allowed for head fitting and checkpoint selection')
    return [index for index, row in enumerate(rows) if row['partition'] == part and row['label'] is not None]


def execution_window(value, now=None, home=False):
    msk = timezone(timedelta(hours=3))
    now = now or datetime.now(msk)
    end = datetime.fromisoformat(value)
    if end.tzinfo is None:
        raise ValueError('Deadline must contain timezone')
    if home and (end.astimezone(msk).date() != now.astimezone(msk).date()
                 or end.astimezone(msk).time().isoformat() != '23:00:00'):
        raise ValueError('Home training must end at 23:00 MSK today; no overnight restart')
    soft = end.timestamp() - 20 * 60
    hard = end.timestamp() - 5 * 60
    if soft <= now.timestamp():
        raise ValueError('Not enough time before checkpoint reserve')
    return soft, hard
