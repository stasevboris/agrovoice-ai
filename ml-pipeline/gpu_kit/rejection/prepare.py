"""Prepare frozen-detector proposals and provenance. No model training."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import hashlib
import io
import json
import math
from pathlib import Path
import random
import sys
import time

import numpy as np
from PIL import Image, ImageOps

from core import matched_crop, partition, reject_reason, validate_partitions

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'alternatives'))
from compare_mobile_detectors import decode, preprocess, sha256


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def read_lines(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8', newline='\n')


def annotations(document, classes):
    result = defaultdict(list)
    for row in document['annotations']:
        x, y, width, height = row['bbox']
        result[row['image_id']].append(dict(box=[x, y, x + width, y + height], crop=classes[row['category_id']]['crop']))
    return result


class Builder:
    def __init__(self, out, crops, deadline):
        self.out = out
        self.crops = crops
        self.deadline = deadline
        self.rows = []
        self.frames = []
        self.review = []
        self.owners = {}
        out.mkdir(parents=True, exist_ok=False)
        (out / 'images').mkdir()

    def check_time(self):
        if time.time() >= self.deadline:
            raise TimeoutError('Preparation deadline reached; partial data is not training-ready')

    def add(self, path, box, group, part, target, reason, domain, origin):
        self.check_time()
        with Image.open(path) as source:
            source = source.convert('RGB')
            if box is not None:
                left, top, right, bottom = box
                bounds = (max(0, math.floor(left)), max(0, math.floor(top)),
                          min(source.width, math.ceil(right)), min(source.height, math.ceil(bottom)))
                if bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
                    raise ValueError('Invalid crop')
                source = source.crop(bounds)
            image = ImageOps.pad(source, (224, 224), method=Image.Resampling.BILINEAR, color=(114, 114, 114))
            buffer = io.BytesIO()
            image.save(buffer, format='JPEG', quality=95)
        content = buffer.getvalue()
        digest = hashlib.sha256(content).hexdigest()
        if digest in self.owners and self.owners[digest] != part:
            raise ValueError(f'Exact crop crosses partitions: {origin}')
        self.owners[digest] = part
        relative = f'images/{digest}.jpg'
        if not (self.out / relative).exists():
            (self.out / relative).write_bytes(content)
        identity = hashlib.sha256(json.dumps([origin, box, group, part, target]).encode()).hexdigest()[:24]
        row = dict(id=identity, image=relative, image_sha256=digest, group=group, partition=part,
                   label=self.crops.index(target) if target is not None else None,
                   reason=reason, domain=domain, origin=origin, box=box)
        self.rows.append(row)
        return identity


def prepare(args):
    import onnxruntime as ort
    root = args.root.resolve()
    known = root / 'ml-pipeline/dataset_vision_training_v1'
    taxonomy = read_json(known / 'taxonomy_v1.json')
    crops = sorted({row['crop'] for row in taxonomy['classes']}) + ['__reject__']
    records = read_lines(known / 'crops.jsonl')
    source_groups = {row['source_image']: 'known:' + row['group'] for row in records}
    builder = Builder(args.out, crops, datetime.fromisoformat(args.deadline).timestamp())
    field = read_json(known / 'annotations/val_field.json')
    field_images = {str(row['id']): row for row in field['images']}
    field_gt = annotations(field, taxonomy['classes'])
    source_rows = {}
    for folder, source in [('dataset_nonplant_openimages_v2', 'OpenImages'),
                           ('dataset_unknown_beans_screened_v1', 'Makerere_ibean'),
                           ('dataset_unknown_deepweeds_v1', 'DeepWeeds')]:
        directory = root / 'ml-pipeline' / folder
        rows = read_lines(directory / 'manifest.jsonl') if source == 'OpenImages' else read_lines(directory / 'val.jsonl')
        for row in rows:
            if row['split'] != 'val':
                continue
            group = source + ':' + str(row.get('capture_group', row.get('duplicate_group', row.get('author_url', row['image']))))
            source_rows[str((directory / row['image']).resolve()).lower()] = (group, source)
    comparison = read_json(args.predictions.parent / 'report.json')
    detector_meta = next(row for row in comparison['models'] if row['name'] == 'web_s')
    if comparison['status'] != 'complete' or detector_meta['sha256'] != sha256(args.detector):
        raise ValueError('Frozen S or predictions do not match completed comparison')

    # Calibration and audit first; test images are never processed or packaged.
    for frame in read_lines(args.predictions):
        if frame['model'] != 'web_s':
            continue
        path = Path(frame['image'])
        if frame['source'] == 'field':
            source_image = field_images[frame['id']]['file_name']
            group = source_groups.get(source_image, 'known:' + source_image)
            source = 'field'
            gt = field_gt[int(frame['id'])]
        else:
            group, source = source_rows[str(path.resolve()).lower()]
            gt = []
        part = partition(group)
        predictions = []
        for prediction in frame['predictions']:
            target = matched_crop(prediction, gt) if source == 'field' else '__reject__'
            if source == 'field':
                reason = 'source_gt_matched_proposal' if target is not None else 'unmatched_field_proposal_unlabelled'
            else:
                reason = reject_reason(source)
            identity = builder.add(path, prediction['box'], group, part, target, reason,
                                   'field', str(path.relative_to(root)))
            predictions.append(dict(prediction, sample_id=identity))
        builder.frames.append(dict(id=frame['id'], source=frame['source'], partition=part,
                                   group=group, ground_truth=gt, predictions=predictions))

    # GT crops cover supported cultures absent from field (not proof of field quality).
    random.Random(args.seed).shuffle(records)
    selected = Counter()
    for row in records:
        if row['split'] not in ['train', 'val']:
            continue
        group = 'known:' + row['group']
        part = 'train' if row['split'] == 'train' else partition(group)
        crop = taxonomy['classes'][row['class_id']]['crop']
        key = (part, crop, row['domain'])
        limit = args.known_per_crop if part == 'train' else 25
        if selected[key] >= limit:
            continue
        builder.add(known / row['image'], None, group, part, crop, 'source_gt_crop',
                    row['domain'], row['image'])
        selected[key] += 1

    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(args.detector), sess_options=options, providers=['CPUExecutionProvider'])
    crop_by_class = [row['crop'] for row in taxonomy['classes']]

    def proposals(path):
        builder.check_time()
        batch, geometry = preprocess(path)
        raw = session.run(None, {session.get_inputs()[0].name: batch})[0]
        return [dict(box=row[:4].tolist(), score=float(row[4]), crop=crop_by_class[int(row[5])])
                for row in decode(raw, geometry)][:args.max_proposals]

    train_field = read_json(known / 'annotations/train_field.json')
    train_gt = annotations(train_field, taxonomy['classes'])
    images = train_field['images'][:]
    random.Random(args.seed).shuffle(images)
    for index, row in enumerate(images[:args.field_train_images]):
        path = known / row['file_name']
        group = source_groups.get(row['file_name'], 'known:' + row['file_name'])
        for prediction in proposals(path):
            target = matched_crop(prediction, train_gt[row['id']])
            if target is None:
                builder.review.append(dict(image=row['file_name'], prediction=prediction,
                                           reason='Unmatched box can be unannotated foliage; no automatic negative label'))
                continue
            builder.add(path, prediction['box'], group, 'train', target, 'source_gt_matched_proposal',
                        'field', row['file_name'])
        if index % 100 == 0:
            print('field train proposals', index + 1, flush=True)

    for folder, source in [('dataset_nonplant_openimages_v2', 'OpenImages'),
                           ('dataset_unknown_beans_screened_v1', 'Makerere_ibean'),
                           ('dataset_unknown_deepweeds_v1', 'DeepWeeds')]:
        directory = root / 'ml-pipeline' / folder
        rows = read_lines(directory / 'manifest.jsonl') if source == 'OpenImages' else read_lines(directory / 'train.jsonl')
        rows = [row for row in rows if row['split'] == 'train']
        random.Random(args.seed).shuffle(rows)
        for index, row in enumerate(rows[:args.outlier_train_images]):
            if source == 'OpenImages' and row.get('review', {}).get('decision') != 'accept':
                raise ValueError('Nonplant example lacks review')
            path = directory / row['image']
            group = source + ':' + str(row.get('capture_group', row.get('duplicate_group', row.get('author_url', row['image']))))
            candidates = proposals(path)
            for prediction in candidates or [dict(box=None)]:
                builder.add(path, prediction['box'], group, 'train', '__reject__', reject_reason(source),
                            'field', str(path.relative_to(root)))
            if index % 100 == 0:
                print(source, index + 1, flush=True)

    validate_partitions(builder.rows)
    train_classes = {row['label'] for row in builder.rows if row['partition'] == 'train'}
    if train_classes != set(range(len(crops))):
        raise ValueError(f'Missing train verifier labels: {set(range(len(crops))) - train_classes}')
    for name, rows in [('samples.jsonl', builder.rows), ('frames.jsonl', builder.frames), ('review_queue.jsonl', builder.review)]:
        (args.out / name).write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows),
                                    encoding='utf-8', newline='\n')
    report = dict(status='ready', training_performed=False, test_images_read=False, seed=args.seed, classes=crops,
                  detector_sha256=sha256(args.detector), predictions_sha256=sha256(args.predictions),
                  partitions=dict(Counter(row['partition'] for row in builder.rows)),
                  label_counts={part: dict(Counter(crops[row['label']] for row in builder.rows
                                                  if row['partition'] == part and row['label'] is not None))
                                for part in ['train', 'cal', 'audit']},
                  reasons=dict(Counter(row['reason'] for row in builder.rows)), review_required=len(builder.review),
                  preprocessing='RGB crop floor/ceil, pad to 224x224 gray114 bilinear, JPEG95; ImageNet mean/std at encoder',
                  limitations=['Unknown proposal labels inherit weak image-level source species, not expert box labels.',
                               'Outlier combines unknown plants and nonplant for rejection; it is not a no-leaf class.',
                               'GT crop accuracy is not complete-chain accuracy; evaluation uses frozen real S proposals.',
                               'Calibration/audit split original validation by source observation/capture group.',
                               'Historical S selection and dataset overlap limitations remain; no new independent test.',
                               'Nonplant data is a small reviewed pilot; no claimed general rejection reliability.'])
    write_json(args.out / 'report.json', report)
    from provenance import add_provenance
    return add_provenance(root, args.out)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[3]
    parser.add_argument('--root', type=Path, default=root)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--detector', type=Path, required=True)
    parser.add_argument('--predictions', type=Path, default=root / 'runs/mobile_web_comparison_20261023/predictions.jsonl')
    parser.add_argument('--deadline', required=True)
    parser.add_argument('--known-per-crop', type=int, default=120)
    parser.add_argument('--field-train-images', type=int, default=600)
    parser.add_argument('--outlier-train-images', type=int, default=500)
    parser.add_argument('--max-proposals', type=int, default=3)
    parser.add_argument('--threads', type=int, default=2)
    parser.add_argument('--seed', type=int, default=20261023)
    args = parser.parse_args()
    print(json.dumps(prepare(args), ensure_ascii=False, indent=2))
