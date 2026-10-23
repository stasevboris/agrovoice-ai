"""Preserve source attribution and previews of excluded, unlabelled proposals."""
import hashlib
import json
import math
import shutil
from PIL import Image, ImageOps


def add_provenance(root, data):
    destination = data / 'provenance'
    destination.mkdir(exist_ok=True)
    for folder in ['dataset_nonplant_openimages_v2', 'dataset_unknown_beans_screened_v1', 'dataset_unknown_deepweeds_v1']:
        source = root / 'ml-pipeline' / folder
        paths = [source / 'manifest.jsonl'] if 'nonplant' in folder else [source / 'train.jsonl', source / 'val.jsonl']
        rows = []
        for path in paths:
            for line in path.read_text(encoding='utf-8').splitlines():
                row = json.loads(line)
                if row['split'] in ['train', 'val']:
                    rows.append(row)
        target = destination / f'{folder}_manifest.jsonl'
        target.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8', newline='\n')
        shutil.copy2(source / 'report.json', destination / f'{folder}_report.json')
    shutil.copy2(root / 'ml-pipeline/dataset_vision_training_v1/report.json', destination / 'known_geometry_report.json')
    review = [json.loads(line) for line in (data / 'review_queue.jsonl').read_text(encoding='utf-8').splitlines()]
    (data / 'review').mkdir(exist_ok=True)
    for row in review:
        identity = hashlib.sha256(json.dumps(row, sort_keys=True).encode()).hexdigest()[:24]
        with Image.open(root / 'ml-pipeline/dataset_vision_training_v1' / row['image']) as source:
            left, top, right, bottom = row['prediction']['box']
            crop = source.convert('RGB').crop((max(0, math.floor(left)), max(0, math.floor(top)),
                                              min(source.width, math.ceil(right)), min(source.height, math.ceil(bottom))))
            image = ImageOps.pad(crop, (224, 224), method=Image.Resampling.BILINEAR, color=(114, 114, 114))
            image.save(data / 'review' / f'{identity}.jpg', quality=95)
        row.update(preview=f'review/{identity}.jpg', included_in_training=False)
    (data / 'review_queue.jsonl').write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in review),
                                            encoding='utf-8', newline='\n')
    report = json.loads((data / 'report.json').read_text(encoding='utf-8'))
    report['test_metadata_policy'] = 'Source manifests can list test records; only train/val records processed; test images not loaded.'
    report['review_previews'] = len(review)
    (data / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8', newline='\n')
    return report
