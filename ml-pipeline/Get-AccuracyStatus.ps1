$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'TrainingProcess.ps1')
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskActive=Get-Content (Join-Path $PSScriptRoot 'logs/active_training.json') -Raw | ConvertFrom-Json
$taskStatus=[ordered]@{running=[bool](Get-TrainingProcess $taskActive);active=$taskActive}
if($taskActive.status_path){
    $taskCurrentPath=Join-Path $taskRoot $taskActive.status_path
    if(Test-Path -LiteralPath $taskCurrentPath){$taskStatus.current_status=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json}
}
foreach($taskName in @('architecture_status','status','progress','detector_progress')){
    $taskPath=Join-Path $taskRoot ('docs/experiments/accuracy_v1/'+$taskName+'.json')
    if(Test-Path -LiteralPath $taskPath){$taskStatus[$taskName]=Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json}
}
$taskStatus | ConvertTo-Json -Depth 6
