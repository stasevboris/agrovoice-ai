"""Render deterministic source/annotation samples for visual data QA."""
from pathlib import Path
import hashlib
import io
import json
import zipfile
from PIL import Image
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle

ROOT = Path(__file__).resolve().parents[1]


def cucumber():
    with zipfile.ZipFile(ROOT/'ml-pipeline/source_candidates/cucumber_zenodo_16816441_tags.zip') as outer:
        for group in ('fresh_leaf', 'anthracnose', 'bacterial_wilt', 'downy_mildew'):
            with zipfile.ZipFile(io.BytesIO(outer.read('Image tags/'+group+'.zip'))) as archive:
                labels = sorted([name for name in archive.namelist() if name.startswith('labels/') and name.endswith('.txt')],
                                key=lambda name: hashlib.sha256(name.encode()).hexdigest())[:12]
                images = {Path(name).stem: name for name in archive.namelist() if name.lower().endswith('.jpg')}
                fig, axes = plt.subplots(3, 4, figsize=(14, 10))
                for axis, label in zip(axes.flat, labels):
                    image = Image.open(io.BytesIO(archive.read(images[Path(label).stem]))).convert('RGB')
                    axis.imshow(image)
                    rows = [list(map(float, row.split())) for row in archive.read(label).decode().splitlines() if row.strip()]
                    for _, x, y, w, h in rows:
                        axis.add_patch(Rectangle(((x-w/2)*image.width, (y-h/2)*image.height),
                                                 w*image.width, h*image.height, fill=False, edgecolor='red', linewidth=1))
                    axis.set_title(f'{Path(label).stem}: {len(rows)} boxes', fontsize=9)
                    axis.axis('off')
                fig.suptitle(group+' — source annotations, deterministic sample')
                fig.tight_layout()
                fig.savefig(ROOT/f'docs/data/cucumber_sample_{group}.jpg', dpi=140)
                plt.close(fig)


def village():
    source = ROOT/'ml-pipeline/source_candidates/plantvillage_subset'
    index = json.loads((source/'download_index.json').read_text(encoding='utf-8'))
    fig, axes = plt.subplots(7, 4, figsize=(13, 19))
    for row, category in zip(axes, index['categories']):
        files = [item['path'] for item in index['files'] if item['category'] == category['source_class']
                 and item['variant'] == 'color'][:2]
        for pair, path in enumerate(files):
            for variant in range(2):
                axis = row[2*pair+variant]
                target = path if variant == 0 else next(item['path'] for item in index['files']
                    if item['variant'] == 'segmented' and item['category'] == category['source_class']
                    and item['color_filename'] == Path(path).name)
                axis.imshow(Image.open(source/target))
                axis.set_title(category['source_class']+(' RGB' if variant == 0 else ' segmented'), fontsize=7)
                axis.axis('off')
    fig.tight_layout()
    fig.savefig(ROOT/'docs/data/plantvillage_source_pairs.jpg', dpi=130)
    plt.close(fig)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--village', action='store_true')
    args = parser.parse_args()
    village() if args.village else cucumber()
