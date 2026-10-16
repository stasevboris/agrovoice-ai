param([string]$Serial='b861c4e0')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskPython=Join-Path $PSScriptRoot 'venv/Scripts/python.exe'
$taskDevice=(& $taskAdb -s $Serial get-state 2>$null)-join ''
if($taskDevice.Trim() -ne 'device'){throw 'Note7 must be connected and authorized before phone verification'}
$taskReport=Join-Path $taskRoot 'docs/android/voice_v2'
New-Item -ItemType Directory -Path $taskReport -Force | Out-Null
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
& (Join-Path $taskRoot 'android-app/gradlew.bat') -p (Join-Path $taskRoot 'android-app') :app:testDebugUnitTest :app:assembleDebugAndroidTest --max-workers=2 --console=plain
if($LASTEXITCODE -ne 0){throw 'Unit/test APK build failed'}
& (Join-Path $PSScriptRoot 'Build-AccuracyApk.ps1') -Install -Serial $Serial
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if($LASTEXITCODE -ne 0){throw 'Instrumentation APK install failed'}
$taskSdk=[int]((& $taskAdb -s $Serial shell getprop ro.build.version.sdk)-join '')
$taskPermissions=@('android.permission.RECORD_AUDIO','android.permission.CAMERA')
$taskPermissions+=if($taskSdk -ge 33){'android.permission.READ_MEDIA_IMAGES'}else{'android.permission.READ_EXTERNAL_STORAGE'}
if($taskSdk -le 28){$taskPermissions+='android.permission.WRITE_EXTERNAL_STORAGE'}
foreach($taskPermission in $taskPermissions){
    & $taskAdb -s $Serial shell pm grant com.agronomy.phytomonitor $taskPermission
    if($LASTEXITCODE -ne 0){throw ('Cannot grant '+$taskPermission)}
}
$taskRemote='/sdcard/Android/data/com.agronomy.phytomonitor/files/voice_validation_v2'
& $taskAdb -s $Serial shell mkdir -p $taskRemote
& $taskAdb -s $Serial push ((Join-Path $taskReport 'audio_samples')+'/.') $taskRemote
if($LASTEXITCODE -ne 0){throw 'Voice fixtures upload failed'}
$taskClasses='com.agronomy.phytomonitor.VoiceRecognitionTest,com.agronomy.phytomonitor.VoiceUiTest,com.agronomy.phytomonitor.PhotoVoiceFlowTest'
$taskOutput=(& $taskAdb -s $Serial shell am instrument -w -r -e class $taskClasses com.agronomy.phytomonitor.test/androidx.test.runner.AndroidJUnitRunner 2>&1)-join "`n"
$taskOutput | Set-Content -LiteralPath (Join-Path $taskReport 'instrumentation.txt') -Encoding utf8
foreach($taskFile in @('recognition_results.json','camera_flow.json','gallery_flow.json')){
    & $taskAdb -s $Serial pull ($taskRemote+'/'+$taskFile) (Join-Path $taskReport $taskFile)
}
if($taskOutput -notmatch 'OK \(5 tests\)'){Write-Output $taskOutput;throw 'Phone voice/flow tests failed; evidence preserved in voice_v2'}

# Preserve previous verified numerical evidence before taking fresh Android traces.
$taskAccuracy=Join-Path $taskRoot 'docs/android/accuracy_v1'
$taskPrevious=Get-Content -LiteralPath (Join-Path $taskAccuracy 'verification.json') -Raw | ConvertFrom-Json
if($taskPrevious.apk -like '*0.9.apk') {
    Copy-Item -LiteralPath (Join-Path $taskAccuracy 'verification.json') -Destination (Join-Path $taskAccuracy 'verification_0.9.json') -Force
    if(!(Test-Path -LiteralPath (Join-Path $taskAccuracy 'field_validation_0.9'))){Copy-Item -LiteralPath (Join-Path $taskAccuracy 'field_validation') -Destination (Join-Path $taskAccuracy 'field_validation_0.9') -Recurse}
}
& $taskAdb -s $Serial shell am force-stop com.agronomy.phytomonitor
$taskField='/sdcard/Android/data/com.agronomy.phytomonitor/files/field_validation'
& $taskAdb -s $Serial shell rm -f ($taskField+'/summary.json')
& $taskAdb -s $Serial shell am start -n com.agronomy.phytomonitor/.FieldValidationActivity --es photos '01_potato_late_blight.jpg,05_tomato_septoria_2026.jpg,09_cucumber_downy_mildew_2024.jpg'
if($LASTEXITCODE -ne 0){throw 'Field validation launch failed'}
$taskComplete=$false
for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++) {
    Start-Sleep -Milliseconds 500
    $taskRaw=(& $taskAdb -s $Serial shell cat ($taskField+'/summary.json') 2>$null)-join "`n"
    if($LASTEXITCODE -eq 0){
        $taskSummary=$taskRaw | ConvertFrom-Json
        if($taskSummary.status -eq 'failed'){throw ('Field validation failed: '+$taskSummary.error)}
        if($taskSummary.status -eq 'complete'){$taskComplete=$true;break}
    }
}
if(!$taskComplete){throw 'Field validation did not finish within 30 seconds'}
& $taskAdb -s $Serial pull $taskField $taskAccuracy
if($LASTEXITCODE -ne 0){throw 'Fresh field traces download failed'}
& $taskPython (Join-Path $PSScriptRoot 'verify_accuracy_phone.py') --apk (Join-Path $taskRoot 'artifacts/apk/AgroVoice-model-lab-0.10.apk')
if($LASTEXITCODE -ne 0){throw 'Numerical visual-model parity failed'}
& $taskPython (Join-Path $PSScriptRoot 'build_voice_flow_report.py')
if($LASTEXITCODE -ne 0){throw 'Voice-flow report verification failed'}
& $taskAdb -s $Serial shell am force-stop com.agronomy.phytomonitor
& $taskAdb -s $Serial shell am start -n com.agronomy.phytomonitor/.FieldDemoActivity
Write-Output 'Voice/camera/gallery tests and all eight visual-model parity checks completed; inspect voice_v2/verification.json'
