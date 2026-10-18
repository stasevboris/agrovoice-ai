"""Write a D-FINE dataset override; does not run or install D-FINE."""

import argparse
import json
from pathlib import Path


def configure(repo, data, out, negatives=False):
    base = repo.resolve() / "configs/dfine/dfine_hgnetv2_n_coco.yml"
    if not base.is_file():
        raise FileNotFoundError(base)
    data = data.resolve()
    taxonomy = json.loads((data / "taxonomy_v1.json").read_text(encoding="utf-8"))
    classes = taxonomy["classes"]
    if [c["id"] for c in classes] != list(range(len(classes))):
        raise ValueError("Expected contiguous unchanged zero-based taxonomy IDs")
    train = data / "annotations" / ("train_with_negatives.json" if negatives else "train.json")
    val = data / "annotations/val_field.json"
    if not train.is_file() or not val.is_file():
        raise FileNotFoundError("Prepared COCO annotations missing")
    # JSON string quoting is valid YAML; paths remain literal, including spaces.
    def quote(path):
        return json.dumps(str(path).replace("\\", "/"))
    text = f"__include__: [{quote(base)}]\nnum_classes: {len(classes)}\nremap_mscoco_category: False\n"
    for loader, annotation in (("train_dataloader", train), ("val_dataloader", val)):
        text += f"{loader}:\n  dataset:\n    img_folder: {quote(data)}\n    ann_file: {quote(annotation)}\n"
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("x", encoding="utf-8") as stream:
        stream.write(text)
    return text


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--negatives", action="store_true")
    args = parser.parse_args()
    configure(args.repo, args.data, args.out, args.negatives)
