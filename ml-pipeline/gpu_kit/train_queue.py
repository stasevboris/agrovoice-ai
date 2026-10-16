"""Train the allcrops_v1 queue on a rented GPU within a fixed time window.

For every run in queue.json: train (Ultralytics, hard time cap) -> evaluate field/lab val+test ->
export Android ONNX (ordinary head, [1, 4+classes, anchors]) -> desktop parity -> run_summary.json.
Finished runs are skipped, interrupted runs resume from weights/last.pt.
Create a file named STOP in the kit root to stop gracefully after the current epoch.

  python train_queue.py --smoke                     # 2-3 minute end-to-end check, do this first
  python train_queue.py --deadline 18:30            # local wall-clock time when the GPU must be free
  python train_queue.py --budget-hours 4 --only allcrops_v1_yolo26s_416_s20261003
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import sys
import time
import traceback

KIT = Path(__file__).resolve().parent
os.environ.setdefault("YOLO_CONFIG_DIR", str(KIT/"config"))
EXPORT_RESERVE_HOURS = 0.2  # evaluation + ONNX export + parity per run


def log(message):
    print(f"[{datetime.now():%H:%M:%S}] {message}", flush=True)


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix+".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding="utf-8")
    os.replace(temporary, path)


def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def runtime_yaml(dataset: Path, name: str) -> Path:
    """Ultralytics resolves relative 'path' against its datasets_dir, so write an absolute copy."""
    import yaml
    config = yaml.safe_load((dataset/name).read_text(encoding="utf-8"))
    config["path"] = str(dataset.resolve())
    target = KIT/"config"/f"runtime_{name}"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False), encoding="utf-8")
    return target


def recommended():
    path = KIT/"env.json"
    return json.loads(path.read_text(encoding="utf-8")).get("recommended", {}) if path.exists() else {}


def evaluate(best: Path, dataset: Path, imgsz: int, out: Path, classes, device):
    from ultralytics import YOLO
    results = {}
    for domain in ("field", "lab"):
        data = runtime_yaml(dataset, f"data_{domain}.yaml")
        for split in ("val", "test"):
            metrics = YOLO(str(best)).val(data=str(data), split=split, imgsz=imgsz, batch=32, device=device,
                                         project=str(out), name=f"{domain}_{split}", exist_ok=True, plots=True, verbose=False)
            per_class = {}
            for index, value in zip(metrics.box.ap_class_index, metrics.box.maps[metrics.box.ap_class_index]):
                per_class[classes[int(index)]] = round(float(value), 4)
            results[f"{domain}_{split}"] = dict(
                precision=float(metrics.box.mp), recall=float(metrics.box.mr),
                map50=float(metrics.box.map50), map50_95=float(metrics.box.map), per_class_map50_95=per_class)
            log(f"  {domain}/{split}: mAP50={metrics.box.map50:.4f} mAP50-95={metrics.box.map:.4f}")
    write_json(out/"evaluation.json", results)
    return results


def export_android(best: Path, imgsz: int, classes):
    """Export the ordinary multi-class head the Android postprocessor expects, then check parity."""
    import numpy as np
    import onnxruntime as ort
    from ultralytics import YOLO
    model = YOLO(str(best))
    model.model.end2end = False  # nms=None + end2end=False = ordinary head, not the YOLO26 one-to-one TopK head
    onnx_path = Path(model.export(format="onnx", imgsz=imgsz, dynamic=False, opset=17, device="cpu", nms=None))
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    input_shape = session.get_inputs()[0].shape
    output_shape = session.get_outputs()[0].shape
    expected = 4+len(classes)
    if input_shape != [1, 3, imgsz, imgsz] or output_shape[0] != 1 or output_shape[1] != expected:
        raise RuntimeError(f"Unexpected ONNX shapes {input_shape} -> {output_shape}; expected [1,{expected},N]")
    # Parity on random inputs: same graph in PyTorch vs ONNX Runtime.
    import torch
    torch_model = YOLO(str(best)).model.float().eval()
    torch_model.end2end = False
    for head in torch_model.modules():
        if hasattr(head, "end2end"):
            head.end2end = False
    differences = []
    generator = np.random.default_rng(0)
    for _ in range(3):
        sample = generator.random((1, 3, imgsz, imgsz), dtype=np.float32)
        with torch.no_grad():
            reference = torch_model(torch.from_numpy(sample))
        reference = (reference[0] if isinstance(reference, (list, tuple)) else reference).numpy()
        actual = session.run(None, {session.get_inputs()[0].name: sample})[0]
        differences.append(float(np.max(np.abs(reference-actual))) if reference.shape == actual.shape else None)
    return dict(onnx=str(onnx_path), sha256=sha256(onnx_path), bytes=onnx_path.stat().st_size,
                input_shape=input_shape, output_shape=output_shape, parity_max_abs_diff=differences)


def android_entry(run_name: str, model_file: str, seed: int, imgsz: int, export: dict, taxonomy: dict):
    classes = [c["code"] for c in taxonomy["classes"]]
    return dict(id=run_name, name=f"{model_file.removesuffix('.pt').upper()} allcrops v1", asset=f"models/{run_name}.onnx",
                sha256=export["sha256"], family="yolo", side=imgsz, seed=seed, detector_seed=seed,
                dataset="allcrops_v1", provisional=True, classes=classes, labels_ru=[c["ru"] for c in taxonomy["classes"]],
                crops=sorted({c["crop"] for c in taxonomy["classes"]}), crops_ru=taxonomy["crops_ru"])


def resumable(checkpoint: Path) -> bool:
    """Ultralytics strips the optimizer and sets epoch=-1 when training completes; such files must not be resumed."""
    import torch
    state = torch.load(checkpoint, map_location="cpu", weights_only=False)
    return state.get("epoch", -1) >= 0 and state.get("optimizer") is not None


def train_one(run: dict, defaults: dict, dataset: Path, hours: float, smoke: bool, device="0"):
    import torch
    from ultralytics import YOLO, settings
    settings.update({"sync": False, "weights_dir": str(KIT/"weights"), "runs_dir": str(KIT/"runs")})
    taxonomy = json.loads((dataset/"taxonomy_v1.json").read_text(encoding="utf-8"))
    classes = [c["code"] for c in taxonomy["classes"]]
    project = KIT/"runs"
    run_dir = project/run["name"]
    summary_path = run_dir/"run_summary.json"
    if not smoke and summary_path.exists() and json.loads(summary_path.read_text(encoding="utf-8")).get("status") == "done":
        log(f"{run['name']}: already done, skipping")
        return "skipped"
    rec = recommended()
    family = Path(run["model"]).stem
    options = dict(defaults) | {k: v for k, v in run.items() if k not in ("name", "model", "hours", "why", "batch")}
    options["batch"] = run.get("batch") or rec.get("batch", {}).get(family, 32)
    options["workers"] = rec.get("workers", 8)
    options["cache"] = rec.get("cache", False)
    if smoke:
        options |= dict(epochs=1, batch=16, cache=False, save_period=-1, close_mosaic=0, workers=2)
    stopped = {"value": False}

    def check_stop(trainer):
        if (KIT/"STOP").exists() and not stopped["value"]:
            trainer.stop = True
            stopped["value"] = True
            log("STOP file found: finishing this epoch and saving.")

    def time_guard(trainer):
        """Keep the planned epochs if they fit; otherwise hand Ultralytics a time cap (it refits the LR schedule)."""
        done = trainer.epoch-trainer.start_epoch+1
        if trainer.args.time:  # a resumed checkpoint carries the old cap; never exceed the current one
            trainer.args.time = min(trainer.args.time, hours)
            return
        if smoke or done < 1:
            return
        projected = (time.time()-trainer.train_time_start)/done*(trainer.epochs-trainer.start_epoch)
        if projected > hours*3600:
            trainer.args.time = hours
            log(f"{trainer.epochs} epochs would take {projected/3600:.2f} h > cap {hours:.2f} h: switching to the time cap")

    def keep_resumable(trainer):
        """Ultralytics strips last.pt when training ends (also after STOP); keep a resumable copy of every epoch."""
        temporary = checkpoint.with_suffix(".tmp")
        shutil.copyfile(trainer.last, temporary)
        os.replace(temporary, checkpoint)

    def attach(model):
        model.add_callback("on_model_save", keep_resumable)
        model.add_callback("on_fit_epoch_end", check_stop)
        model.add_callback("on_fit_epoch_end", time_guard)
        return model

    checkpoint = run_dir/"weights/last_resumable.pt"
    previous = json.loads(summary_path.read_text(encoding="utf-8")).get("status") if summary_path.exists() else None
    started = time.time()
    meta = dict(name=run["name"], model=run["model"], hours_cap=hours, options=options, started=datetime.now().isoformat(),
                gpu=torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu", torch=torch.__version__, platform=platform.platform(),
                dataset_fingerprint=json.loads((dataset/"report.json").read_text(encoding="utf-8"))["fingerprint"])
    if smoke and run_dir.exists():
        shutil.rmtree(run_dir)
    if not smoke and previous == "trained" and (run_dir/"weights/best.pt").exists():
        log(f"{run['name']}: training had already finished; continuing with evaluation/export")
    elif not smoke and checkpoint.exists() and resumable(checkpoint):
        write_json(summary_path, meta | dict(status="training"))
        log(f"{run['name']}: resuming from {checkpoint}")
        try:
            attach(YOLO(str(checkpoint))).train(resume=True)
        except AssertionError as error:
            if "nothing to resume" not in str(error):
                raise
            log(f"{run['name']}: training had already finished; continuing with evaluation/export")
    else:
        if run_dir.exists() and not smoke:
            shutil.move(str(run_dir), str(run_dir.with_name(f"{run_dir.name}_abandoned_{datetime.now():%H%M%S}")))
        write_json(summary_path, meta | dict(status="training"))
        train_args = dict(data=str(runtime_yaml(dataset, "data.yaml")), project=str(project), name=run["name"],
                          exist_ok=True, device=device, plots=True, **options)
        log(f"{run['name']}: training, cap {hours:.2f} h, batch {options['batch']}, cache {options['cache']}")
        attach(YOLO(str(KIT/"weights"/run["model"]))).train(**train_args)
    best = run_dir/"weights/best.pt"
    if not best.exists():
        raise RuntimeError("best.pt was not produced")
    meta["train_minutes"] = round((time.time()-started)/60, 1)
    if stopped["value"]:
        write_json(summary_path, meta | dict(status="stopped"))
        return "stopped"
    write_json(summary_path, meta | dict(status="trained"))
    log(f"{run['name']}: evaluating field/lab val+test")
    evaluation = evaluate(best, dataset, options["imgsz"], run_dir/"eval", classes, device)
    log(f"{run['name']}: exporting Android ONNX")
    export = export_android(best, options["imgsz"], classes)
    write_json(run_dir/"android_manifest_entry.json", android_entry(run["name"], run["model"], options.get("seed", 0), options["imgsz"], export, taxonomy))
    write_json(summary_path, meta | dict(status="done", ended=datetime.now().isoformat(), evaluation=evaluation, export=export))
    log(f"{run['name']}: done in {(time.time()-started)/60:.1f} min")
    return "done"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--queue", type=Path, default=KIT/"queue.json")
    parser.add_argument("--smoke", action="store_true", help="1 epoch of YOLO26n on the tiny smoke dataset, full pipeline")
    parser.add_argument("--device", default="0", help="CUDA device index, or cpu (only for testing the kit)")
    parser.add_argument("--deadline", help="HH:MM local time when the GPU must be released")
    parser.add_argument("--budget-hours", type=float, help="Alternative to --deadline")
    parser.add_argument("--only", nargs="*", help="Run names to execute")
    args = parser.parse_args()
    if (KIT/"STOP").exists():
        parser.error("Remove the STOP file first.")
    queue = json.loads(args.queue.read_text(encoding="utf-8"))
    dataset = (KIT/queue["dataset"]).resolve()
    if args.smoke:
        run = dict(name="smoke_yolo26n", model="yolo26n.pt", seed=1)
        print(train_one(run, queue["defaults"], (KIT/queue["smoke_dataset"]).resolve(), 0.1, smoke=True, device=args.device))
        log("Smoke OK. Check runs/smoke_yolo26n/run_summary.json, then start the real queue.")
        return
    if args.deadline:
        hour, minute = map(int, args.deadline.split(":"))
        deadline = datetime.now().replace(hour=hour, minute=minute, second=0, microsecond=0)
        if deadline <= datetime.now():
            deadline += timedelta(days=1)
    elif args.budget_hours:
        deadline = datetime.now()+timedelta(hours=args.budget_hours)
    else:
        parser.error("Give --deadline HH:MM or --budget-hours N so the queue never overruns the rented time.")
    log(f"Deadline {deadline:%Y-%m-%d %H:%M}")
    for run in queue["runs"]:
        if args.only and run["name"] not in args.only:
            continue
        remaining = (deadline-datetime.now()).total_seconds()/3600-EXPORT_RESERVE_HOURS
        if remaining < 0.3:
            log(f"Not enough time left for {run['name']} ({remaining:.2f} h). Queue finished.")
            break
        hours = min(run["hours"], remaining)
        try:
            status = train_one(run, queue["defaults"], dataset, hours, smoke=False, device=args.device)
        except Exception:
            traceback.print_exc()
            log(f"{run['name']} FAILED; continuing with the next run")
            continue
        if status == "stopped":
            log("Stopped by STOP file.")
            break
    log("Queue finished. Now run: python package_results.py")


if __name__ == "__main__":
    main()
