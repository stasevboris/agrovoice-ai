$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'TrainingProcess.ps1')
$taskActive = Join-Path $PSScriptRoot 'logs\active_training.json'
if (!(Test-Path -LiteralPath $taskActive)) { Write-Output 'No training has been launched with Start-Training.ps1'; return }
$taskState = Get-Content -LiteralPath $taskActive -Raw | ConvertFrom-Json
$taskProcess = Get-TrainingProcess $taskState
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskRuns = Join-Path $taskRoot 'runs\detect'
$taskCandidates = Get-ChildItem -LiteralPath $taskRuns -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq $taskState.name -or $_.Name -match ('^' + [regex]::Escape($taskState.name) + '\d+$') }
$taskRun = $taskCandidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$taskResult = [ordered]@{running=[bool]$taskProcess;pid=$taskState.pid;backend=$taskState.backend;started_utc=$taskState.started_utc;run_directory=$taskRun.FullName;stdout=$taskState.stdout;stderr=$taskState.stderr}
if ($taskRun) {
    $taskProgress = Join-Path $taskRun.FullName 'progress.json'
    $taskManifest = Join-Path $taskRun.FullName 'run_manifest.json'
    if (Test-Path -LiteralPath $taskProgress) { $taskResult['progress'] = Get-Content -LiteralPath $taskProgress -Raw | ConvertFrom-Json }
    if (Test-Path -LiteralPath $taskManifest) { $taskResult['manifest_status'] = (Get-Content -LiteralPath $taskManifest -Raw | ConvertFrom-Json).status }
}
$taskResult | ConvertTo-Json -Depth 6
if (!$taskProcess) {
    Write-Output 'Process has exited. Check manifest status and the end of the logs before assuming success.'
    Get-Content -LiteralPath $taskState.stderr -Tail 8 -ErrorAction SilentlyContinue
}
