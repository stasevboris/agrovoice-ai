$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'TrainingProcess.ps1')
$taskState = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'logs\active_training.json') -Raw | ConvertFrom-Json
if (-not (Get-TrainingProcess $taskState)) { throw 'The recorded training process has already exited.' }
'Stop after the current epoch' | Set-Content -LiteralPath $taskState.stop_file -Encoding utf8
Write-Output 'Stop requested. The current epoch will finish and its checkpoint will be saved.'
