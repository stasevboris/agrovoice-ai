#!/usr/bin/env bash
# Run only inside the dedicated AgroVoice-ML Ubuntu distribution.
set -euo pipefail
TASK_ROOT=/mnt/f/agrovoice-ai
mkdir -p /opt/agrovoice
apt-get update
apt-get install -y python3-venv python3-dev libgl1 libglib2.0-0 libgomp1 wget ca-certificates
# Register AMD's signed repositories, then install only compute runtime/libraries.
# Do not install a Linux kernel display driver in WSL.
wget -q -O /opt/agrovoice/amdgpu-install.deb \
  https://repo.radeon.com/amdgpu-install/7.2.1/ubuntu/noble/amdgpu-install_7.2.1.70201-1_all.deb
apt-get install -y /opt/agrovoice/amdgpu-install.deb
apt-get update
apt-get install -y --no-install-recommends rocm-hip-runtime miopen-hip hipblas hipfft \
  hiprand hipsparse hipsolver hipsparselt hipblaslt rccl roctracer rocminfo
python3 -m venv /opt/agrovoice/venv
PY=/opt/agrovoice/venv/bin/python
"$PY" -m pip install --upgrade pip wheel
wget -q -O /opt/agrovoice/rocdxg-roct_1.2.2_amd64.deb \
  https://github.com/ROCm/librocdxg/releases/download/v1.2.2/rocdxg-roct_1.2.2_amd64.deb
dpkg -i /opt/agrovoice/rocdxg-roct_1.2.2_amd64.deb
"$PY" -m pip install --no-cache-dir \
  'https://repo.radeon.com/rocm/manylinux/rocm-rel-7.2.1/torch-2.9.1%2Brocm7.2.1.lw.gitff65f5bc-cp312-cp312-linux_x86_64.whl' \
  'https://repo.radeon.com/rocm/manylinux/rocm-rel-7.2.1/torchvision-0.24.0%2Brocm7.2.1.gitb919bd0c-cp312-cp312-linux_x86_64.whl' \
  'https://repo.radeon.com/rocm/manylinux/rocm-rel-7.2.1/triton-3.5.1%2Brocm7.2.1.gita272dfa8-cp312-cp312-linux_x86_64.whl'
"$PY" -m pip install 'numpy==1.26.4' 'opencv-python==4.11.0.86' \
  'ultralytics==8.4.164' 'onnx==1.20.1' onnxslim
"$PY" -m pip freeze > "$TASK_ROOT/ml-pipeline/requirements-wsl.lock.txt"
export HSA_ENABLE_DXG_DETECTION=1
export ROCM_PATH=/opt/rocm-7.2.1
export HIP_PATH=$ROCM_PATH
export PATH="$ROCM_PATH/bin:$PATH"
export LD_LIBRARY_PATH=$ROCM_PATH/lib:/opt/rocm/lib:/usr/lib/wsl/lib:/usr/local/lib
"$PY" "$TASK_ROOT/ml-pipeline/check_gpu.py"
