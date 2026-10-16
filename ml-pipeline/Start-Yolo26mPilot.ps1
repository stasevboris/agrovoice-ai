$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'TrainingProcess.ps1')
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskActive=Join-Path $PSScriptRoot 'logs/active_training.json'
if(Test-Path -LiteralPath $taskActive){
    $taskPrevious=Get-Content -LiteralPath $taskActive -Raw|ConvertFrom-Json
    if(Get-TrainingProcess $taskPrevious){throw 'A GPU worker is already active.'}
}
$taskStamp=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd_HHmmss_fff')
$taskStdout=Join-Path $PSScriptRoot ('logs/'+$taskStamp+'.yolo26m_pilot.stdout.log')
$taskStderr=Join-Path $PSScriptRoot ('logs/'+$taskStamp+'.yolo26m_pilot.stderr.log')
$taskStop=Join-Path $PSScriptRoot ('logs/'+$taskStamp+'.yolo26m_pilot.stop')
$taskWslRoot='/mnt/'+$taskRoot.Substring(0,1).ToLowerInvariant()+'/'+$taskRoot.Substring(3).Replace('\','/')
$taskFocus=Get-Content -LiteralPath (Join-Path $taskRoot 'docs/experiments/yolo26s_focus_v1/status.json') -Raw | ConvertFrom-Json
if($taskFocus.status -ne 'completed' -or $taskFocus.completed -ne 10){throw 'Finish10S before M pilot'}
& (Join-Path $PSScriptRoot 'venv/Scripts/python.exe') -c 'import sys; sys.path.insert(0,sys.argv[1]); from yolo26m_validation import protocol,base_examples; protocol(); base_examples()' $PSScriptRoot
if($LASTEXITCODE -ne 0){throw 'Frozen M protocol or ten-S selection preflight failed.'}
$taskArgs=@('--distribution','AgroVoice-ML','--user','root','--exec','bash',($taskWslRoot+'/ml-pipeline/accuracy_python.sh'),($taskWslRoot+'/ml-pipeline/yolo_m_accuracy.py'),'--seed','20261003','--stop-file',($taskWslRoot+'/ml-pipeline/logs/'+$taskStamp+'.yolo26m_pilot.stop'))
$taskQuoted=($taskArgs|ForEach-Object {if($_ -match '\s'){'"'+$_+'"'}else{$_}})-join ' '
$taskProcess=Start-Process -FilePath (Get-Command wsl.exe).Source -ArgumentList $taskQuoted -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput $taskStdout -RedirectStandardError $taskStderr -PassThru
$taskState=[ordered]@{pid=$taskProcess.Id;backend='wsl';started_utc=[DateTimeOffset]::UtcNow.ToString('o');name='yolo26m_pilot_v1';study='yolo26m_v1';stdout=$taskStdout;stderr=$taskStderr;stop_file=$taskStop;status_path='docs/experiments/yolo26m_v1/status.json'}
$taskState|ConvertTo-Json|Set-Content -LiteralPath $taskActive -Encoding utf8
$taskState|ConvertTo-Json
