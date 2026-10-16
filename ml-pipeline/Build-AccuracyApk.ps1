param([switch]$Install,[string]$Serial='b861c4e0')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskGradle=Get-Content -LiteralPath (Join-Path $taskRoot 'android-app/app/build.gradle.kts') -Raw
$taskVersion=[regex]::Match($taskGradle,'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$taskCode=[int][regex]::Match($taskGradle,'versionCode\s*=\s*(\d+)').Groups[1].Value
if(!$taskVersion.EndsWith('-model-lab') -or $taskCode -lt 1){throw 'Expected a model-lab version in Gradle'}
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
& (Join-Path $taskRoot 'android-app/gradlew.bat') -p (Join-Path $taskRoot 'android-app') :app:assembleDebug --max-workers=2 --console=plain
if($LASTEXITCODE -ne 0){throw 'APK build failed'}
$taskFolder=Join-Path $taskRoot 'artifacts/apk'
New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
$taskName='AgroVoice-model-lab-'+$taskVersion.Replace('-model-lab','')+'.apk'
$taskTarget=Join-Path $taskFolder $taskName
$taskSource=Join-Path $taskRoot 'android-app/app/build/outputs/apk/debug/app-debug.apk'
$taskSha=(Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash.ToLowerInvariant()
if(Test-Path -LiteralPath $taskTarget){
    $taskOldSha=(Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    if($taskOldSha -ne $taskSha){
        $taskArchive=Join-Path $taskFolder 'archive'
        New-Item -ItemType Directory -Path $taskArchive -Force | Out-Null
        Copy-Item -LiteralPath $taskTarget -Destination (Join-Path $taskArchive ($taskName.Replace('.apk','-')+$taskOldSha.Substring(0,12)+'.apk'))
    }
}
Copy-Item -LiteralPath $taskSource -Destination $taskTarget -Force
$taskInstalled=$false
if($Install){
    $taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
    & $taskAdb -s $Serial install -r $taskTarget
    if($LASTEXITCODE -ne 0){throw 'APK installation failed'}
    $taskPackage=(& $taskAdb -s $Serial shell dumpsys package com.agronomy.phytomonitor)-join "`n"
    if($LASTEXITCODE -ne 0 -or $taskPackage -notmatch ('versionCode='+$taskCode+'\s') -or !$taskPackage.Contains('versionName='+$taskVersion)){throw 'Installed version does not match build'}
    $taskInstalled=$true
}
$taskState=[ordered]@{status='assembled';apk=('artifacts/apk/'+$taskName);version=$taskVersion;version_code=$taskCode;sha256=$taskSha;bytes=(Get-Item -LiteralPath $taskTarget).Length;installed=$taskInstalled;serial=$Serial;phone_parity='pending';updated_utc=[DateTimeOffset]::UtcNow.ToString('o')}
$taskState | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskFolder 'current_model_lab.json') -Encoding utf8
$taskState | ConvertTo-Json
