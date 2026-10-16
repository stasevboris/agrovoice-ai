"""Freeze v2: unchanged PlantDoc holdouts, capped mask-derived PV, reviewed crop-only cucumber."""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import hashlib
import io
import json
from pathlib import Path
import shutil
import zipfile
import cv2
import numpy as np
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SEED = 20261003


def digest(payload):
    return hashlib.sha256(payload).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def signature(image):
    rgb = np.asarray(image.convert('RGB'))
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    dct = cv2.dct(cv2.resize(gray, (32, 32)).astype(np.float32))[:8, :8]
    bits = dct > np.median(dct.flat[1:])
    bits[0, 0] = False
    small = cv2.resize(gray, (9, 8))
    dh = small[:, 1:] > small[:, :-1]
    pack = lambda a: int.from_bytes(np.packbits(a.flatten()).tobytes(), 'big')
    return dict(decoded_hash=digest(str(image.size).encode()+rgb.tobytes()),
                phash=pack(bits), dhash=pack(dh), size=list(image.size))


def near(a, b, threshold=4):
    ratio = (a['size'][0]/a['size'][1])/(b['size'][0]/b['size'][1])
    return (a['decoded_hash'] == b['decoded_hash'] or
            (abs(ratio-1) <= .03 and (a['phash'] ^ b['phash']).bit_count() <= threshold
             and (a['dhash'] ^ b['dhash']).bit_count() <= threshold))


def mask_box(segmented):
    """Quality gate; boxes are derived, not manual lesion annotations."""
    mask = (np.asarray(segmented.convert('RGB')).max(axis=2) > 25).astype(np.uint8)
    n, components, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    if n < 2:
        return None, 'empty_mask'
    k = 1+int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    x, y, w, h, area = map(int, stats[k])
    height, width = mask.shape
    fraction = area/(height*width)
    if not .08 <= fraction <= .88:
        return None, 'mask_area_or_background_leakage'
    if area/int(mask.sum()) < .90:
        return None, 'multiple_components'
    edges = sum((x <= 1, y <= 1, x+w >= width-1, y+h >= height-1))
    if edges >= 3 or (w/width >= .97 and h/height >= .97):
        return None, 'mask_spans_background_or_truncated_leaf'
    x0, y0, x1, y1 = max(0, x-1), max(0, y-1), min(width, x+w+1), min(height, y+h+1)
    return [(x0+x1)/(2*width), (y0+y1)/(2*height), (x1-x0)/width, (y1-y0)/height], None


def leaf_group(filename, category, mapping):
    key = Path(filename).stem.split('___', 1)[-1].casefold().strip()
    matches = [v for v in mapping.get(key, []) if v.split(':::', 1)[0] == category]
    return 'pv:'+matches[0] if len(matches) == 1 else None


def group_split(records, seed=SEED):
    groups = defaultdict(list)
    for r in records:
        groups[r['group_id']].append(r)
    by_class = defaultdict(list)
    for group, items in groups.items():
        by_class[items[0]['boxes'][0][0]].append(group)
    for cls, keys in by_class.items():
        ordered = sorted(keys, key=lambda k: digest(f'{seed}:{k}'.encode()))
        count = max(1, round(len(ordered)*.20)) if len(ordered) >= 2 else 0
        auxiliary = set(ordered[:count])
        for key in ordered:
            for r in groups[key]:
                r['split'] = 'val_aux' if key in auxiliary else 'train'
    return records


