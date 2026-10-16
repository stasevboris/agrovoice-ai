#!/usr/bin/env bash
set -euo pipefail
export HSA_ENABLE_DXG_DETECTION=1
export ROCM_PATH=/opt/rocm-7.2.1
export HIP_PATH=$ROCM_PATH
export PATH="$ROCM_PATH/bin:$PATH"
export LD_LIBRARY_PATH=$ROCM_PATH/lib:/opt/rocm/lib:/usr/lib/wsl/lib:/usr/local/lib
export PYTHONUTF8=1
exec /opt/agrovoice/venv/bin/python -u /mnt/f/agrovoice-ai/ml-pipeline/train_clean.py "$@"
