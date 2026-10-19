"""Frozen MobileNetV3 features + a learned linear head on labelled whole-leaf crops.

This is a representation probe. Its GT-crop accuracy is not detector or full-phone accuracy.
Launch through train_home_pilot.py --task crop-probe to enforce the process-group deadline.
"""
import hashlib
import json
from pathlib import Path
import time

import numpy as np
from PIL import Image


def splits(rows):
    groups = {}
    parts = {"train": [], "val_field": [], "val_lab": []}
    for row in rows:
        group = row["group"]
        if group in groups and groups[group] != row["split"]:
            raise ValueError("An observation group crosses dataset splits")
        groups[group] = row["split"]
        if row["split"] == "train":
            parts["train"].append(row)
        elif row["split"] == "val":
            parts["val_field" if row["domain"] == "field" else "val_lab"].append(row)
    if any(not rows for rows in parts.values()):
        raise ValueError("Training and both validation domains must be present")
    return parts


def metrics(truth, predictions, classes=49):
    confusion = np.zeros((classes, classes), dtype=np.int64)
    np.add.at(confusion, (truth, predictions), 1)
    support = confusion.sum(axis=1)
    predicted = confusion.sum(axis=0)
    true_positive = confusion.diagonal()
    f1 = 2 * true_positive / np.maximum(support + predicted, 1)
    return {"examples": int(support.sum()), "accuracy": float(true_positive.sum() / max(1, support.sum())),
            "macro_f1_present_classes": float(f1[support > 0].mean()),
            "support": support.tolist(), "confusion": confusion.tolist()}


class Crops:
    def __init__(self, rows, root, transform):
        self.rows = rows
        self.root = root
        self.transform = transform

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, index):
        row = self.rows[index]
        with Image.open(self.root / row["image"]) as image:
            tensor = self.transform(image.convert("RGB"))
        return tensor, row["class_id"]


