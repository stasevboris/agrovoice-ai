"""Write identical validation tensors for desktop and Android export checks."""
from pathlib import Path
import argparse
import json
import os

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault('YOLO_CONFIG_DIR', str(ROOT/'ml-pipeline/.cache/ultralytics/windows'))


def main():
    import cv2
    import numpy as np
    from ultralytics.data.augment import LetterBox
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sizes', type=int, nargs='+', default=[416])
    args = parser.parse_args()
    output = ROOT/'ml-pipeline/.cache/phone_benchmark'
    output.mkdir(parents=True, exist_ok=True)
    files = sorted((ROOT/'ml-pipeline/dataset_clean_v1/images/val').glob('*.jpg'))[:5]
    records = []
    for size in args.sizes:
        for i, path in enumerate(files):
            image = LetterBox(new_shape=(size, size), auto=False)(image=cv2.imread(str(path)))
            batch = np.ascontiguousarray(image.transpose(2, 0, 1)[::-1][None], dtype='<f4')/255
            target = output/f'input_{size}_{i}.bin'
            batch.tofile(target)
            records.append(dict(file=target.name, image=path.name, size=size, shape=list(batch.shape)))
    (output/'inputs.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
    print(output)


if __name__ == '__main__':
    main()
