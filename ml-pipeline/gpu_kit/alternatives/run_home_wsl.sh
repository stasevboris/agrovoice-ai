#!/usr/bin/env bash
# Start train_home_pilot.py in WSL with the ROCm environment of the home PC.
# The RX 9060 XT is reached through /dev/dxg (librocdxg); without these
# variables PyTorch either misses libroctx64 or reports no GPU.
# Usage: run_home_wsl.sh --deadline 2026-10-20T23:00:00+03:00 --data ... --out ...
set -euo pipefail
export ROCM_PATH=/opt/rocm-7.2.1
export LD_LIBRARY_PATH=/opt/rocm-7.2.1/lib:/opt/rocm/lib
export HSA_ENABLE_DXG_DETECTION=1
here="$(cd "$(dirname "$0")" && pwd)"
exec /opt/agrovoice/venv/bin/python -u "$here/train_home_pilot.py" "$@"
