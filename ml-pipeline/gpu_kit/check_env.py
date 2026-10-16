"""Check the GPU machine before spending the rented hours; writes env.json with recommended settings.

Run from the kit root:  python check_env.py
Exit code 0 = ready, 1 = something must be fixed (message printed).
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import platform
import shutil
import sys
import time

KIT = Path(__file__).resolve().parent
os.environ.setdefault("YOLO_CONFIG_DIR", str(KIT/"config"))


def total_ram_gb():
    try:
        import psutil
        memory = psutil.virtual_memory()
        return memory.total/2**30, memory.available/2**30
    except ImportError:
        return None, None


def main():
    problems, info = [], dict(python=sys.version.split()[0], platform=platform.platform(), cpu_count=os.cpu_count())
    info["disk_free_gb"] = round(shutil.disk_usage(KIT).free/2**30, 1)
    info["ram_total_gb"], info["ram_available_gb"] = (round(v, 1) if v else None for v in total_ram_gb())
    try:
        import torch
    except ImportError:
        print("PyTorch is not installed. Run setup_linux.sh / setup_windows.ps1 first.")
        return 1
    info.update(torch=torch.__version__, cuda_build=torch.version.cuda, cuda_available=torch.cuda.is_available())
    if not torch.cuda.is_available():
        problems.append("CUDA is not available: install the CUDA build of PyTorch (see README).")
    else:
        properties = torch.cuda.get_device_properties(0)
        capability = f"sm_{properties.major}{properties.minor}"
        info.update(gpu=properties.name, vram_gb=round(properties.total_memory/2**30, 1), capability=capability,
                    arch_list=torch.cuda.get_arch_list())
        if capability not in torch.cuda.get_arch_list() and f"compute_{properties.major}{properties.minor}" not in " ".join(torch.cuda.get_arch_list()):
            problems.append(f"This PyTorch build has no kernels for {capability} (RTX 50xx needs a cu128+ wheel).")
        else:
            x = torch.randn(4096, 4096, device="cuda")
            torch.cuda.synchronize(); started = time.perf_counter()
            for _ in range(20):
                y = x @ x
            torch.cuda.synchronize()
            info["matmul_tflops_fp32"] = round(20*2*4096**3/(time.perf_counter()-started)/1e12, 1)
            del x, y
    try:
        import ultralytics
        info["ultralytics"] = ultralytics.__version__
        if ultralytics.__version__ != "8.4.164":
            print(f"Warning: ultralytics {ultralytics.__version__}; the project was validated with 8.4.164.")
    except ImportError:
        problems.append("ultralytics is not installed.")
    for package in ("onnx", "onnxruntime", "yaml", "PIL", "cv2"):
        try:
            __import__(package)
        except Exception as error:
            problems.append(f"Cannot import {package}: {error}")

    dataset = KIT/"datasets/allcrops_v1"
    report = dataset/"report.json"
    if not report.exists():
        problems.append(f"Dataset missing: {dataset}")
    else:
        splits = json.loads(report.read_text(encoding="utf-8"))["splits"]
        info["dataset_images"] = {k: v["images"] for k, v in splits.items()}
        train = splits["train"]["images"]
        cache_gb = train*416*416*3/2**30  # Ultralytics RAM cache stores resized uint8 images
        info["ram_cache_needed_gb"] = round(cache_gb, 1)
    for weight in ("yolo26n.pt", "yolo26s.pt", "yolo26m.pt"):
        if not (KIT/"weights"/weight).exists():
            problems.append(f"Missing weights/{weight}")

    vram = info.get("vram_gb") or 0
    workers = max(2, min(16, (os.cpu_count() or 4)-2))
    available = info.get("ram_available_gb") or 0
    info["recommended"] = dict(
        batch={"yolo26n": 128 if vram >= 30 else 64, "yolo26s": 64 if vram >= 30 else 32, "yolo26m": 32 if vram >= 30 else 16},
        workers=workers,
        cache="ram" if available and available > 1.6*info.get("ram_cache_needed_gb", 999) else False)
    (KIT/"env.json").write_text(json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(info, indent=2, ensure_ascii=False))
    if problems:
        print("\nNOT READY:\n- "+"\n- ".join(problems))
        return 1
    print("\nREADY. Next: python train_queue.py --smoke")
    return 0


if __name__ == "__main__":
    sys.exit(main())
