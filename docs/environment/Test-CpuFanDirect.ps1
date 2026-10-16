# One-minute CPU_FAN check using the installed sensor library, with FanControl closed.
$ErrorActionPreference = 'Stop'
$taskStateFile = 'F:\agrovoice-ai\docs\environment\cpu_fan_direct_test_2026-10-03.json'
$taskLibraryRoot = 'C:\Program Files (x86)\FanControl'
$taskState = @{ status='checking'; requested_seconds=60; cutoff_celsius=70; started_utc=[DateTime]::UtcNow.ToString('o'); samples=@() }
$taskComputer = $null
$taskGuard = $null
$taskPaused = $false
function Save-TaskState { $taskState | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $taskStateFile -Encoding utf8 }
function Read-TaskHardware($taskHardware) {
    $taskHardware.Update()
    $taskHardware.Sensors
    foreach ($taskChild in $taskHardware.SubHardware) { Read-TaskHardware $taskChild }
}
function Read-TaskSample {
    $taskSensors = @($taskComputer.Hardware | ForEach-Object { Read-TaskHardware $_ })
    $taskTemp = @($taskSensors | Where-Object { $_.SensorType.ToString() -eq 'Temperature' -and $_.Name -eq 'Core (Tctl/Tdie)' })
    $taskFan = @($taskSensors | Where-Object { $_.Identifier.ToString() -eq '/lpc/nct6687d/0/fan/0' })
    $taskControl = @($taskSensors | Where-Object { $_.Identifier.ToString() -eq '/lpc/nct6687d/0/control/0' })
    return @{ utc=[DateTime]::UtcNow.ToString('o'); celsius=if($taskTemp.Count -eq 1){$taskTemp[0].Value}else{$null}; rpm=if($taskFan.Count -eq 1){$taskFan[0].Value}else{$null}; control_percent=if($taskControl.Count -eq 1){$taskControl[0].Value}else{$null} }
}
try {
    if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Administrator rights required.' }
    if (Get-Process -Name FanControl -ErrorAction SilentlyContinue) { throw 'FanControl is running; no changes applied.' }
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Reflection;
using System.Runtime.Loader;
using System.Threading;
public sealed class TaskDirectLibraryContext : AssemblyLoadContext {
    private readonly string root;
    public TaskDirectLibraryContext(string path) : base("TaskDirectSensors",true) { root=path; }
    protected override Assembly Load(AssemblyName name) {
        var path=Path.Combine(root,name.Name+".dll");
        return File.Exists(path)?LoadFromAssemblyPath(path):null;
    }
}
public sealed class TaskCpuRestoreTimer : IDisposable {
    private readonly object control;
    private readonly object gate=new object();
    private readonly Timer timer;
    public bool Restored { get; private set; }
    public string Error { get; private set; }
    public TaskCpuRestoreTimer(object value) {
        control=value;
        timer=new Timer(_=>Restore(),null,TimeSpan.FromSeconds(60),Timeout.InfiniteTimeSpan);
    }
    public void Restore() {
        lock(gate) {
            if(Restored) return;
            try { control.GetType().GetMethod("SetDefault").Invoke(control,null); Restored=true; Error=null; }
            catch(Exception e) { Error=e.ToString(); }
        }
    }
    public void Dispose() { Restore(); timer.Dispose(); }
}
'@
    $taskContext = [TaskDirectLibraryContext]::new($taskLibraryRoot)
    $taskAssembly = $taskContext.LoadFromAssemblyPath((Join-Path $taskLibraryRoot 'LibreHardwareMonitorLib.dll'))
    $taskComputer = $taskAssembly.CreateInstance('LibreHardwareMonitor.Hardware.Computer')
    $taskComputer.IsCpuEnabled = $true
    $taskComputer.IsMotherboardEnabled = $true
    $taskComputer.Open()
    for($taskWarm=0;$taskWarm -lt 3;$taskWarm++){ $taskBefore=Read-TaskSample; Start-Sleep -Seconds 1 }
    $taskSensors = @($taskComputer.Hardware | ForEach-Object { Read-TaskHardware $_ })
    $taskCpuSensors = @($taskSensors | Where-Object { $_.Identifier.ToString() -eq '/lpc/nct6687d/0/control/0' -and $_.Name -eq 'CPU Fan' -and $_.Control })
    if($taskCpuSensors.Count -ne 1 -or $null -eq $taskBefore.rpm -or $taskBefore.rpm -le 0){ throw 'Live CPU fan/control could not be uniquely identified; no changes applied.' }
    $taskState.loaded_sample = $taskBefore
    Copy-Item -LiteralPath 'F:\agrovoice-ai\docs\environment\study_cpu_noise_pause_2026-10-03.json' -Destination 'F:\agrovoice-ai\docs\environment\study_cpu_noise_pause_before_direct_2026-10-03.json' -Force
    & wsl.exe -d AgroVoice-ML -- /opt/agrovoice/venv/bin/python /mnt/f/agrovoice-ai/ml-pipeline/temporary_noise_test_pause.py
    if($LASTEXITCODE -ne 0){ throw 'Study pause failed; no fan changes applied.' }
    $taskPaused=$true
    $taskState.status='cooling'
    Save-TaskState
    for($taskCool=0;$taskCool -lt 30;$taskCool++){ Start-Sleep -Seconds 2; $taskBefore=Read-TaskSample; if($null -ne $taskBefore.celsius -and $taskBefore.celsius -lt 65){break} }
    $taskState.before=$taskBefore
    if($null -eq $taskBefore.celsius -or $taskBefore.celsius -lt 10 -or $taskBefore.celsius -ge 70){throw 'CPU temperature is unavailable or too high; no fan changes applied.'}
    $taskCpuControl=$taskCpuSensors[0].Control
    $taskGuard=[TaskCpuRestoreTimer]::new($taskCpuControl)
    $taskCpuControl.SetSoftware([single]0)
    $taskState.status='direct_zero_requested'
    Save-TaskState
    $taskClock=[Diagnostics.Stopwatch]::StartNew()
    while($taskClock.Elapsed.TotalSeconds -lt 60 -and -not $taskGuard.Restored){
        Start-Sleep -Seconds 2
        $taskSample=Read-TaskSample
        $taskState.samples+=$taskSample
        if($null -ne $taskSample.rpm -and $taskSample.rpm -eq 0){$taskState.zero_rpm_observed=$true}
        Save-TaskState
        if($null -eq $taskSample.celsius -or $taskSample.celsius -lt 10 -or $taskSample.celsius -ge 70){$taskState.stop_reason='temperature_guard';break}
        if($taskClock.Elapsed.TotalSeconds -ge 15 -and -not $taskState.zero_rpm_observed){$taskState.stop_reason='fan_did_not_stop';break}
        if(Get-Process -Name FanControl -ErrorAction SilentlyContinue){$taskState.stop_reason='FanControl restarted';break}
    }
    $taskState.elapsed_seconds=[Math]::Round($taskClock.Elapsed.TotalSeconds,1)
    if(-not $taskState.stop_reason){$taskState.stop_reason='60-second timeout'}
} catch {
    $taskState.error=$_.Exception.Message
    $taskState.status='failed'
} finally {
    if($taskGuard){
        $taskGuard.Restore()
        $taskState.restore_called=$taskGuard.Restored
        $taskState.restore_error=$taskGuard.Error
        if(-not $taskGuard.Restored){$taskCpuControl.SetSoftware([single]100)}
        Start-Sleep -Seconds 3
        $taskState.after=Read-TaskSample
        $taskGuard.Dispose()
        if($taskGuard.Restored){$taskState.status='default_control_restored'}
    }
    if($taskComputer){$taskComputer.Close()}
    if($taskPaused){
        & wsl.exe -d AgroVoice-ML -- /opt/agrovoice/venv/bin/python /mnt/f/agrovoice-ai/ml-pipeline/temporary_noise_test_pause.py --resume
        $taskState.study_resume_exit_code=$LASTEXITCODE
    }
    $taskState.ended_utc=[DateTime]::UtcNow.ToString('o')
    Save-TaskState
}
