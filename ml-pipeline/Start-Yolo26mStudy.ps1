$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'TrainingProcess.ps1')
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskActive=Join-Path $PSScriptRoot 'logs/active_training.json'
if(Test-Path -LiteralPath $taskActive){
    $taskPrevious=Get-Content -LiteralPath $taskActive -Raw | ConvertFrom-Json
    if(Get-TrainingProcess $taskPrevious){throw 'A GPU worker is already active.'}
}
$taskGate=Join-Path $taskRoot 'docs/experiments/yolo26m_v1/phone_gate.json'
if(!(Test-Path -LiteralPath $taskGate)){throw 'Real M pilot phone gate required before remaining9.'}
if((Get-Content -LiteralPath $taskGate -Raw | ConvertFrom-Json).status -ne 'passed'){throw 'M pilot phone gate did not pass.'}
$taskFocus=Get-Content -LiteralPath (Join-Path $taskRoot 'docs/experiments/yolo26s_focus_v1/status.json') -Raw | ConvertFrom-Json
if($taskFocus.status -ne 'completed' -or $taskFocus.completed -ne 10){throw 'Finish10S before M.'}
& (Join-Path $PSScriptRoot 'venv/Scripts/python.exe') -c 'import sys; sys.path.insert(0,sys.argv[1]); from yolo26m_validation import protocol,base_examples,validate_seed,FOCUS,read; p,l=protocol(); base_examples(); g=read(FOCUS/"phone_gate.json"); assert g["status"]=="passed" and g["model_sha256"]==validate_seed(20261003)["graph_sha256"] and g["plan_sha256"]==l["plan_sha256"]' $PSScriptRoot
if($LASTEXITCODE -ne 0){throw 'Frozen M protocol, pilot or real phone gate preflight failed.'}
$taskStamp=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd_HHmmss_fff')
$taskStdout=Join-Path $PSScriptRoot ('logs/'+$taskStamp+'.yolo26m_study.stdout.log')
$taskStderr=Join-Path $PSScriptRoot ('logs/'+$taskStamp+'.yolo26m_study.stderr.log')
$taskStop=Join-Path $PSScriptRoot ('logs/'+$taskStamp+'.yolo26m_study.stop')
$taskWslRoot='/mnt/'+$taskRoot.Substring(0,1).ToLowerInvariant()+'/'+$taskRoot.Substring(3).Replace('\','/')
$taskArgs=@('--distribution','AgroVoice-ML','--user','root','--exec','bash',($taskWslRoot+'/ml-pipeline/accuracy_python.sh'),($taskWslRoot+'/ml-pipeline/yolo26m_queue.py'),'--stop-file',($taskWslRoot+'/ml-pipeline/logs/'+$taskStamp+'.yolo26m_study.stop'))
$taskQuoted=($taskArgs | ForEach-Object {if($_ -match '\s'){'"'+$_+'"'}else{$_}})-join ' '
$taskProcess=Start-Process -FilePath (Get-Command wsl.exe).Source -ArgumentList $taskQuoted -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput $taskStdout -RedirectStandardError $taskStderr -PassThru
$taskState=[ordered]@{pid=$taskProcess.Id;backend='wsl';started_utc=[DateTimeOffset]::UtcNow.ToString('o');name='yolo26m_v1';study='yolo26m_v1';stdout=$taskStdout;stderr=$taskStderr;stop_file=$taskStop;status_path='docs/experiments/yolo26m_v1/queue_status.json'}
$taskState | ConvertTo-Json | Set-Content -LiteralPath $taskActive -Encoding utf8
$taskState | ConvertTo-Json
