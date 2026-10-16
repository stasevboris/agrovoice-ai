# User-authorized timed CPU fan noise isolation, with temperature guard.
# Uses the already-installed FanControl CLI; does not install drivers.
param([ValidateRange(1,120)][int]$Seconds = 60)
$ErrorActionPreference = 'Stop'
$taskStateFile = 'F:\agrovoice-ai\docs\environment\cpu_fan_noise_test_2026-10-03.json'
$taskFanRoot = 'C:\Program Files (x86)\FanControl'
$taskConfigFolder = Join-Path $taskFanRoot 'Configurations'
$taskOriginalName = 'userConfig.json'
$taskTempName = 'zzz_cpu_noise_test_20261003.json'
$taskResult = @{ status = 'checking'; requested_seconds = $Seconds; cutoff_celsius = 70; samples = @(); started_utc = [DateTime]::UtcNow.ToString('o') }
$taskComputer = $null
$taskChanged = $false
$taskStudyPaused = $false
function Save-TaskState {
    $taskResult | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $taskStateFile -Encoding utf8
}
function Read-TaskSensors {
    function Read-TaskHardware($taskHardware) {
        $taskHardware.Update()
        $taskHardware.Sensors
        foreach ($taskSub in $taskHardware.SubHardware) { Read-TaskHardware $taskSub }
    }
    $taskAll = @($taskComputer.Hardware | ForEach-Object { Read-TaskHardware $_ })
    $taskTemp = @($taskAll | Where-Object { $_.SensorType -eq 'Temperature' -and $_.Name -match 'Tctl/Tdie' } | Select-Object -First 1)
    $taskFan = @($taskAll | Where-Object { $_.Identifier.ToString() -match '^/lpc/nct6687d/(0/)?fan/0$' } | Select-Object -First 1)
    return @{ utc = [DateTime]::UtcNow.ToString('o'); celsius = if ($taskTemp.Count) { $taskTemp[0].Value } else { $null }; temperature_identifier = if ($taskTemp.Count) { $taskTemp[0].Identifier.ToString() } else { $null }; rpm = if ($taskFan.Count) { $taskFan[0].Value } else { $null } }
}
try {
    if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Administrator rights required to read hardware sensors.' }
    if (-not (Get-Process -Name FanControl -ErrorAction SilentlyContinue)) {
        Start-Process -FilePath (Join-Path $taskFanRoot 'FanControl.exe') -WorkingDirectory $taskFanRoot -ArgumentList @('-c',$taskOriginalName) -WindowStyle Hidden
        $taskResult.fancontrol_started = $true
        for ($taskStartup = 0; $taskStartup -lt 20; $taskStartup++) {
            Start-Sleep -Seconds 1
            if (Get-Process -Name FanControl -ErrorAction SilentlyContinue) { break }
        }
        if (-not (Get-Process -Name FanControl -ErrorAction SilentlyContinue)) { throw 'FanControl did not start; fan stop was not applied.' }
        Start-Sleep -Seconds 3
    }
    # Keep FanControl's System.Management/PawnIO versions separate from PowerShell.
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Reflection;
using System.Runtime.Loader;
public sealed class TaskFanLibraryContext : AssemblyLoadContext {
    private readonly string root;
    public TaskFanLibraryContext(string path) : base("TaskFanSensors", true) { root = path; }
    protected override Assembly Load(AssemblyName name) {
        var path = Path.Combine(root, name.Name + ".dll");
        return File.Exists(path) ? LoadFromAssemblyPath(path) : null;
    }
}
'@
    $taskLibraryContext = [TaskFanLibraryContext]::new($taskFanRoot)
    $taskLibrary = $taskLibraryContext.LoadFromAssemblyPath((Join-Path $taskFanRoot 'LibreHardwareMonitorLib.dll'))
    $taskComputer = $taskLibrary.CreateInstance('LibreHardwareMonitor.Hardware.Computer')
    $taskComputer.IsCpuEnabled = $true
    $taskComputer.IsMotherboardEnabled = $true
    if ($taskComputer.GetType().GetProperty('IsEmbeddedControllerEnabled')) { $taskComputer.IsEmbeddedControllerEnabled = $true }
    $taskComputer.Open()
    for ($taskWarm = 0; $taskWarm -lt 3; $taskWarm++) { $taskBefore = Read-TaskSensors; Start-Sleep -Seconds 1 }
    $taskResult.loaded_temperature = $taskBefore
    if ($null -ne $taskBefore.celsius -and $taskBefore.celsius -ge 10) {
        & wsl.exe -d AgroVoice-ML -- /opt/agrovoice/venv/bin/python /mnt/f/agrovoice-ai/ml-pipeline/temporary_noise_test_pause.py
        if ($LASTEXITCODE -ne 0) { throw 'Could not safely pause the study; fan stop was not applied.' }
        $taskStudyPaused = $true
        $taskResult.status = 'study_paused_cooling'
        Save-TaskState
        for ($taskCool = 0; $taskCool -lt 30; $taskCool++) {
            Start-Sleep -Seconds 2
            $taskBefore = Read-TaskSensors
            if ($null -ne $taskBefore.celsius -and $taskBefore.celsius -lt 65) { break }
        }
    }
    $taskResult.before = $taskBefore
    if ($null -eq $taskBefore.celsius -or $taskBefore.celsius -lt 10 -or $taskBefore.celsius -ge 70) { throw 'No valid CPU temperature below 70 C; fan stop was not applied.' }
    $taskResult.rpm_feedback_available = ($null -ne $taskBefore.rpm)
    $taskSource = Join-Path $taskConfigFolder $taskOriginalName
    $taskProfile = Get-Content -LiteralPath $taskSource -Raw | ConvertFrom-Json
    $taskCpu = @($taskProfile.FanControl.Controls | Where-Object { $_.NickName -eq 'CPU Fan' -and $_.Identifier -eq '/lpc/nct6687d/control/0' })
    if ($taskCpu.Count -ne 1) { throw 'CPU control could not be uniquely identified.' }
    Copy-Item -LiteralPath $taskSource -Destination 'F:\agrovoice-ai\docs\environment\fancontrol_before_noise_test_2026-10-03.json' -Force
    $taskCurve = ($taskProfile.FanControl.FanCurves | Where-Object Name -eq 'Graph' | Select-Object -First 1) | ConvertTo-Json -Depth 30 | ConvertFrom-Json
    if (-not $taskCurve) { throw 'Existing graph curve template missing.' }
    $taskCurve.Name = 'CPU noise test temperature guard'
    $taskCurve.SelectedTempSource.Identifier = $taskBefore.temperature_identifier
    $taskCurve.Points = @('20,0','69,0','70,100')
    $taskCurve.HysteresisConfig.ResponseTimeUp = 0
    $taskCurve.HysteresisConfig.ResponseTimeDown = 0
    $taskCurve.HysteresisConfig.HysteresisValueUp = 0
    $taskCurve.HysteresisConfig.HysteresisValueDown = 0
    $taskProfile.FanControl.FanCurves += $taskCurve
    $taskCpu[0].ManualControl = $false
    $taskCpu[0].SelectedFanCurve = @{ Name = $taskCurve.Name }
    $taskCpu[0].MinimumPercent = 0
    $taskCpu[0].SelectedStart = 0
    $taskCpu[0].SelectedStop = 0
    $taskProfile | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath (Join-Path $taskConfigFolder $taskTempName) -Encoding utf8
    $taskPwsh = (Get-Process -Id $PID).Path
    # Independent watchdog restores the profile even if this monitor exits unexpectedly.
    $taskWatchdogFile = 'F:\agrovoice-ai\docs\environment\Restore-CpuFanAfterNoiseTest.ps1'
    $taskWatchdogSeconds = $Seconds + 10
    @"
for (`$taskWait = 0; `$taskWait -lt $taskWatchdogSeconds; `$taskWait++) { Start-Sleep -Seconds 1 }
Start-Process -FilePath 'C:\Program Files (x86)\FanControl\FanControl.exe' -WorkingDirectory 'C:\Program Files (x86)\FanControl' -ArgumentList @('-c','userConfig.json') -WindowStyle Hidden
"@ | Set-Content -LiteralPath $taskWatchdogFile -Encoding utf8
    $taskWatchdog = Start-Process -FilePath $taskPwsh -ArgumentList @('-NoProfile','-File',$taskWatchdogFile) -WindowStyle Hidden -PassThru
    $taskResult.watchdog_pid = $taskWatchdog.Id
    $taskChanged = $true
    Start-Process -FilePath (Join-Path $taskFanRoot 'FanControl.exe') -WorkingDirectory $taskFanRoot -ArgumentList @('-c',$taskTempName) -WindowStyle Hidden
    $taskResult.status = 'temporary_profile_requested'
    Save-TaskState
    $taskClock = [Diagnostics.Stopwatch]::StartNew()
    while ($taskClock.Elapsed.TotalSeconds -lt $Seconds) {
        Start-Sleep -Seconds 2
        $taskSample = Read-TaskSensors
        $taskResult.samples += $taskSample
        Save-TaskState
        if ($null -eq $taskSample.celsius -or $taskSample.celsius -lt 10 -or $taskSample.celsius -ge 70) {
            $taskResult.stop_reason = 'temperature_guard_or_invalid_sensor'
            break
        }
        if (-not (Get-Process -Name FanControl -ErrorAction SilentlyContinue)) { $taskResult.stop_reason = 'FanControl exited'; break }
    }
    $taskResult.elapsed_seconds = [Math]::Round($taskClock.Elapsed.TotalSeconds, 1)
    if (-not $taskResult.stop_reason) { $taskResult.stop_reason = "$Seconds-second timeout" }
} catch {
    $taskResult.error = $_.Exception.Message
    $taskResult.status = 'failed_before_stop'
} finally {
    if ($taskChanged) {
        try {
            Start-Process -FilePath (Join-Path $taskFanRoot 'FanControl.exe') -WorkingDirectory $taskFanRoot -ArgumentList @('-c',$taskOriginalName) -WindowStyle Hidden
            Start-Sleep -Seconds 3
            $taskResult.after = Read-TaskSensors
            $taskResult.status = 'original_profile_restore_requested'
        } catch { $taskResult.restore_error = $_.Exception.Message }
    }
    if ($taskComputer) { $taskComputer.Close() }
    if ($taskStudyPaused) {
        & wsl.exe -d AgroVoice-ML -- /opt/agrovoice/venv/bin/python /mnt/f/agrovoice-ai/ml-pipeline/temporary_noise_test_pause.py --resume
        $taskResult.study_resume_exit_code = $LASTEXITCODE
    }
    $taskResult.ended_utc = [DateTime]::UtcNow.ToString('o')
    Save-TaskState
}
