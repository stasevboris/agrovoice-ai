"""Read-only CPU diagnosis of the user's 5 October cucumber example; no training."""
from __future__ import annotations
import json
from pathlib import Path
import numpy as np
from PIL import Image
from evaluate_accuracy_pipeline import read, sha, session, letterbox, decode
from prepare_supplemented import signature, near

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/diagnostics/cucumber_misclassification_2026-10-05'
PHOTO = OUT / 'import_9b048b01-39c1-40a8-a9ba-ab6190cd2b55.jpg'


def main():
    image = Image.open(PHOTO).convert('RGB')
    photo_sha = sha(PHOTO)
    manifest = read(ROOT / 'android-app/app/src/main/assets/models/manifest.json')
    classes = manifest['classes']
    actual = []
    for path in sorted((OUT / 'phone_results').glob('*.json')):
        value = read(path)
        if value['photo_sha256'] != photo_sha:
            continue
        model = next(m for m in manifest['models'] if m['id'] == value['model'])
        assert value['model_sha256'] == model['sha256']
        actual.append({
            'file': str(path.relative_to(ROOT)), 'model': value['model'],
            'threshold': value['confidence_threshold'], 'crop': value['crop_filter'],
            'detections': value['detections'], 'graph_sha256': model['sha256'],
        })
    cpu = []
    for model in manifest['models']:
        if model['id'] not in ('yolo11n', 'yolo26n', 'yolo26s', 'yolo26m', 'deimv2_n'):
            continue
        runtime = session(model['asset'], model['sha256'])
        assert runtime.get_providers() == ['CPUExecutionProvider']
        tensor, geometry = letterbox(image, model.get('side', 416))
        raw = runtime.run(None, {runtime.get_inputs()[0].name: tensor})[0]
        detr = model.get('family') == 'detr'
        boxes = decode(raw, geometry, .001, detr=detr)
        top = [
            {'class_id': int(b[5]), 'label': classes[int(b[5])],
             'score': float(b[4]), 'xyxy': b[:4].tolist()}
            for b in boxes[:8]
        ]
        if detr:
            cucumber_rows = raw[0][raw[0, :, 5] == 29]
            maximum_cucumber = float(cucumber_rows[:, 4].max()) if len(cucumber_rows) else None
        else:
            maximum_cucumber = float(raw[0, 4 + 29].max())
        cpu.append({'model': model['id'], 'graph_sha256': model['sha256'],
                    'top_postprocessed': top,
                    'maximum_raw_cucumber_score': maximum_cucumber,
                    'cucumber_score_scope': 'Across all proposals; not a calibrated crop probability',
                    'all_at_0_4': [x for x in top if x['score'] >= .4]})
        print(json.dumps({'model': model['id'], 'top': top[:2],
                          'max_cucumber': maximum_cucumber}, ensure_ascii=False), flush=True)
        del runtime
    data = read(ROOT / 'ml-pipeline/dataset_clean_v2/manifest.json')
    sig = signature(image)
    matches = []
    nearest = []
    for row in data:
        other = row.get('source_signature')
        if not other:
            continue
        exact_bytes = photo_sha in (row.get('prepared_sha256'), row.get('raw_hash'))
        exact_pixels = sig['decoded_hash'] == other['decoded_hash']
        distance_p = (sig['phash'] ^ other['phash']).bit_count()
        distance_d = (sig['dhash'] ^ other['dhash']).bit_count()
        evidence = {k: row.get(k) for k in ('filename', 'split', 'source_dataset', 'sources', 'boxes')}
        evidence.update(phash_distance=distance_p, dhash_distance=distance_d,
                        exact_bytes=exact_bytes, exact_pixels=exact_pixels)
        if exact_bytes or exact_pixels or near(sig, other):
            matches.append(evidence)
        nearest.append(evidence)
    nearest.sort(key=lambda x: x['phash_distance'] + x['dhash_distance'])
    dataset = read(ROOT / 'ml-pipeline/dataset_clean_v2/report.json')
    report = {
        'case': 'User-supplied comparison of cucumber crop recognition, 2026-10-05',
        'photo': str(PHOTO.relative_to(ROOT)), 'photo_sha256': photo_sha,
        'photo_size': list(image.size), 'crop_ground_truth': 'Cucumber, supplied by user; not expert disease ground truth',
        'original_web_source': 'Not identified; preserve as a private diagnostic example, not an openly licensed training source',
        'phone_reports': actual, 'desktop_cpu': cpu,
        'desktop_scope': 'CV2/PIL letterbox can differ from Android bitmap interpolation; phone reports are authoritative for screenshots. No claim of new pixel-exact Android/Desktop parity.',
        'data_cucumber_class': next(c for c in dataset['classes'] if c['id'] == 29),
        'cucumber_source': next(s for s in dataset['sources'] if s['name'] == 'Cucumber'),
        'cucumber_training_source_names': sorted({source for row in data if row.get('source_dataset') == 'Cucumber' and row['split'] == 'train' for source in row['sources'] if source.startswith('images/')}),
        'duplicate_screen': {'matches_under_existing_gate': matches, 'nearest_candidates': nearest[:5],
                             'rule': 'Exact file/decoded signature, or aspect ratio within3% and pHash+dHash distances each<=4. Screening is not proof of independence from all pretrained data.'},
        'constraints': 'No training, GPU inference, frozen data/recipe/selection, APK, phone settings or historical evidence changed.',
        'interpretation': 'Crop recognition case, not diagnosis accuracy or evidence that one architecture is globally best. Lower thresholds expose additional DEIM false classes; higher confidence does not guarantee correctness.',
    }
    (OUT / 'diagnosis.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'phone_reports': len(actual), 'duplicate_gate_matches': matches,
                      'nearest': nearest[:2]}, ensure_ascii=False))


if __name__ == '__main__':
    main()