def build(base, output):
    if output.exists():
        raise FileExistsError(f'Frozen dataset already exists: {output}')
    old_report = read_json(base/'report.json')
    names = read_json(base/'classes.json')+['Cucumber leaf (condition unknown)']
    records = read_json(base/'manifest.json')
    heldout, existing = [], []
    for i, r in enumerate(records):
        path = base/'images'/r['split']/r['filename']
        with Image.open(path) as image:
            sig = signature(image)
        r.update(source_dataset='PlantDoc', group_id='pd:'+sig['decoded_hash'],
                 prepared_sha256=digest(path.read_bytes()), source_signature=sig)
        existing.append(r)
        if r['split'] != 'train':
            heldout.append(r)
        if (i+1) % 500 == 0:
            print(f'Checked original files: {i+1}/{len(records)}', flush=True)
    issues, supplements, payloads = [], [], {}
    pv = ROOT/'ml-pipeline/source_candidates/plantvillage_subset'
    index = read_json(pv/'download_index.json')
    checksums = {r['path']: r['sha256'] for r in read_json(pv/'checksums.json')}
    mapping = read_json(pv/'leaf-map.json')
    target_by_category = {r['source_class']: r['target_class'] for r in index['categories']}
    pairs = defaultdict(dict)
    for item in index['files']:
        pairs[(item['category'], item['color_filename'])][item['variant']] = item['path']
    for (category, filename), pair in sorted(pairs.items()):
        def reject(reason):
            issues.append(dict(source='PlantVillage', filename=filename, reason=reason))
        if category == 'Tomato___Leaf_Mold':
            reject('category_excluded_after_visual_mask_background_leakage'); continue
        group = leaf_group(filename, category, mapping)
        if not group:
            reject('physical_leaf_group_unknown_or_ambiguous'); continue
        rgb_payload, seg_payload = [(pv/pair[v]).read_bytes() for v in ('color', 'segmented')]
        if any(digest(payload) != checksums[pair[v]] for v, payload in
               [('color', rgb_payload), ('segmented', seg_payload)]):
            raise RuntimeError('Source checksum changed')
        with Image.open(io.BytesIO(rgb_payload)) as image, Image.open(io.BytesIO(seg_payload)) as segmented:
            if image.getexif().get(274, 1) != 1 or image.size != segmented.size:
                reject('mask_rgb_geometry_mismatch'); continue
            box, reason = mask_box(segmented)
            if reason:
                reject(reason); continue
            rgb = image.convert('RGB'); sig = signature(rgb)
        cls = names.index(target_by_category[category])
        file = 'pv_'+digest(pair['color'].encode())[:20]+'.jpg'
        r = dict(filename=file, source_dataset='PlantVillage', sources=[pair['color'], pair['segmented']],
                 group_id=group, source_signature=sig, pixel_hash=sig['decoded_hash'],
                 size=sig['size'], raw_hash=digest(rgb_payload), boxes=[[cls, *box]],
                 annotation_method='bbox_from_source_segmentation_largest_component', source_revision=index['revision'])
        supplements.append(r); payloads[file] = rgb_payload
    cuc_review = read_json(ROOT/'docs/data/cucumber_crop_review.json')
    candidates = read_json(ROOT/'docs/data/cucumber_crop_candidates.json')['candidates']
    cucumber_archive = ROOT/'ml-pipeline/source_candidates/cucumber_zenodo_16816441_tags.zip'
    with zipfile.ZipFile(cucumber_archive) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read('Image tags/fresh_leaf.zip'))) as inner:
            for candidate in candidates:
                if candidate['id'] in cuc_review['reject_ids']:
                    issues.append(dict(source='Cucumber', filename=candidate['source'], reason='visual_review_rejected')); continue
                payload = inner.read(candidate['source'])
                with Image.open(io.BytesIO(payload)) as image:
                    if image.getexif().get(274, 1) != 1:
                        raise ValueError('Unexpected cucumber EXIF; re-review boxes')
                    sig = signature(image)
                file = 'cuc_'+digest(candidate['source'].encode())[:20]+'.jpg'
                supplements.append(dict(filename=file, source_dataset='Cucumber', sources=[candidate['source'], candidate['label']],
                     group_id='cuc:'+sig['decoded_hash'], source_signature=sig, pixel_hash=sig['decoded_hash'],
                     size=sig['size'], raw_hash=digest(payload), boxes=[[29, *candidate['box']]],
                     annotation_method='reviewed_source_box_crop_only', visual_review_id=candidate['id']))
                payloads[file] = payload
    print(f'Supplements passing annotation gates: {len(supplements)}', flush=True)
    # Conservative similarities to every original frame are excluded. This also avoids
    # adding a supplement to train when its group overlaps the unchanged holdouts.
    accepted = []
    for r in supplements:
        match = next((b for b in existing if near(r['source_signature'], b['source_signature'])), None)
        if match:
            issues.append(dict(source=r['source_dataset'], filename=r['filename'],
                          reason='similar_to_original_'+match['split'], original=match['filename']))
        else:
            accepted.append(r)
    # Connected components join physical leaf IDs, exact duplicates and detected
    # near-duplicates before splitting. Conflicting class labels are excluded.
    parent = list(range(len(accepted)))
    def find(k):
        while parent[k] != k:
            parent[k] = parent[parent[k]]; k = parent[k]
        return k
    def join(a, b):
        parent[find(b)] = find(a)
    for i, a in enumerate(accepted):
        for j in range(i):
            b = accepted[j]
            threshold = 6 if a['source_dataset'] == b['source_dataset'] == 'Cucumber' else 4
            if a['group_id'] == b['group_id'] or near(a['source_signature'], b['source_signature'], threshold):
                join(i, j)
    components = defaultdict(list)
    for i, r in enumerate(accepted):
        components[find(i)].append(r)
    grouped = []
    for items in components.values():
        if len({r['boxes'][0][0] for r in items}) != 1:
            issues.extend(dict(source=r['source_dataset'], filename=r['filename'], reason='group_conflicting_class_labels') for r in items)
            continue
        group_id = 'supp:'+digest('|'.join(sorted({r['group_id'] for r in items})).encode())
        seen = set()
        for r in sorted(items, key=lambda r: r['filename']):
            if r['pixel_hash'] in seen:
                issues.append(dict(source=r['source_dataset'], filename=r['filename'], reason='exact_duplicate_supplement')); continue
            seen.add(r['pixel_hash']); r['group_id'] = group_id; grouped.append(r)
    group_split(grouped)
    all_records = sorted(existing+grouped, key=lambda r: r['filename'])
    output.mkdir(parents=True)
    for split in ('train', 'val', 'test', 'val_aux'):
        (output/'images'/split).mkdir(parents=True)
        (output/'labels'/split).mkdir(parents=True)
    for r in all_records:
        dst = output/'images'/r['split']/r['filename']
        label = output/'labels'/r['split']/Path(r['filename']).with_suffix('.txt')
        if r['source_dataset'] == 'PlantDoc':
            shutil.copyfile(base/'images'/r['split']/r['filename'], dst)
            shutil.copyfile(base/'labels'/r['split']/label.name, label)
        else:
            dst.write_bytes(payloads[r['filename']])
            label.write_text('\n'.join(f'{b[0]} '+' '.join(f'{v:.8f}' for v in b[1:]) for b in r['boxes'])+'\n', encoding='utf-8')
        r['prepared_sha256'] = digest(dst.read_bytes())
        r['label_sha256'] = digest(label.read_bytes())
    yaml_names = ''.join(f'  {k}: {json.dumps(n)}\n' for k, n in enumerate(names))
    (output/'data.yaml').write_text('train: images/train\nval: images/val\ntest: images/test\nnames:\n'+yaml_names, encoding='utf-8')
    (output/'data_aux.yaml').write_text('train: images/train\nval: images/val_aux\nnames:\n'+yaml_names, encoding='utf-8')
    manifest = json.dumps(all_records, ensure_ascii=False, sort_keys=True, indent=2)
    fingerprint = digest(manifest.encode())
    (output/'manifest.json').write_text(manifest, encoding='utf-8')
    (output/'classes.json').write_text(json.dumps(names, ensure_ascii=False, indent=2), encoding='utf-8')
    counts, splits = {}, {}
    for s in ('train', 'val', 'test', 'val_aux'):
        items = [r for r in all_records if r['split'] == s]
        counts[s] = Counter(b[0] for r in items for b in r['boxes'])
        splits[s] = dict(images=len(items), boxes=sum(counts[s].values()),
                         absent_classes=[n for k, n in enumerate(names) if not counts[s][k]],
                         sources=dict(Counter(r['source_dataset'] for r in items)))
    train_groups = {r['group_id'] for r in all_records if r['split'] == 'train'}
    if train_groups & {r['group_id'] for r in all_records if r['split'] != 'train'}:
        raise AssertionError('Group leakage')
    for split in ('val', 'test'):
        original = {(r['filename'], r['prepared_sha256']) for r in existing if r['split'] == split}
        frozen = {(r['filename'], r['prepared_sha256']) for r in all_records if r['split'] == split}
        if frozen != original:
            raise AssertionError('Original holdout changed')
    report = dict(dataset=output.name, seed=SEED, fingerprint=fingerprint,
         base_dataset=old_report['dataset'], base_fingerprint=old_report['fingerprint'],
         annotation_target='whole_leaf_with_crop_and_condition_or_explicit_unknown', splits=splits,
         classes=[dict(id=k, name=n, **{s: counts[s][k] for s in counts}) for k, n in enumerate(names)],
         sources=[dict(name='PlantDoc', url=old_report['source_url'], license='CC-BY-4.0'),
                  dict(name='PlantVillage', url=index['source'], revision=index['revision'], license=index['license'],
                       license_source=index['license_source'], annotation='Derived whole-leaf boxes from segmented counterparts; laboratory RGB retained'),
                  dict(name='Cucumber', url='https://zenodo.org/records/16816441', license='CC-BY-4.0',
                       archive_md5='0c0797650bfcdf66fdf528f5a605909e', annotation='53 visually accepted candidates before duplicate gates; source disease labels discarded')],
         split_policy='Original PlantDoc train/val/test preserved. Supplements grouped by physical leaf IDs and detected similarities; 20% groups held out as val_aux. Primary val is field PlantDoc only.',
         duplicate_policy='Exclude supplements similar to any original image; exact decoded hash or pHash/dHash <=4 with aspect tolerance 3%. Cucumber supplementary grouping threshold 6. Not proof of exhaustive near-duplicate removal.',
         issues=issues, issue_counts=dict(Counter(r['reason'] for r in issues)),
         limitations=['PlantVillage domain is laboratory; derived boxes are not expert lesion annotations',
                      'Primary val/test have no cucumber; crop-only auxiliary validation cannot establish disease detection',
                      'Original source already has sparse classes and may contain undetected near-duplicates',
                      'Whole-leaf bounding boxes cannot measure percentage of diseased tissue'])
    (output/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (ROOT/f'docs/data/{output.name}_report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(fingerprint=fingerprint, splits=splits, excluded=report['issue_counts']), indent=2), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=ROOT/'ml-pipeline/dataset_clean_v1')
    parser.add_argument('--output', type=Path, default=ROOT/'ml-pipeline/dataset_clean_v2')
    args = parser.parse_args()
    build(args.base, args.output)
