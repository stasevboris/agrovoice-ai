$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'TrainingProcess.ps1')
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskActive=Join-Path $PSScriptRoot 'logs/active_training.json'
if(Test-Path -LiteralPath $taskActive){
    $taskPrevious=Get-Content -LiteralPath $taskActive -Raw|ConvertFrom-Json
    if(Get-TrainingProcess $taskPrevious){throw 'A GPU worker is already active.'}
}
$taskStamp=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd_HHmmss_fff')
$taskStdout=Join-Path $PSScriptRoot ('logs/'+$taskStamp+'.yolo26s_focus.stdout.log')
$taskStderr=Join-Path $PSScriptRoot ('logs/'+$taskStamp+'.yolo26s_focus.stderr.log')
$taskStop=Join-Path $PSScriptRoot ('logs/'+$taskStamp+'.yolo26s_focus.stop')
$taskWslRoot='/mnt/'+$taskRoot.Substring(0,1).ToLowerInvariant()+'/'+$taskRoot.Substring(3).Replace('\','/')
$taskArgs=@('--distribution','AgroVoice-ML','--user','root','--exec','bash',($taskWslRoot+'/ml-pipeline/accuracy_python.sh'),($taskWslRoot+'/ml-pipeline/yolo26s_focus_queue.py'),'--stop-file',($taskWslRoot+'/ml-pipeline/logs/'+$taskStamp+'.yolo26s_focus.stop'))
$taskQuoted=($taskArgs|ForEach-Object {if($_ -match '\s'){'"'+$_+'"'}else{$_}})-join ' '
$taskProcess=Start-Process -FilePath (Get-Command wsl.exe).Source -ArgumentList $taskQuoted -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput $taskStdout -RedirectStandardError $taskStderr -PassThru
$taskState=[ordered]@{pid=$taskProcess.Id;backend='wsl';started_utc=[DateTimeOffset]::UtcNow.ToString('o');name='yolo26s_focus_v1';study='yolo26s_focus_v1';stdout=$taskStdout;stderr=$taskStderr;stop_file=$taskStop;status_path='docs/experiments/yolo26s_focus_v1/status.json'}
$taskState|ConvertTo-Json|Set-Content -LiteralPath $taskActive -Encoding utf8
$taskState|ConvertTo-Json
