# User-requested temporary PnP removal of the single WD HDD.
# No driver deletion, persistent Disable-PnpDevice, force, or reboot.
$ErrorActionPreference = 'Stop'
$taskStatePath = 'F:\agrovoice-ai\docs\environment\hdd_session_state_2026-10-03.json'
$taskExpectedModel = 'WDC WD20EZRZ-22Z5HB0'
$taskExpectedInstance = 'SCSI\DISK&VEN_WDC&PROD_WD20EZRZ-22Z5HB0\5&221BABA8&0&010000'
$taskResult = @{ requested = 'temporary removal until hardware re-enumeration/reboot'; utc = [DateTime]::UtcNow.ToString('o'); model = $taskExpectedModel; instance = $taskExpectedInstance; status = 'checking' }
try {
    $taskAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $taskAdmin) { throw 'Administrator rights are required.' }
    $taskDevices = @(Get-CimInstance Win32_DiskDrive | Where-Object { $_.Model -eq $taskExpectedModel -and $_.PNPDeviceID -eq $taskExpectedInstance })
    if ($taskDevices.Count -ne 1) { throw 'The exact expected HDD could not be uniquely identified.' }
    $taskDiskDevice = $taskDevices[0]
    $taskPartitions = @(Get-CimAssociatedInstance -InputObject $taskDiskDevice -Association Win32_DiskDriveToDiskPartition)
    if ($taskPartitions | Where-Object BootPartition) { throw 'Refusing to remove a boot partition.' }
    $taskLetters = @($taskPartitions | ForEach-Object { Get-CimAssociatedInstance -InputObject $_ -Association Win32_LogicalDiskToPartition } | Select-Object -ExpandProperty DeviceID -Unique)
    if ($taskLetters.Count -ne 1 -or $taskLetters[0] -ne 'E:') { throw 'The HDD volume mapping differs from the verified E: mapping.' }
    $taskVolumes = @(Get-CimInstance Win32_Volume | Where-Object { $_.DriveLetter -in $taskLetters })
    if ($taskVolumes | Where-Object { $_.BootVolume -or $_.SystemVolume -or $_.PageFilePresent }) { throw 'Refusing to remove a system or page-file volume.' }
    $taskPages = @(Get-CimInstance Win32_PageFileUsage)
    if ($taskPages | Where-Object { $_.Name.Substring(0,2) -in $taskLetters }) { throw 'Refusing to remove an active page-file disk.' }
    $taskResult.volumes = $taskLetters
    # PnP performs orderly removal; omit /force so open/critical use can veto it.
    $taskCommandOutput = & "$env:WINDIR\System32\pnputil.exe" /remove-device $taskExpectedInstance 2>&1
    $taskResult.exit_code = $LASTEXITCODE
    $taskResult.output = ($taskCommandOutput | Out-String).Trim()
    $taskConnected = @(Get-PnpDevice -PresentOnly -Class DiskDrive | Where-Object InstanceId -eq $taskExpectedInstance)
    $taskResult.present_after = $taskConnected.Count -gt 0
    $taskResult.status = if ($taskResult.present_after) { 'still_present' } else { 'removed_from_windows' }
    $taskResult.note = 'Electrical power and spindle state are not measured. Hardware re-scan may restore the device before reboot.'
} catch {
    $taskResult.status = 'failed'
    $taskResult.error = $_.Exception.Message
} finally {
    $taskResult | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $taskStatePath -Encoding utf8
}
