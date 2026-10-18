"""Create optional train-only negative augmentation; never touch val/test."""

import json
import os
from pathlib import Path

from prepare_selected import ROOT, write_json


def attach(vision, negatives):
    report = json.loads((negatives / "report.json").read_text(encoding="utf-8"))
    if report.get("training_ready") is not True:
        raise ValueError("Unreviewed negatives cannot enter training")
    coco = json.loads((vision / "annotations/train.json").read_text(encoding="utf-8"))
    image_id = max((row["id"] for row in coco["images"]), default=0)
    paths = ["./" + row["file_name"] for row in coco["images"]]
    added = 0
    from PIL import Image
    with (negatives / "manifest.jsonl").open(encoding="utf-8") as stream:
        for row in map(json.loads, stream):
            if row["split"] != "train":
                continue
            image_id += 1
            image_path = negatives / row["image"]
            relative = Path(os.path.relpath(image_path, vision)).as_posix()
            with Image.open(image_path) as image:
                width, height = image.size
            coco["images"].append({"id": image_id, "file_name": relative, "width": width,
                                   "height": height, "source": "OpenImages_reviewed_nonplant",
                                   "domain": "negative"})
            paths.append("./" + relative)
            added += 1
    write_json(vision / "annotations/train_with_negatives.json", coco)
    (vision / "train_with_negatives.txt").write_text("\n".join(paths) + "\n", encoding="utf-8")
    yaml = (vision / "data.yaml").read_text(encoding="utf-8")
    (vision / "data_with_negatives.yaml").write_text(yaml.replace("train: images/train", "train: train_with_negatives.txt"), encoding="utf-8")
    result = {"added_train_negatives": added, "images": len(coco["images"]),
              "annotations": len(coco["annotations"]), "val_test_modified": False}
    write_json(vision / "negative_augmentation_report.json", result)
    return result


if __name__ == "__main__":
    print(attach(ROOT / "ml-pipeline/dataset_vision_training_v1",
                 ROOT / "ml-pipeline/dataset_nonplant_openimages_v2"))
