"""Create YOLO data files with identical field-only validation for an ablation.

Run this on the training machine after unpacking data. No training is started.
"""
import argparse
import json
from pathlib import Path


def configure(data, out):
    data = data.resolve()
    if out.exists():
        raise FileExistsError("Choose a new configuration directory")
    taxonomy = json.loads((data / "taxonomy_v1.json").read_text(encoding="utf-8"))
    classes = taxonomy["classes"]
    if [row["id"] for row in classes] != list(range(len(classes))):
        raise ValueError("Taxonomy IDs must remain contiguous and unchanged")
    lists = {}
    for name in ("train", "train_with_negatives", "val_field"):
        annotation = json.loads((data / "annotations" / f"{name}.json").read_text(encoding="utf-8"))
        paths = [(data / row["file_name"]).resolve() for row in annotation["images"]]
        if not paths or any(not path.is_file() for path in paths):
            raise ValueError(f"Missing images for {name}")
        lists[name] = paths
    val = set(lists["val_field"])
    if any(val.intersection(lists[name]) for name in ("train", "train_with_negatives")):
        raise ValueError("Validation image included in training")
    out.mkdir(parents=True)
    for name, paths in lists.items():
        (out / f"{name}.txt").write_text("".join(path.as_posix() + "\n" for path in paths), encoding="utf-8")
    names = [row["code"] for row in classes]
    for name in ("train", "train_with_negatives"):
        # JSON strings/lists are valid YAML, including paths containing spaces.
        config = "train: " + json.dumps((out / f"{name}.txt").resolve().as_posix()) + "\n"
        config += "val: " + json.dumps((out / "val_field.txt").resolve().as_posix()) + "\n"
        config += "names: " + json.dumps(names) + "\n"
        (out / f"{name}.yaml").write_text(config, encoding="utf-8")
    report = {"counts": {name: len(paths) for name, paths in lists.items()},
              "validation": "field_only", "test_exposed": False,
              "training_performed": False, "data": data.as_posix()}
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(configure(args.data, args.out), indent=2))
