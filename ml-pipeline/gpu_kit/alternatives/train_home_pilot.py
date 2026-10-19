"""Run a bounded ROCm pilot, with a separate watchdog and an explicit same-day deadline.

The supervisor owns the worker process group. A completed epoch is checkpointed;
no process is automatically restarted after the deadline or on the following day.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

MSK = timezone(timedelta(hours=3))


def deadlines(value, now=None):
    end = datetime.fromisoformat(value)
    now = now or datetime.now(MSK)
    if end.tzinfo is None or end.utcoffset() != timedelta(hours=3):
        raise ValueError("Deadline must explicitly use Moscow time (+03:00)")
    if end.date() != now.astimezone(MSK).date() or end.hour != 23 or end.minute != 0:
        raise ValueError("Only 23:00 MSK on the authorized day is allowed")
    soft = end - timedelta(minutes=30)
    hard = end - timedelta(minutes=5)
    if now >= soft:
        raise ValueError("Not enough time left for a new run and checkpoint reserve")
    return soft.timestamp(), hard.timestamp()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def supervise(args):
    soft, hard = deadlines(args.deadline)
    args.out.mkdir(parents=True, exist_ok=False)
    command = [sys.executable, "-u", str(Path(__file__).resolve()), *sys.argv[1:], "--worker"]
    status = {"deadline": args.deadline, "soft_stop_epoch_boundary": soft,
              "hard_stop_process_group": hard, "automatic_resume": False, "status": "running"}
    if args.after is not None:
        status["status"] = "waiting_for_previous_run"
        write_json(args.out / "status.json", status)
        while time.time() < soft:
            previous = args.after / "status.json"
            if previous.is_file() and json.loads(previous.read_text(encoding="utf-8")).get("status") in {"finished", "interrupted_or_failed"}:
                break
            time.sleep(10)
        else:
            status["status"] = "skipped_deadline"
            write_json(args.out / "status.json", status)
            return 0
    status["status"] = "running"
    write_json(args.out / "status.json", status)
    with (args.out / "training.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            returncode = process.wait(timeout=max(1, hard - time.time()))
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            os.killpg(process.pid, signal.SIGINT)
            try:
                returncode = process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                returncode = process.wait()
        finally:
            # A worker exit must not leave loader/validation processes behind.
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
    status.update(status="finished" if returncode == 0 else "interrupted_or_failed", returncode=returncode)
    write_json(args.out / "status.json", status)
    return returncode


def train(args):
    soft, _ = deadlines(args.deadline)
    if args.weights is None:
        raise ValueError("Detector pilot requires --weights")
    os.environ.setdefault("OMP_NUM_THREADS", "4")
    os.environ.setdefault("MKL_NUM_THREADS", "4")
    os.environ.setdefault("YOLO_CONFIG_DIR", str(args.out / "ultralytics"))
    Path(os.environ["YOLO_CONFIG_DIR"]).mkdir(parents=True, exist_ok=True)
    import torch
    import ultralytics
    from ultralytics import YOLO
    import configure_yolo_pilot

    torch.set_num_threads(4)
    if not torch.cuda.is_available() or not torch.version.hip:
        raise RuntimeError("A working ROCm accelerator is required; no silent CPU fallback")
    config = args.out / "config"
    print("Preparing train and field-validation lists", flush=True)
    configured = configure_yolo_pilot.configure(args.data, config)
    environment = {"torch": torch.__version__, "hip": torch.version.hip,
                   "ultralytics": ultralytics.__version__, "device": torch.cuda.get_device_name(0),
                   "data": configured, "amp": False, "batch": args.batch, "seed": 20261019}
    write_json(args.out / "environment.json", environment)
    summaries = []
    variants = ["train"] if args.smoke else ["train", "train_with_negatives"]
    for variant in variants:
        if time.time() >= soft or (args.out / "STOP").exists():
            break
        model = YOLO(str(args.weights))
        started = time.monotonic()

        def stop_at_boundary(trainer):
            if time.time() >= soft or (args.out / "STOP").exists():
                trainer.stop = True

        model.add_callback("on_train_batch_end", stop_at_boundary)
        model.add_callback("on_train_epoch_end", stop_at_boundary)
        metrics = model.train(
            data=str(config / f"{variant}.yaml"), imgsz=416,
            epochs=1 if args.smoke else args.epochs, patience=5,
            fraction=0.003 if args.smoke else 1.0, batch=args.batch,
            device=0, workers=2, amp=False, cache=False,
            optimizer="AdamW", lr0=0.001, cos_lr=True, seed=20261019,
            deterministic=True, project=str(args.out), name=variant,
            save=True, save_period=1, plots=False, verbose=False,
        )
        summary = {"variant": variant, "seconds": time.monotonic() - started,
                   "metrics": metrics.results_dict if metrics is not None else None,
                   "best": str(model.trainer.best), "last": str(model.trainer.last),
                   "test_evaluated": False}
        summaries.append(summary)
        write_json(args.out / "report.json", {
            "smoke": args.smoke, "runs": summaries, "deadline": args.deadline,
            "limitations": ["Strict dataset has missing train classes; not a full taxonomy release.",
                            "Only field validation; no test-based tuning or model release.",
                            "Negatives are a small assistant-screened pilot; no guarantee of unknown rejection."]})
        del model
        torch.cuda.empty_cache()
    if not summaries:
        raise RuntimeError("No run fitted inside the authorized window")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deadline", required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--weights", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--task", choices=("detect", "crop-probe", "evaluate-detectors", "assistant-sft"), default="detect")
    parser.add_argument("--model", type=Path, help="Local pretrained language-model snapshot for assistant-sft")
    parser.add_argument("--after", type=Path, help="Wait for another bounded run before using the GPU")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        if args.task == "crop-probe":
            from train_crop_probe import train_probe
            train_probe(args)
        elif args.task == "evaluate-detectors":
            from evaluate_home_pilot import evaluate
            evaluate(args)
        elif args.task == "assistant-sft":
            sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "assistant"))
            from train_assistant_sft import train_sft
            train_sft(args)
        else:
            train(args)
    else:
        sys.exit(supervise(args))


if __name__ == "__main__":
    main()
