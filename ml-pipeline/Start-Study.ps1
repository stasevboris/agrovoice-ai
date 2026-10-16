param([switch]$Resume)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'TrainingProcess.ps1')
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskLogs = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskActive = Join-Path $taskLogs 'active_training.json'
if (Test-Path -LiteralPath $taskActive) {
    $taskPrevious = Get-Content -LiteralPath $taskActive -Raw | ConvertFrom-Json
    if (Get-TrainingProcess $taskPrevious) { throw 'A training process is already active.' }
}
$taskStamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd_HHmmss_fff')
$taskStdout = Join-Path $taskLogs ($taskStamp + '.study.stdout.log')
$taskStderr = Join-Path $taskLogs ($taskStamp + '.study.stderr.log')
$taskStop = Join-Path $taskLogs ($taskStamp + '.study.stop')
$taskDrive = $taskRoot.Substring(0,1).ToLowerInvariant()
$taskWslRoot = '/mnt/' + $taskDrive + '/' + $taskRoot.Substring(3).Replace('\','/')
$taskArgs = @('--distribution','AgroVoice-ML','--user','root','--exec','bash',($taskWslRoot+'/ml-pipeline/run_wsl_study.sh'),
              '--stop-file',($taskWslRoot+'/ml-pipeline/logs/'+$taskStamp+'.study.stop'))
if ($Resume) { $taskArgs += '--resume' }
$taskQuoted = ($taskArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
$taskProcess = Start-Process -FilePath (Get-Command wsl.exe).Source -ArgumentList $taskQuoted -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput $taskStdout -RedirectStandardError $taskStderr -PassThru
$taskState = [ordered]@{pid=$taskProcess.Id;backend='wsl';started_utc=[DateTimeOffset]::UtcNow.ToString('o');epochs=60;image_size=416;name='study10_v2';study='study10_v2';stdout=$taskStdout;stderr=$taskStderr;stop_file=$taskStop}
$taskState | ConvertTo-Json | Set-Content -LiteralPath $taskActive -Encoding utf8
$taskState | ConvertTo-Json
