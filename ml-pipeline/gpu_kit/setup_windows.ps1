# Prepare a Python environment for RTX 5090 on Windows. Run from the kit root in PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\setup_windows.ps1
# Needs Python 3.10-3.13 (py launcher) and a recent NVIDIA driver (nvidia-smi must work).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

nvidia-smi | Out-Host
$py = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
if (-not (Test-Path .venv)) { & $py -m venv .venv }
$venv = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
& $venv -m pip install -U pip
# cu128 wheels contain sm_120 kernels for RTX 50xx; fall back to cu130.
& $venv -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu128
if ($LASTEXITCODE -ne 0) { & $venv -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu130 }
& $venv -m pip install -r requirements-gpu.txt
New-Item -ItemType Directory -Force logs | Out-Null
& $venv check_env.py
