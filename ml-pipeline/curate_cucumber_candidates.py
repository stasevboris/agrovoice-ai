"""Preview single-leaf cucumber candidates for crop detection, without disease labels."""
import hashlib
import io
import json
from pathlib import Path
import zipfile
from PIL import Image
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle

ROOT = Path(__file__).resolve().parents[1]


def main():
    candidates = []
    with zipfile.ZipFile(ROOT/'ml-pipeline/source_candidates/cucumber_zenodo_16816441_tags.zip') as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read('Image tags/fresh_leaf.zip'))) as archive:
            images = {Path(name).stem: name for name in archive.namelist() if name.lower().endswith('.jpg')}
            labels = sorted(name for name in archive.namelist() if name.startswith('labels/') and name.endswith('.txt'))
            for label in labels:
                rows = [list(map(float, row.split())) for row in archive.read(label).decode().splitlines() if row.strip()]
                if len(rows) != 1:
                    continue
                _, x, y, w, h = rows[0]
                if w*h < 0.25 or min(w, h) < 0.45 or max(w, h) > 1.001:
                    continue
                candidates.append(dict(id=len(candidates), source=images[Path(label).stem], label=label,
                                       box=rows[0][1:], annotation='Cucumber leaf (condition unknown)'))
            for start in range(0, len(candidates), 16):
                fig, axes = plt.subplots(4, 4, figsize=(13, 11))
                for axis in axes.flat:
                    axis.axis('off')
                for axis, candidate in zip(axes.flat, candidates[start:start+16]):
                    image = Image.open(io.BytesIO(archive.read(candidate['source']))).convert('RGB')
                    axis.imshow(image)
                    x, y, w, h = candidate['box']
                    axis.add_patch(Rectangle(((x-w/2)*image.width, (y-h/2)*image.height),
                                             w*image.width, h*image.height, fill=False, edgecolor='red', linewidth=1))
                    axis.set_title(str(candidate['id'])+' '+Path(candidate['source']).stem[-24:], fontsize=8)
                fig.suptitle('Cucumber crop-only candidates: whole visible leaf, no health claim')
                fig.tight_layout()
                fig.savefig(ROOT/f'docs/data/cucumber_crop_candidates_{start//16+1}.jpg', dpi=140)
                plt.close(fig)
    output = ROOT/'docs/data/cucumber_crop_candidates.json'
    output.write_text(json.dumps(dict(policy='Single source box, area >=0.25, sides >=0.45; requires visual review.',
                                      source='Zenodo 16816441, fresh_leaf.zip', candidates=candidates), indent=2), encoding='utf-8')
    print(len(candidates))


if __name__ == '__main__':
    main()
