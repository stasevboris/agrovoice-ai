param(
    [ValidateSet('cpu','wsl')][string]$Backend = 'cpu',
    [int]$Epochs = 60,
    [int]$ImageSize = 416,
    [int]$Batch = 16,
    [string]$Name = 'plantdoc_clean_v1_yolo26n_416',
    [string]$Model = '',
    [string]$Data = '',
    [string]$Optimizer = 'AdamW',
    [double]$LearningRate = 0.001,
    [int]$Seed = 20261003,
    [int]$Patience = 20,
    [string]$Resume = '',
    [switch]$NoExport,
    [switch]$Test
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'TrainingProcess.ps1')
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskLogs = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskActive = Join-Path $taskLogs 'active_training.json'
if (Test-Path -LiteralPath $taskActive) {
    $taskPrevious = Get-Content -LiteralPath $taskActive -Raw | ConvertFrom-Json
    $taskRunning = Get-TrainingProcess $taskPrevious
    if ($taskRunning) { throw "Training is already running (PID $($taskPrevious.pid)). Stop it at an epoch boundary first." }
}
function Convert-ToWslPath([string]$Value) {
    $taskAbsolute = [IO.Path]::GetFullPath($Value)
    $taskDrive = $taskAbsolute.Substring(0,1).ToLowerInvariant()
    return '/mnt/' + $taskDrive + '/' + $taskAbsolute.Substring(3).Replace('\','/')
}
$taskStamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd_HHmmss_fff')
$taskStdout = Join-Path $taskLogs ($taskStamp + '.stdout.log')
$taskStderr = Join-Path $taskLogs ($taskStamp + '.stderr.log')
$taskStop = Join-Path $taskLogs ($taskStamp + '.stop')
$taskArguments = @('--epochs',"$Epochs",'--imgsz',"$ImageSize",'--batch',"$Batch",'--name',$Name,
                   '--optimizer',$Optimizer,'--lr0',$LearningRate.ToString([Globalization.CultureInfo]::InvariantCulture),'--seed',"$Seed",'--patience',"$Patience")
if ($NoExport) { $taskArguments += '--no-export' }
if ($Test) { $taskArguments += '--test' }
if ($Backend -eq 'wsl') {
    $taskExe = (Get-Command wsl.exe).Source
    $taskArguments += @('--device','0','--stop-file',(Convert-ToWslPath $taskStop))
    if ($Resume) { $taskArguments += @('--resume',(Convert-ToWslPath $Resume)) }
    if ($Model) { $taskArguments += @('--model',(Convert-ToWslPath $Model)) }
    if ($Data) { $taskArguments += @('--data',(Convert-ToWslPath $Data)) }
    $taskArguments = @('--distribution','AgroVoice-ML','--user','root','--exec','bash',(Convert-ToWslPath (Join-Path $PSScriptRoot 'run_wsl_training.sh'))) + $taskArguments
} else {
    $taskExe = Join-Path $PSScriptRoot 'venv\Scripts\python.exe'
    $taskArguments += @('--device','cpu','--workers','0','--stop-file',$taskStop)
    if ($Resume) { $taskArguments += @('--resume',([IO.Path]::GetFullPath($Resume))) }
    if ($Model) { $taskArguments += @('--model',([IO.Path]::GetFullPath($Model))) }
    if ($Data) { $taskArguments += @('--data',([IO.Path]::GetFullPath($Data))) }
    $taskArguments = @('-X','utf8','-u',(Join-Path $PSScriptRoot 'train_clean.py')) + $taskArguments
}
$taskQuoted = ($taskArguments | ForEach-Object {
    if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
}) -join ' '
$taskProcess = Start-Process -FilePath $taskExe -ArgumentList $taskQuoted -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput $taskStdout -RedirectStandardError $taskStderr -PassThru
$taskState = [ordered]@{pid=$taskProcess.Id;backend=$Backend;started_utc=[DateTimeOffset]::UtcNow.ToString('o');epochs=$Epochs;image_size=$ImageSize;name=$Name;stdout=$taskStdout;stderr=$taskStderr;stop_file=$taskStop}
$taskState | ConvertTo-Json | Set-Content -LiteralPath $taskActive -Encoding utf8
$taskState | ConvertTo-Json
