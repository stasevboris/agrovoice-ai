#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
nvidia-smi
python3 -c "import sys; assert (3,11) <= sys.version_info[:2] < (3,14), 'Use Python 3.11-3.13'"
python3 -m venv .venv
.venv/bin/python -m pip install torch==2.9.1 torchvision==0.24.1 --index-url https://download.pytorch.org/whl/cu128
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python check_environment.py --device cuda --report environment.json
