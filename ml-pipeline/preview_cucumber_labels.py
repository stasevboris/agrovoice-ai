"""Create a four-image annotation figure for assessing the candidate data."""
import io
from pathlib import Path
import zipfile
from PIL import Image
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle

root = Path(__file__).resolve().parents[1]
groups = ['anthracnose', 'bacterial_wilt', 'downy_mildew', 'fresh_leaf']
figure, axes = plt.subplots(2, 2, figsize=(12, 9))
with zipfile.ZipFile(root/'ml-pipeline/source_candidates/cucumber_zenodo_16816441_tags.zip') as outer:
    for axis, group in zip(axes.flat, groups):
        with zipfile.ZipFile(io.BytesIO(outer.read('Image tags/'+group+'.zip'))) as archive:
            label = sorted(n for n in archive.namelist() if n.startswith('labels/') and n.endswith('.txt'))[0]
            stem = Path(label).stem
            image_name = next(n for n in archive.namelist() if Path(n).stem == stem and n.lower().endswith('.jpg'))
            image = Image.open(io.BytesIO(archive.read(image_name))).convert('RGB')
            width, height = image.size
            axis.imshow(image)
            for line in archive.read(label).decode('utf-8').splitlines():
                if not line.strip():
                    continue
                cls, x, y, w, h = map(float, line.split())
                axis.add_patch(Rectangle(((x-w/2)*width, (y-h/2)*height), w*width, h*height,
                                         fill=False, edgecolor='red', linewidth=1.2))
            axis.set_title(group)
            axis.axis('off')
figure.suptitle('Cucumber candidate: original YOLO annotations (Zenodo 16816441, CC BY 4.0)')
figure.tight_layout()
figure.savefig(root/'docs/data/cucumber_annotation_preview.jpg', dpi=130)
