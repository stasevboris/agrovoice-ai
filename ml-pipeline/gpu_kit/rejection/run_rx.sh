#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export ROCM_PATH=/opt/rocm-7.2.1
export LD_LIBRARY_PATH=/opt/rocm-7.2.1/lib:/opt/rocm/lib
export HSA_ENABLE_DXG_DETECTION=1
export OMP_NUM_THREADS=4
export MKL_NUM_THREADS=4
if [ "${1:-}" = "check" ]; then
    shift
    exec /opt/agrovoice/venv/bin/python -u check_environment.py --device cuda "$@"
fi
exec /opt/agrovoice/venv/bin/python -u train.py --device cuda "$@"
