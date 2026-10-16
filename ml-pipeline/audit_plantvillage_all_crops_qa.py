"""Verify and review staged PV pairs; does not accept labels or make splits."""
from __future__ import annotations
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib, json
from pathlib import Path
import cv2
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
from PIL import Image
from prepare_supplemented import leaf_group, mask_box, signature

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'ml-pipeline/source_candidates/yolo26s_data_v1/plantvillage_all_crops'
OUT = ROOT/'docs/experiments/yolo26s_data_v1/plantvillage_qa_r1'


def main():
    cv2.setNumThreads(2)
    OUT.mkdir(parents=True, exist_ok=True)
    if (OUT/'inventory.json').exists():
        raise FileExistsError('Review evidence already exists; use a new revision')
    index = json.loads((SOURCE/'qa_downloads.json').read_text(encoding='utf-8'))
    mapping = json.loads((ROOT/'ml-pipeline/source_candidates/plantvillage_subset/leaf-map.json').read_text())
    pairs = defaultdict(dict)
    for item in index['files']:
        pairs[(item['category'], item['color_filename'])][item['variant']] = item
    rows = []
    for (category, filename), pair in sorted(pairs.items()):
        flags = []
        for variant in ('color', 'segmented'):
            item = pair[variant]
            payload = (ROOT/item['candidate_path']).read_bytes()
            if hashlib.sha256(payload).hexdigest() != item['sha256']:
                raise ValueError('Staged source SHA mismatch: '+item['candidate_path'])
            blob = hashlib.sha1(f'blob {len(payload)}\0'.encode()+payload).hexdigest()
            if blob != item['git_blob']:
                raise ValueError('Publisher Git blob mismatch')
        with Image.open(ROOT/pair['color']['candidate_path']) as image, Image.open(ROOT/pair['segmented']['candidate_path']) as segmented:
            if image.size != segmented.size or image.getexif().get(274, 1) != 1:
                flags.append('mask_rgb_geometry_mismatch')
                box = None
            else:
                box, reason = mask_box(segmented)
                if reason:
                    flags.append(reason)
            sig = signature(image)
        group = leaf_group(filename, category, mapping)
        if group is None:
            flags.append('physical_leaf_group_unknown_or_ambiguous')
        rows.append(dict(id=hashlib.sha256((category+'/'+filename).encode()).hexdigest()[:16],
            source_class=category, source_filename=filename, source_files=pair,
            physical_leaf_group_candidate=group, signature=sig,
            proposed_box_xywh=box, flags=flags, visual_review='pending', training_acceptance='pending'))
    sheets = []
    # Sixteen originals per sheet, color and source mask shown separately.
    for start in range(0, len(rows), 16):
        fig, axes = plt.subplots(4, 8, figsize=(20, 12))
        for axis in axes.flat:
            axis.axis('off')
        for number, row in enumerate(rows[start:start+16]):
            ar, ac = divmod(number, 4)
            for j, variant in enumerate(('color', 'segmented')):
                axis = axes[ar, 2*ac+j]
                with Image.open(ROOT/row['source_files'][variant]['candidate_path']) as image:
                    axis.imshow(image)
                    if row['proposed_box_xywh']:
                        x, y, w, h = row['proposed_box_xywh']
                        axis.add_patch(Rectangle(((x-w/2)*image.width, (y-h/2)*image.height), w*image.width, h*image.height, fill=False, edgecolor='red', linewidth=1.1))
                title = row['source_class'].replace('___', ': ')+'\n'+row['id']
                if j:
                    title = 'source mask\n'+(','.join(row['flags']) or 'geometry gate passed')
                axis.set_title(title, fontsize=7)
        name = f'pairs_{start//16+1:02}.jpg'
        fig.suptitle('PlantVillage staged QA: source masks / proposals; no accepted training labels')
        fig.tight_layout(); fig.savefig(OUT/name, dpi=125); plt.close(fig)
        sheets.append(dict(path=str((OUT/name).relative_to(ROOT)), ids=[r['id'] for r in rows[start:start+16]], visually_seen=False))
    result = dict(status='QA_only_not_training_ready', updated_utc=datetime.now(timezone.utc).isoformat(),
        source_revision=index['revision'], index_sha256=hashlib.sha256((SOURCE/'qa_downloads.json').read_bytes()).hexdigest(),
        algorithm='Existing PV largest-component geometry quality gate; visual mask alignment still required',
        helper_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        counts=dict(pairs=len(rows), classes=len({r['source_class'] for r in rows}), geometry_or_group_flagged=sum(bool(r['flags']) for r in rows), training_accepted=0),
        flags=dict(Counter(flag for r in rows for flag in r['flags'])), sheets=sheets, images=rows)
    (OUT/'inventory.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result['counts']))


if __name__ == '__main__':
    main()
