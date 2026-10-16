"""Inventory existing new-source cucumber frames and preview whole-leaf candidates.

This does not accept disease annotations, create training data, or launch training.
"""
from __future__ import annotations
import collections, hashlib, io, json, zipfile
from pathlib import Path
from PIL import Image, ImageOps
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
from prepare_supplemented import signature

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT/'ml-pipeline/source_candidates/cucumber_zenodo_16816441_tags.zip'
OUT = ROOT/'docs/experiments/yolo26s_data_v1/cucumber_review'
LEAF_FOLDERS = {
    'anthracnose': 'Cucumber leaf anthracnose',
    'bacterial_wilt': 'Cucumber leaf bacterial wilt',
    'downy_mildew': 'Cucumber leaf downy mildew',
    'fresh_leaf': 'Cucumber healthy leaf',
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with ARCHIVE.open('rb') as f:
        md5 = hashlib.file_digest(f, 'md5').hexdigest()
    assert md5 == '0c0797650bfcdf66fdf528f5a605909e'
    rows, previews = [], {}
    with zipfile.ZipFile(ARCHIVE) as outer:
        for folder, target in LEAF_FOLDERS.items():
            member = f'Image tags/{folder}.zip'
            with zipfile.ZipFile(io.BytesIO(outer.read(member))) as inner:
                names = sorted(n for n in inner.namelist() if n.lower().endswith('.jpg'))
                for name in names:
                    label = f'labels/{Path(name).stem}.txt'
                    source_boxes = [list(map(float, x.split())) for x in inner.read(label).decode('utf-8-sig').splitlines() if x.strip()]
                    payload = inner.read(name)
                    with Image.open(io.BytesIO(payload)) as image:
                        rgb = ImageOps.exif_transpose(image).convert('RGB')
                        sig = signature(rgb)
                        orientation = image.getexif().get(274, 1)
                        valid = all(len(b)==5 and b[0]==0 and 0<=b[1]<=1 and 0<=b[2]<=1 and 0<b[3]<=1 and 0<b[4]<=1 for b in source_boxes)
                        eligible = (valid and orientation==1 and len(source_boxes)==1 and source_boxes[0][3]*source_boxes[0][4]>=.25 and min(source_boxes[0][3:])>=.45)
                        item_id = f'{folder}_{Path(name).stem}'
                        if eligible:
                            rgb.thumbnail((480, 400))
                            previews[item_id] = rgb.copy()
                    rows.append({'id': item_id, 'archive_member': member, 'image_member': name,
                                 'label_member': label, 'source_class': folder, 'proposed_class': target,
                                 'source_boxes': source_boxes, 'source_sha256': hashlib.sha256(payload).hexdigest(),
                                 'source_signature': sig, 'bytes': len(payload), 'valid_rows': valid,
                                 'eligible_for_whole_leaf_visual_review': eligible,
                                 'acceptance': 'pending_visual_and_source_label_review' if eligible else 'needs_annotation_or_exclusion',
                                 'diagnosis_basis': 'Source category; not a diagnosis generated from appearance'})
                print(f'{folder}: {len(names)} original images', flush=True)
    selected = [r for r in rows if r['eligible_for_whole_leaf_visual_review']]
    for start in range(0, len(selected), 16):
        fig, axes = plt.subplots(4, 4, figsize=(14, 12))
        for axis in axes.flat:
            axis.axis('off')
        for axis, row in zip(axes.flat, selected[start:start+16]):
            image = previews[row['id']]
            axis.imshow(image)
            _, x, y, w, h = row['source_boxes'][0]
            axis.add_patch(Rectangle(((x-w/2)*image.width, (y-h/2)*image.height), w*image.width, h*image.height,
                                     fill=False, edgecolor='red', linewidth=1.2))
            axis.set_title(f"#{rows.index(row)} {row['source_class']}\n{Path(row['image_member']).stem[-25:]}", fontsize=8)
        fig.suptitle('Unaccepted source boxes: verify WHOLE visible leaf; disease label is source-provided')
        fig.tight_layout()
        fig.savefig(OUT/f'candidates_{start//16+1:02}.jpg', dpi=125)
        plt.close(fig)
    source = {'url': 'https://zenodo.org/records/16816441', 'original_url': 'https://data.mendeley.com/datasets/y6d3z6f8z9/1',
              'license': 'CC BY 4.0', 'archive': str(ARCHIVE.relative_to(ROOT)), 'archive_md5': md5,
              'relationship': 'Curated copy of Sultana cucumber dataset, not a second independent cohort'}
    counts = {c: {'originals': sum(r['source_class']==c for r in rows),
                  'single_large_box_review_candidates': sum(r['source_class']==c and r['eligible_for_whole_leaf_visual_review'] for r in rows)}
              for c in LEAF_FOLDERS}
    output = {'status': 'review_candidates_only', 'source': source, 'classes': counts, 'images': rows,
              'training_accepted': 0, 'policy': 'No automatic conversion of symptoms/fruits/partial boxes into whole-leaf disease detection. Original category != verified diagnosis of every background leaf.'}
    (OUT/'inventory.json').write_text(json.dumps(output, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'classes': counts, 'candidate_sheets': (len(selected)+15)//16, 'training_accepted': 0}, ensure_ascii=False))


if __name__ == '__main__':
    main()