def train_probe(args):
    from train_home_pilot import deadlines, write_json
    soft, _ = deadlines(args.deadline)
    import torch
    import torchvision
    from torchvision import models, transforms

    if not torch.cuda.is_available() or not torch.version.hip:
        raise RuntimeError("ROCm accelerator is required")
    torch.set_num_threads(4)
    torch.manual_seed(20261019)
    rows = [json.loads(line) for line in (args.data / "crops.jsonl").read_text(encoding="utf-8").splitlines()]
    taxonomy = json.loads((args.data / "taxonomy_v1.json").read_text(encoding="utf-8"))["classes"]
    if len(taxonomy) != 49 or any(row["class_id"] not in range(49) or row["class_code"] != taxonomy[row["class_id"]]["code"] for row in rows):
        raise ValueError("Crop labels do not match the unchanged taxonomy_v1")
    parts = splits(rows)
    root = Path(__file__).resolve().parents[3]
    torch.hub.set_dir(str(root / "ml-pipeline/.cache/torch"))
    pretrained = models.MobileNet_V3_Small_Weights.IMAGENET1K_V1
    base = models.mobilenet_v3_small(weights=pretrained)
    encoder = torch.nn.Sequential(base.features, base.avgpool, torch.nn.Flatten()).eval().to("cuda")
    for parameter in encoder.parameters():
        parameter.requires_grad_(False)
    started = time.monotonic()
    with torch.no_grad():
        probe = encoder(torch.zeros(4, 3, 224, 224, device="cuda"))
    temporary_head = torch.nn.Linear(probe.shape[1], 49).to("cuda")
    probe_loss = temporary_head(probe).square().mean()
    if not torch.isfinite(probe_loss):
        raise RuntimeError("Non-finite probe loss")
    probe_loss.backward()
    torch.cuda.synchronize()
    write_json(args.out / "benchmark.json", {"seconds": time.monotonic() - started,
        "forward_and_head_backward": "passed", "feature_dimensions": probe.shape[1],
        "torch": torch.__version__, "torchvision": torchvision.__version__, "gpu": torch.cuda.get_device_name(0)})
    del temporary_head, probe, base
    if args.smoke:
        random = np.random.default_rng(20261019)
        parts = {name: [items[i] for i in random.choice(len(items), min(64, len(items)), replace=False)] for name, items in parts.items()}
    transform = transforms.Compose([transforms.Resize((224, 224)), transforms.ToTensor(),
        transforms.Normalize((0.485, 0.456, 0.406), (0.229, 0.224, 0.225))])
    prepared = {}
    for name, items in parts.items():
        features, labels = [], []
        loader = torch.utils.data.DataLoader(Crops(items, args.data, transform), batch_size=args.batch,
                                            shuffle=False, num_workers=args.workers)
        for index, (images, targets) in enumerate(loader):
            if time.time() >= soft or (args.out / "STOP").exists():
                raise RuntimeError("Feature extraction stopped before the deadline; no trained head claimed")
            with torch.no_grad():
                features.append(encoder(images.to("cuda")).cpu())
            labels.append(targets)
            if index % 100 == 0:
                print(f"{name}: batch {index + 1}/{len(loader)}", flush=True)
        prepared[name] = (torch.cat(features), torch.cat(labels))
        torch.save(prepared[name], args.out / f"{name}_features.pt")
    del encoder
    torch.cuda.empty_cache()
    torch.manual_seed(20261019)
    head = torch.nn.Linear(prepared["train"][0].shape[1], 49).to("cuda")
    optimizer = torch.optim.AdamW(head.parameters(), lr=0.001)
    generator = torch.Generator().manual_seed(20261019)
    train_loader = torch.utils.data.DataLoader(torch.utils.data.TensorDataset(*prepared["train"]),
        batch_size=256, shuffle=True, generator=generator)
    best = -1.0
    history = []
    best_weights = None
    for epoch in range(1 if args.smoke else args.epochs):
        if time.time() >= soft or (args.out / "STOP").exists():
            break
        for inputs, targets in train_loader:
            optimizer.zero_grad()
            loss = torch.nn.functional.cross_entropy(head(inputs.to("cuda")), targets.to("cuda"))
            if not torch.isfinite(loss):
                raise RuntimeError("Non-finite classifier loss")
            loss.backward()
            optimizer.step()
        with torch.no_grad():
            predicted = head(prepared["val_field"][0].to("cuda")).argmax(dim=1).cpu().numpy()
        evaluated = metrics(prepared["val_field"][1].numpy(), predicted)
        history.append({"epoch": epoch + 1, "loss_last_batch": float(loss.detach().cpu()), **evaluated})
        checkpoint = {"epoch": epoch + 1, "head": {key: value.cpu() for key, value in head.state_dict().items()},
                      "optimizer": optimizer.state_dict(), "pretrained": pretrained.url}
        torch.save(checkpoint, args.out / "last.tmp")
        (args.out / "last.tmp").replace(args.out / "last.pt")
        if evaluated["macro_f1_present_classes"] > best:
            best = evaluated["macro_f1_present_classes"]
            best_weights = {key: value.clone() for key, value in checkpoint["head"].items()}
            torch.save(checkpoint, args.out / "best.pt")
        write_json(args.out / "history.json", history)
        print(f"epoch {epoch + 1}: field crop macro-F1={evaluated['macro_f1_present_classes']:.4f}", flush=True)
    if best_weights is None:
        raise RuntimeError("No head optimization epoch completed")
    head.load_state_dict(best_weights)
    evaluated = {}
    for name in ("val_field", "val_lab"):
        with torch.no_grad():
            predicted = head(prepared[name][0].to("cuda")).argmax(dim=1).cpu().numpy()
        evaluated[name] = metrics(prepared[name][1].numpy(), predicted)
    weights_path = Path(torch.hub.get_dir()) / "checkpoints" / pretrained.url.rsplit("/", 1)[-1]
    write_json(args.out / "report.json", {"model": "Frozen ImageNet MobileNetV3-small + learned linear head",
        "counts": {name: len(items) for name, items in parts.items()}, "epochs": len(history),
        "pretrained": pretrained.url, "pretrained_sha256": hashlib.sha256(weights_path.read_bytes()).hexdigest(),
        "resize": [224, 224], "normalization": "ImageNet RGB mean/std", "seed": 20261019,
        "missing_train_class_ids": sorted(set(range(49)) - set(prepared["train"][1].tolist())),
        "validation": evaluated, "test_evaluated": False, "smoke": args.smoke,
        "limitations": ["Ground-truth leaf crops, not end-to-end recognition or rejection of objects.",
                        "Synthetic/home GPU hardware check is not a phone latency measurement.",
                        "Source data split and license limitations remain; no publication or APK integration.",
                        "Pretrained ImageNet upstream terms must be reviewed before release."]})
