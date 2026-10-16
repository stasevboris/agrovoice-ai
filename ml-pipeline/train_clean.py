"""Train clean PlantDoc with atomic resumable checkpoints and explicit metrics."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault("YOLO_CONFIG_DIR", str(ROOT/"ml-pipeline/.cache/ultralytics"/platform.system().lower()))
(Path(os.environ["YOLO_CONFIG_DIR"])/"Ultralytics").mkdir(parents=True, exist_ok=True)


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix+".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=ROOT/"ml-pipeline/dataset_clean_v1/data.yaml")
    parser.add_argument("--project", type=Path, default=ROOT/"runs/detect")
    parser.add_argument("--name", default="plantdoc_clean_v1_yolo26n_416")
    parser.add_argument("--model", type=Path, default=ROOT/"ml-pipeline/yolo26n.pt")
    parser.add_argument("--epochs", type=int, default=60)
    parser.add_argument("--imgsz", type=int, default=416)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--optimizer", default="AdamW")
    parser.add_argument("--lr0", type=float, default=0.001)
    parser.add_argument("--seed", type=int, default=20261003)
    parser.add_argument("--patience", type=int, default=20)
    parser.add_argument("--amp", action="store_true", help="Opt into AMP after verifying numeric stability")
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--stop-file", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--no-export", action="store_true")
    parser.add_argument("--export-tflite", action="store_true")
    parser.add_argument("--test", action="store_true", help="Evaluate held-out TEST once after training")
    args = parser.parse_args()
    import torch
    import yaml
    from ultralytics import YOLO, settings
    from ultralytics.data.utils import check_det_dataset
    torch.set_num_threads(args.threads)
    settings.update({"sync": False, "weights_dir": str(ROOT/"ml-pipeline/.cache/weights"),
                     "datasets_dir": str(ROOT/"ml-pipeline"), "runs_dir": str(ROOT/"runs")})
    report_path = args.data.resolve().parent/"report.json"
    if not report_path.exists():
        parser.error("Clean dataset report required. Run prepare_plantdoc_clean.py first.")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    config = yaml.safe_load(args.data.read_text(encoding="utf-8"))
    config["path"] = str(args.data.resolve().parent)
    cache = ROOT/"ml-pipeline/.cache"
    cache.mkdir(parents=True, exist_ok=True)
    runtime_yaml = cache/f"data_{report['fingerprint'][:16]}_{platform.system().lower()}.yaml"
    runtime_yaml.write_text(yaml.safe_dump(config, allow_unicode=True), encoding="utf-8")
    check_det_dataset(runtime_yaml, autodownload=False)
    device = ("0" if torch.cuda.is_available() else "cpu") if args.device == "auto" else args.device
    environment = dict(python=sys.version, platform=platform.platform(), torch=torch.__version__,
                       hip=torch.version.hip, cuda=torch.version.cuda,
                       gpu=torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
                       ultralytics=importlib.metadata.version("ultralytics"))
    print(json.dumps(dict(environment=environment, device=device, dataset=report["splits"]), indent=2), flush=True)
    if args.check:
        return
    if args.resume:
        path = args.resume.resolve().parent.parent/"run_manifest.json"
        if not path.exists() or json.loads(path.read_text(encoding="utf-8"))["dataset_fingerprint"] != report["fingerprint"]:
            parser.error("Checkpoint dataset is unknown or differs. Start a fresh experiment.")
    if args.stop_file and args.stop_file.exists():
        parser.error("Stop file already exists; use a new path or remove it deliberately.")
    if not args.resume and (args.project/args.name).exists():
        parser.error('Run directory already exists. Resume explicitly or choose a fresh name.')
    initial = args.resume or args.model
    if not initial.exists():
        parser.error(f"Weights do not exist: {initial}")
    model = YOLO(str(initial))
    user_stopped = False

    def started(trainer):
        previous_path = trainer.save_dir/"run_manifest.json"
        previous = json.loads(previous_path.read_text(encoding="utf-8")) if previous_path.exists() else {}
        attempts = previous.get("attempts", [])
        attempts.append(dict(started_utc=datetime.now(timezone.utc).isoformat(),
                             resumed_from=str(args.resume) if args.resume else None,
                             previous_status=previous.get("status")))
        write_json(previous_path, dict(
            started_utc=datetime.now(timezone.utc).isoformat(), dataset_fingerprint=report["fingerprint"],
            dataset_name=report["dataset"], dataset_splits=report["splits"], environment=environment,
            resumed_from=str(args.resume) if args.resume else None, status="training",
            requested_epochs=trainer.epochs, image_size=trainer.args.imgsz,
            annotation_target=report["annotation_target"], stop_file=str(args.stop_file) if args.stop_file else None,
            attempts=attempts, amp_effective=bool(trainer.amp),
            model_source=str(initial), model_architecture=trainer.model.yaml.get("yaml_file"),
            parameters=sum(p.numel() for p in trainer.model.parameters()),
            seed=int(trainer.args.seed), batch=trainer.args.batch,
            patience=trainer.args.patience,
            optimizer=trainer.args.optimizer, lr0=trainer.args.lr0,
            data_directory=str(args.data.resolve().parent)))

    def saved(trainer):
        target = trainer.save_dir/"weights/last_complete.pt"
        temporary = target.with_suffix(".tmp")
        shutil.copyfile(trainer.last, temporary)
        os.replace(temporary, target)
        write_json(trainer.save_dir/"progress.json", dict(
            updated_utc=datetime.now(timezone.utc).isoformat(), epoch=trainer.epoch+1,
            planned_epochs=trainer.epochs, metrics={k: float(v) for k, v in trainer.metrics.items()},
            resumable_checkpoint=str(target)))

    def check_stop(trainer):
        nonlocal user_stopped
        if args.stop_file and args.stop_file.exists():
            trainer.stop = True
            user_stopped = True
            print("Stop requested. Current epoch and checkpoint are saved.", flush=True)

    model.add_callback("on_train_start", started)
    model.add_callback("on_model_save", saved)
    model.add_callback("on_fit_epoch_end", check_stop)
    try:
        if args.resume:
            model.train(resume=True, device=device, workers=args.workers)
        else:
            model.train(data=str(runtime_yaml), epochs=args.epochs, imgsz=args.imgsz, batch=args.batch,
                        project=str(args.project), name=args.name, device=device, workers=args.workers,
                        optimizer=args.optimizer, lr0=args.lr0, cos_lr=True, patience=args.patience, seed=args.seed,
                        hsv_h=0.015, hsv_s=0.7, hsv_v=0.4, degrees=10.0, fliplr=0.5,
                        save=True, save_period=5, plots=True, amp=args.amp and torch.cuda.is_available(),
                        deterministic=True, cache=False)
        run_dir = Path(model.trainer.save_dir)
        path = run_dir/"run_manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest.update(status="stopped" if user_stopped else "trained", ended_utc=datetime.now(timezone.utc).isoformat())
        write_json(path, manifest)
        best = YOLO(str(run_dir/"weights/best.pt"))
        export_size = int(manifest['image_size'])
        if args.test and not user_stopped:
            metrics = best.val(data=str(runtime_yaml), split="test", device=device, project=str(run_dir),
                               name="held_out_test", imgsz=export_size)
            write_json(run_dir/"test_metrics.json", dict(metrics=metrics.results_dict,
                       absent_classes=report["splits"]["test"]["absent_classes"]))
        if not args.no_export and not user_stopped:
            onnx_path = best.export(format="onnx", imgsz=export_size, dynamic=False, opset=17, device="cpu")
            print(f"ONNX exported: {onnx_path}", flush=True)
            if args.export_tflite:
                converter = shutil.which("onnx2tf")
                if not converter:
                    raise RuntimeError("onnx2tf is not installed in this environment")
                output = run_dir/"weights/tflite_model"
                subprocess.run([converter, "-i", str(onnx_path), "-o", str(output)], check=True)
                if not list(output.glob("*.tflite")):
                    raise RuntimeError("No TFLite output exists")
        print(f"Run saved: {run_dir}", flush=True)
    except BaseException as exc:
        trainer = getattr(model, "trainer", None)
        if trainer and (Path(trainer.save_dir)/"run_manifest.json").exists():
            path = Path(trainer.save_dir)/"run_manifest.json"
            manifest = json.loads(path.read_text(encoding="utf-8"))
            manifest.update(status="interrupted" if isinstance(exc, KeyboardInterrupt) else "failed",
                            error=str(exc), ended_utc=datetime.now(timezone.utc).isoformat())
            write_json(path, manifest)
        raise


if __name__ == "__main__":
    main()
