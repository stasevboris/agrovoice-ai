# Read-only check of the installed sensor library with FanControl closed.
$ErrorActionPreference = 'Stop'
$taskLibraryRoot = 'C:\Program Files (x86)\FanControl'
$taskOutput = 'F:\agrovoice-ai\docs\environment\hardware_without_fancontrol_2026-10-03.json'
$taskReport = 'F:\agrovoice-ai\docs\environment\hardware_without_fancontrol_report_2026-10-03.txt'
$taskState = @{ started_utc = [DateTime]::UtcNow.ToString('o'); status = 'checking'; changes_applied = $false }
$taskComputer = $null
function Read-TaskTree($taskHardware) {
    $taskHardware.Update()
    foreach ($taskSensor in $taskHardware.Sensors) {
        $taskRow = @{ hardware = $taskHardware.Name; hardware_identifier = $taskHardware.Identifier.ToString(); name = $taskSensor.Name; identifier = $taskSensor.Identifier.ToString(); sensor_type = $taskSensor.SensorType.ToString(); value = $taskSensor.Value }
        if ($taskSensor.Control) {
            $taskRow.control = @{ identifier = $taskSensor.Control.Identifier.ToString(); mode = $taskSensor.Control.ControlMode.ToString(); software_value = $taskSensor.Control.SoftwareValue; minimum = $taskSensor.Control.MinSoftwareValue; maximum = $taskSensor.Control.MaxSoftwareValue }
        }
        $taskRow
    }
    foreach ($taskChild in $taskHardware.SubHardware) { Read-TaskTree $taskChild }
}
try {
    if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Administrator rights required for hardware sensor access.' }
    if (Get-Process -Name FanControl -ErrorAction SilentlyContinue) { throw 'FanControl is running; this probe requires it to remain closed.' }
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Reflection;
using System.Runtime.Loader;
public sealed class TaskProbeLibraryContext : AssemblyLoadContext {
    private readonly string root;
    public TaskProbeLibraryContext(string path) : base("TaskProbeSensors", true) { root = path; }
    protected override Assembly Load(AssemblyName name) {
        var path = Path.Combine(root, name.Name + ".dll");
        return File.Exists(path) ? LoadFromAssemblyPath(path) : null;
    }
}
'@
    $taskContext = [TaskProbeLibraryContext]::new($taskLibraryRoot)
    $taskAssembly = $taskContext.LoadFromAssemblyPath((Join-Path $taskLibraryRoot 'LibreHardwareMonitorLib.dll'))
    $taskComputer = $taskAssembly.CreateInstance('LibreHardwareMonitor.Hardware.Computer')
    $taskComputer.IsCpuEnabled = $true
    $taskComputer.IsMotherboardEnabled = $true
    if ($taskComputer.GetType().GetProperty('IsEmbeddedControllerEnabled')) { $taskComputer.IsEmbeddedControllerEnabled = $true }
    $taskComputer.Open()
    for ($taskWarm = 0; $taskWarm -lt 3; $taskWarm++) {
        $taskSensors = @($taskComputer.Hardware | ForEach-Object { Read-TaskTree $_ })
        Start-Sleep -Seconds 1
    }
    $taskState.sensors = $taskSensors
    $taskState.computer_properties = @($taskComputer.GetType().GetProperties() | Select-Object -ExpandProperty Name)
    $taskComputer.GetReport() | Set-Content -LiteralPath $taskReport -Encoding utf8
    $taskState.status = 'read_only_probe_complete'
} catch {
    $taskState.status = 'probe_failed'
    $taskState.error = $_.Exception.Message
} finally {
    if ($taskComputer) { $taskComputer.Close() }
    $taskState.ended_utc = [DateTime]::UtcNow.ToString('o')
    $taskState | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $taskOutput -Encoding utf8
}
