param([string]$Serial='b861c4e0',[int]$Series=10,[string]$Photo='01_potato_late_blight.jpg')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskOutput=Join-Path $taskRoot 'docs/android/accuracy_v1/benchmark'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskStamp=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd_HHmmss')
$taskFiles=@()
for($taskIndex=0;$taskIndex -lt $Series;$taskIndex++){
    $taskLabel='pipeline_'+$taskStamp+'_'+$taskIndex.ToString('00')
    $taskRemote='/sdcard/Android/data/com.agronomy.phytomonitor/files/benchmark/'+$taskLabel+'.json'
    & $taskAdb -s $Serial shell am start -S -n com.agronomy.phytomonitor/.ModelBenchmarkActivity --es model $taskLabel --es photo $Photo --ei series $taskIndex --ei iterations 10 | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Could not start phone benchmark'}
    $taskDeadline=[DateTimeOffset]::UtcNow.AddMinutes(5)
    $taskReady=$false
    while([DateTimeOffset]::UtcNow -lt $taskDeadline){
        Start-Sleep -Seconds 2
        $taskText=(& $taskAdb -s $Serial shell cat $taskRemote 2>$null)-join "`n"
        if($LASTEXITCODE -ne 0){continue}
        try{$taskReport=$taskText | ConvertFrom-Json}catch{continue}
        if($taskReport.status -ne 'passed'){throw ('Phone benchmark failed: '+$taskText)}
        $taskFile=Join-Path $taskOutput ($taskLabel+'.json')
        $taskText | Set-Content -LiteralPath $taskFile -Encoding utf8
        $taskFiles+=$taskFile;$taskReady=$true;break
    }
    if(!$taskReady){throw 'Phone benchmark timed out'}
    Write-Output ('Series '+($taskIndex+1)+'/'+$Series+' passed: '+$taskLabel)
}
[ordered]@{status='passed';series=$Series;files=$taskFiles;phone=$Serial;photo=$Photo;completed_utc=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskOutput 'latest_batch.json') -Encoding utf8
& $taskAdb -s $Serial shell am start -S -n com.agronomy.phytomonitor/.FieldDemoActivity | Out-Null
