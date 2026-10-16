"""Fetch a capped, pinned PlantVillage subset plus segmented counterparts for leaf boxes."""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import time
from urllib.parse import quote
import requests

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'spMohanty/PlantVillage-Dataset'
# Capped supplements avoid replacing the field dataset with a large laboratory domain.
CATEGORIES = {
    'Potato___healthy': ('Potato leaf', 152),
    'Tomato___Spider_mites Two-spotted_spider_mite': ('Tomato two spotted spider mites leaf', 200),
    'Tomato___Early_blight': ('Tomato Early blight leaf', 150),
    'Tomato___Bacterial_spot': ('Tomato leaf bacterial spot', 150),
    'Tomato___Leaf_Mold': ('Tomato mold leaf', 150),
    'Tomato___Tomato_mosaic_virus': ('Tomato leaf mosaic virus', 150),
    'Tomato___healthy': ('Tomato leaf', 150),
}


def fetch(url):
    for attempt in range(4):
        try:
            response = requests.get(url, timeout=60, headers={'User-Agent': 'AgroVoice-data-audit'})
            response.raise_for_status()
            return response.content
        except requests.RequestException:
            if attempt == 3:
                raise
            time.sleep(2**attempt)


def api(path):
    cache = ROOT/'ml-pipeline/source_candidates/plantvillage_subset/api_cache'
    cache.mkdir(parents=True, exist_ok=True)
    target = cache/(hashlib.sha256(path.encode()).hexdigest()+'.json')
    if not target.exists():
        target.write_bytes(fetch(f'https://api.github.com/repos/{REPOSITORY}/{path}'))
    return json.loads(target.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preview', action='store_true', help='Fetch only two pairs per category first')
    parser.add_argument('--refresh-index', action='store_true')
    args = parser.parse_args()
    output = ROOT/'ml-pipeline/source_candidates/plantvillage_subset'
    output.mkdir(parents=True, exist_ok=True)
    index_path = output/'download_index.json'
    if index_path.exists() and not args.refresh_index:
        index = json.loads(index_path.read_text(encoding='utf-8'))
    else:
        commit = api('commits/master')['sha']
        tree = api(f'git/trees/{commit}')['tree']
        raw_sha = next(item['sha'] for item in tree if item['path'] == 'raw')
        variants = api(f'git/trees/{raw_sha}')['tree']
        folders = {variant: {item['path']: item['sha'] for item in api(
            'git/trees/'+next(entry['sha'] for entry in variants if entry['path'] == variant))['tree']}
            for variant in ('color', 'segmented')}
        index = dict(source=f'https://github.com/{REPOSITORY}', revision=commit,
                     license='CC-BY-SA-3.0', license_source='https://huggingface.co/datasets/mohanty/PlantVillage',
                     selection='Sort SHA256(filename); capped deterministic sample. Original photos only, no augmentation.',
                     categories=[], files=[])
        for category, (target, cap) in CATEGORIES.items():
            color = api('git/trees/'+folders['color'][category])['tree']
            segmented = {Path(item['path']).stem.removesuffix('_final_masked'): item
                         for item in api('git/trees/'+folders['segmented'][category])['tree']}
            available = [item for item in color if item['type'] == 'blob' and Path(item['path']).stem in segmented]
            available.sort(key=lambda item: hashlib.sha256(item['path'].encode()).hexdigest())
            chosen = available[:cap]
            index['categories'].append(dict(source_class=category, target_class=target,
                                            available_pairs=len(available), selected=len(chosen)))
            for item in chosen:
                for variant, entry in [('color', item), ('segmented', segmented[Path(item['path']).stem])]:
                    index['files'].append(dict(path=f'raw/{variant}/{category}/{entry["path"]}',
                                               color_filename=item['path'],
                                               git_blob=entry['sha'], category=category, variant=variant))
        index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf-8')
        (output/'leaf-map.json').write_bytes(fetch(
            f'https://raw.githubusercontent.com/{REPOSITORY}/{commit}/leaf-map.json'))
        (output/'README_HF.md').write_bytes(fetch(
            f'https://raw.githubusercontent.com/{REPOSITORY}/{commit}/README_HF.md'))
    files = index['files']
    if args.preview:
        selected_names = {category['source_class']: [item['path'].rsplit('/', 1)[1]
            for item in files if item['category'] == category['source_class'] and item['variant'] == 'color'][:2]
            for category in index['categories']}
        files = [item for item in files if item['color_filename'] in selected_names[item['category']]]

    def download(item):
        path = output/item['path']
        payload = path.read_bytes() if path.exists() else fetch(
            f'https://raw.githubusercontent.com/{REPOSITORY}/{index["revision"]}/'+quote(item['path'], safe='/'))
        git_hash = hashlib.sha1(f'blob {len(payload)}\0'.encode()+payload).hexdigest()
        if git_hash != item['git_blob']:
            raise RuntimeError(f'Git blob checksum mismatch: {item["path"]}')
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
        return dict(path=item['path'], sha256=hashlib.sha256(payload).hexdigest(), bytes=len(payload))

    checked = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        pending = [pool.submit(download, item) for item in files]
        for i, future in enumerate(as_completed(pending), 1):
            checked.append(future.result())
            if i % 100 == 0:
                print(f'Checked/downloaded {i}/{len(files)}', flush=True)
    target = output/('preview_checksums.json' if args.preview else 'checksums.json')
    target.write_text(json.dumps(sorted(checked, key=lambda r: r['path']), indent=2), encoding='utf-8')
    print(json.dumps(dict(revision=index['revision'], categories=index['categories'], files=len(checked)), indent=2))


if __name__ == '__main__':
    main()
