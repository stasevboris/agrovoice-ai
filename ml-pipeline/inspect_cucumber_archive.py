"""Inventory nested cucumber archives without extracting or changing labels."""
import collections
import io
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
target = root/'docs/data/cucumber_zenodo_archive_audit.json'
report = json.loads(target.read_text(encoding='utf-8'))
details = []
with zipfile.ZipFile(root/'ml-pipeline/source_candidates/cucumber_zenodo_16816441_tags.zip') as outer:
    for member in outer.namelist():
        if not member.endswith('.zip'):
            continue
        with zipfile.ZipFile(io.BytesIO(outer.read(member))) as archive:
            names = [n for n in archive.namelist() if not n.endswith('/')]
            annotations = [n for n in names if Path(n).suffix.lower() in ('.json', '.xml', '.txt', '.csv')]
            row = dict(archive=member, files=len(names),
                       extensions=dict(collections.Counter(Path(n).suffix.lower() for n in names)),
                       annotation_files=annotations[:6])
            label_names = [n for n in names if n.startswith('labels/') and n.endswith('.txt')]
            images = [n for n in names if Path(n).suffix.lower() in ('.jpg', '.jpeg', '.png')]
            row['image_count'] = len(images)
            row['label_count'] = len(label_names)
            row['missing_label_stems'] = sorted({Path(n).stem for n in images} - {Path(n).stem for n in label_names})
            lengths = collections.Counter()
            identifiers = collections.Counter()
            invalid = 0
            total_boxes = 0
            for label in label_names:
                for line in archive.read(label).decode('utf-8-sig').splitlines():
                    if not line.strip():
                        continue
                    values = line.split()
                    lengths[len(values)] += 1
                    total_boxes += 1
                    if len(values) != 5:
                        invalid += 1
                        continue
                    cls, x, y, w, h = map(float, values)
                    identifiers[int(cls)] += 1
                    if cls != int(cls) or not (0 <= x <= 1 and 0 <= y <= 1 and 0 < w <= 1 and 0 < h <= 1):
                        invalid += 1
            row.update(boxes=total_boxes, row_lengths=dict(lengths), local_class_ids=dict(identifiers),
                       invalid_rows=invalid, label_preview=archive.read(label_names[0]).decode('utf-8')[:200] if label_names else None)
            if annotations:
                sample = archive.read(annotations[0])
                if Path(annotations[0]).suffix.lower() == '.json':
                    value = json.loads(sample)
                    row['json_container'] = type(value).__name__
                    tasks = value if isinstance(value, list) else [value]
                    types = collections.Counter()
                    for task in tasks:
                        if not isinstance(task, dict):
                            continue
                        for annotation in task.get('annotations', []):
                            for result in annotation.get('result', []):
                                types[result.get('type', 'unknown')] += 1
                    row['label_studio_result_types'] = dict(types)
                    row['task_count'] = len(tasks)
                    row['sample_keys'] = list(tasks[0]) if tasks and isinstance(tasks[0], dict) else []
                else:
                    row['annotation_preview'] = sample[:400].decode('utf-8', errors='replace')
            details.append(row)
            print(json.dumps(row, ensure_ascii=False))
report['nested_archives'] = details
target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
