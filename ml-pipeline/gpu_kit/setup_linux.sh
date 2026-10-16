#!/usr/bin/env bash
# Prepare a Python environment for RTX 5090 (Blackwell, sm_120) on Linux. Run from the kit root:
#   bash setup_linux.sh
# Reuses an existing CUDA PyTorch (rental images often have one) when it already supports the GPU.
set -euo pipefail
cd "$(dirname "$0")"

PY=${PYTHON:-python3}
torch_ok() {
  "$1" - <<'EOF' 2>/dev/null
import torch, sys
ok = torch.cuda.is_available()
if ok:
    p = torch.cuda.get_device_properties(0)
    ok = f"sm_{p.major}{p.minor}" in torch.cuda.get_arch_list()
sys.exit(0 if ok else 1)
EOF
}

if torch_ok "$PY"; then
  echo "System PyTorch already supports this GPU; creating venv with system site-packages."
  "$PY" -m venv --system-site-packages .venv
  .venv/bin/python -m pip install -U pip
else
  "$PY" -m venv .venv || { echo "python3-venv missing: apt-get install -y python3-venv (or set PYTHON=...)"; exit 1; }
  .venv/bin/python -m pip install -U pip
  # cu128 wheels contain sm_120 kernels; fall back to cu130 if the index has no wheel for this Python.
  .venv/bin/python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu128 \
    || .venv/bin/python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu130
fi

.venv/bin/python -m pip install -r requirements-gpu.txt
# Servers without libGL cannot import opencv-python; the headless build is equivalent for training.
if ! .venv/bin/python -c "import cv2" 2>/dev/null; then
  .venv/bin/python -m pip uninstall -y opencv-python || true
  .venv/bin/python -m pip install opencv-python-headless
fi
mkdir -p logs
.venv/bin/python check_env.py
