param([string]$Serial='b861c4e0',[Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9_]+$')][string]$Label,[switch]$Voice,[switch]$Benchmark,[switch]$Lab)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskPython=Join-Path $PSScriptRoot 'venv/Scripts/python.exe'
$taskDevice=(& $taskAdb -s $Serial get-state 2>$null)-join ''
if($taskDevice.Trim() -ne 'device'){throw 'Phone must be connected and authorized.'}
$taskReport=Join-Path $taskRoot ('docs/android/'+$Label)
New-Item -ItemType Directory -Path $taskReport -Force | Out-Null
if($Voice -and !(Test-Path -LiteralPath (Join-Path $taskReport 'audio_samples/cases.json'))){throw 'Prepare stage-specific voice samples before build/install.'}
$taskAccuracy=Join-Path $taskRoot 'docs/android/accuracy_v1'
if(Test-Path -LiteralPath (Join-Path $taskAccuracy 'verification.json')){
    $taskPrevious=Get-Content -LiteralPath (Join-Path $taskAccuracy 'verification.json') -Raw | ConvertFrom-Json
    $taskArchive=Join-Path $taskRoot ('docs/android/archive_models/'+$taskPrevious.apk_sha256)
    if(!(Test-Path -LiteralPath $taskArchive)){
        New-Item -ItemType Directory -Path $taskArchive -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $taskAccuracy 'verification.json') -Destination $taskArchive
        foreach($taskPart in @('field_validation','benchmark')){
            $taskSource=Join-Path $taskAccuracy $taskPart
            if(Test-Path -LiteralPath $taskSource){Copy-Item -LiteralPath $taskSource -Destination $taskArchive -Recurse}
        }
    }
}
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
$taskGradleTasks=@(':app:testDebugUnitTest')
if($Voice){$taskGradleTasks+=':app:assembleDebugAndroidTest'}
& (Join-Path $taskRoot 'android-app/gradlew.bat') -p (Join-Path $taskRoot 'android-app') @taskGradleTasks --max-workers=2 --console=plain
if($LASTEXITCODE -ne 0){throw 'Gradle checks failed.'}
& (Join-Path $PSScriptRoot 'Build-AccuracyApk.ps1') -Install -Serial $Serial
$taskCurrent=Get-Content -LiteralPath (Join-Path $taskRoot 'artifacts/apk/current_model_lab.json') -Raw | ConvertFrom-Json
if(!$taskCurrent.installed -or $taskCurrent.serial -ne $Serial -or $taskCurrent.version_code -le 11){throw 'New APK must be installed, with version above0.11.'}
if($Voice){
    $taskRemoteVoice='/sdcard/Android/data/com.agronomy.phytomonitor/files/voice_'+$Label
    $taskSamples=Join-Path $taskReport 'audio_samples'
    if(!(Test-Path -LiteralPath (Join-Path $taskSamples 'cases.json'))){throw 'Prepare stage-specific voice samples before -Voice.'}
    & $taskAdb -s $Serial install -r (Join-Path $taskRoot 'android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    if($LASTEXITCODE -ne 0){throw 'Test APK installation failed.'}
    $taskSdk=[int]((& $taskAdb -s $Serial shell getprop ro.build.version.sdk)-join '')
    $taskPermissions=@('android.permission.RECORD_AUDIO','android.permission.CAMERA')
    $taskPermissions+=if($taskSdk -ge 33){'android.permission.READ_MEDIA_IMAGES'}else{'android.permission.READ_EXTERNAL_STORAGE'}
    if($taskSdk -le 28){$taskPermissions+='android.permission.WRITE_EXTERNAL_STORAGE'}
    foreach($taskPermission in $taskPermissions){& $taskAdb -s $Serial shell pm grant com.agronomy.phytomonitor $taskPermission;if($LASTEXITCODE -ne 0){throw 'Permission grant failed.'}}
    & $taskAdb -s $Serial shell mkdir -p $taskRemoteVoice
    & $taskAdb -s $Serial push ($taskSamples+'/.') $taskRemoteVoice
    if($LASTEXITCODE -ne 0){throw 'Voice samples upload failed.'}
    $taskClasses='com.agronomy.phytomonitor.VoiceRecognitionTest,com.agronomy.phytomonitor.VoiceUiTest,com.agronomy.phytomonitor.VoiceControlsTest'
    if($Lab){$taskClasses+=',com.agronomy.phytomonitor.InspectionToolsTest,com.agronomy.phytomonitor.NoteSpeechFlowTest'}
    $taskText=(& $taskAdb -s $Serial shell am instrument -w -r -e voiceFolder ('voice_'+$Label) -e class $taskClasses com.agronomy.phytomonitor.test/androidx.test.runner.AndroidJUnitRunner 2>&1)-join "`n"
    $taskText | Set-Content -LiteralPath (Join-Path $taskReport 'instrumentation.txt') -Encoding utf8
    foreach($taskFile in @('recognition_results.json','camera_flow.json','gallery_flow.json','continuous_flow.json','controls_flow.json')){
        & $taskAdb -s $Serial pull ($taskRemoteVoice+'/'+$taskFile) (Join-Path $taskReport $taskFile)
        if($LASTEXITCODE -ne 0){throw 'Fresh voice evidence missing.'}
    }
    if($Lab){
        foreach($taskFile in @('inspection_tools.json','comparison_tools.json','note_speech_flow.json','tts_flow.json')){& $taskAdb -s $Serial pull ($taskRemoteVoice+'/'+$taskFile) (Join-Path $taskReport $taskFile);if($LASTEXITCODE -ne 0){throw 'New tools evidence missing.'}}
    }
    $taskExpectedTests=if($Lab){10}else{6}
    if($taskText -notmatch ('OK \('+$taskExpectedTests+' tests\)')){throw 'Native voice/flow checks failed; saved stage log.'}
}
& $taskAdb -s $Serial shell am force-stop com.agronomy.phytomonitor
$taskField='/sdcard/Android/data/com.agronomy.phytomonitor/files/field_validation'
& $taskAdb -s $Serial shell rm -f ($taskField+'/summary.json')
& $taskAdb -s $Serial shell am start -n com.agronomy.phytomonitor/.FieldValidationActivity --es photos '01_potato_late_blight.jpg,05_tomato_septoria_2026.jpg,09_cucumber_downy_mildew_2024.jpg'
if($LASTEXITCODE -ne 0){throw 'Validation launch failed.'}
$taskReady=$false
for($taskAttempt=0;$taskAttempt -lt 90;$taskAttempt++){
    Start-Sleep -Milliseconds 500
    $taskRaw=(& $taskAdb -s $Serial shell cat ($taskField+'/summary.json') 2>$null)-join "`n"
    if($LASTEXITCODE -eq 0){
        $taskValue=$taskRaw | ConvertFrom-Json
        if($taskValue.status -eq 'failed'){throw ('Field validation failed: '+$taskValue.error)}
        if($taskValue.status -eq 'complete'){$taskReady=$true;break}
    }
}
if(!$taskReady){throw 'Fresh field validation timed out.'}
& $taskAdb -s $Serial pull $taskField $taskAccuracy
if($LASTEXITCODE -ne 0){throw 'Fresh tensors download failed.'}
& $taskPython (Join-Path $PSScriptRoot 'verify_accuracy_phone.py') --apk (Join-Path $taskRoot $taskCurrent.apk)
if($LASTEXITCODE -ne 0){throw 'Android/Desktop parity failed.'}
Copy-Item -LiteralPath (Join-Path $taskAccuracy 'verification.json') -Destination (Join-Path $taskReport 'verification.json') -Force
if($Benchmark){
    & (Join-Path $PSScriptRoot 'Measure-AccuracyPhone.ps1') -Serial $Serial -Series 10
    & $taskPython (Join-Path $PSScriptRoot 'summarize_accuracy_phone.py')
    if($LASTEXITCODE -ne 0){throw 'Ten series summary failed.'}
    Copy-Item -LiteralPath (Join-Path $taskAccuracy 'benchmark/summary.json') -Destination (Join-Path $taskReport 'benchmark_summary.json') -Force
    Copy-Item -LiteralPath (Join-Path $taskAccuracy 'benchmark/latest_batch.json') -Destination (Join-Path $taskReport 'benchmark_batch.json') -Force
}
& $taskAdb -s $Serial shell am start -S -n com.agronomy.phytomonitor/.FieldDemoActivity
Write-Output ('Stage '+$Label+' checked. See '+$taskReport+'; M mobile gate remains a separate strict step.')
