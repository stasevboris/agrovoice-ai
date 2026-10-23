# Installs packages only inside the kit venv; no drivers or system packages.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
nvidia-smi
if ($LASTEXITCODE -ne 0) { throw 'NVIDIA driver/GPU unavailable' }
$python = if (Get-Command py -ErrorAction SilentlyContinue) { 'py' } else { 'python' }
& $python -c "import sys; assert (3,11) <= sys.version_info[:2] < (3,14), 'Use Python 3.11-3.13'"
if ($LASTEXITCODE -ne 0) { throw 'Python 3.11-3.13 is required' }
if (-not (Test-Path .venv)) {
    & $python -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'venv creation failed' }
}
$venvPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
& $venvPython -m pip install torch==2.9.1 torchvision==0.24.1 --index-url https://download.pytorch.org/whl/cu128
if ($LASTEXITCODE -ne 0) { throw 'CUDA PyTorch installation failed' }
& $venvPython -m pip install -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
& $venvPython check_environment.py --device cuda --report environment.json
if ($LASTEXITCODE -ne 0) { throw 'Feature inference check failed' }
