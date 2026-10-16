param([int]$LimitJobs=0,[switch]$AllArchitectures)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'TrainingProcess.ps1')
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskLogs=Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskActive=Join-Path $taskLogs 'active_training.json'
if(Test-Path -LiteralPath $taskActive){
    $taskPrevious=Get-Content -LiteralPath $taskActive -Raw | ConvertFrom-Json
    if(Get-TrainingProcess $taskPrevious){throw 'A GPU worker is already active.'}
}
$taskStamp=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd_HHmmss_fff')
$taskStdout=Join-Path $taskLogs ($taskStamp+'.accuracy.stdout.log')
$taskStderr=Join-Path $taskLogs ($taskStamp+'.accuracy.stderr.log')
$taskStop=Join-Path $taskLogs ($taskStamp+'.accuracy.stop')
$taskDrive=$taskRoot.Substring(0,1).ToLowerInvariant()
$taskWslRoot='/mnt/'+$taskDrive+'/'+$taskRoot.Substring(3).Replace('\','/')
$taskArgs=@('--distribution','AgroVoice-ML','--user','root','--exec','bash',($taskWslRoot+'/ml-pipeline/run_accuracy.sh'),'--stop-file',($taskWslRoot+'/ml-pipeline/logs/'+$taskStamp+'.accuracy.stop'))
if($AllArchitectures){
    $taskArgs=@('--distribution','AgroVoice-ML','--user','root','--exec','bash',($taskWslRoot+'/ml-pipeline/accuracy_python.sh'),($taskWslRoot+'/ml-pipeline/architecture_queue.py'),'--stop-file',($taskWslRoot+'/ml-pipeline/logs/'+$taskStamp+'.accuracy.stop'))
}
if($LimitJobs -gt 0){$taskArgs+=@('--limit-jobs',[string]$LimitJobs)}
$taskQuoted=($taskArgs | ForEach-Object {if($_ -match '\s'){'"'+$_+'"'}else{$_}})-join ' '
$taskProcess=Start-Process -FilePath (Get-Command wsl.exe).Source -ArgumentList $taskQuoted -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput $taskStdout -RedirectStandardError $taskStderr -PassThru
$taskState=[ordered]@{pid=$taskProcess.Id;backend='wsl';started_utc=[DateTimeOffset]::UtcNow.ToString('o');name='accuracy_v1';study='accuracy_v1';stdout=$taskStdout;stderr=$taskStderr;stop_file=$taskStop;status_path='docs/experiments/accuracy_v1/status.json'}
if($AllArchitectures){$taskState.status_path='docs/experiments/accuracy_v1/architecture_status.json'}
$taskState | ConvertTo-Json | Set-Content -LiteralPath $taskActive -Encoding utf8
$taskState | ConvertTo-Json
