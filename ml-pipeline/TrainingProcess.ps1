function Get-TrainingProcess($State) {
    $taskProcess = Get-Process -Id $State.pid -ErrorAction SilentlyContinue
    $taskExpected = if ($State.backend -eq 'wsl') { 'wsl' } else { 'python' }
    if (!$taskProcess -or $taskProcess.ProcessName -ne $taskExpected) { return $null }
    try {
        $taskStarted = $taskProcess.StartTime.ToUniversalTime()
        $taskRecorded = if ($State.started_utc -is [DateTime]) {
            $State.started_utc.ToUniversalTime()
        } else {
            [DateTimeOffset]::Parse([string]$State.started_utc, [Globalization.CultureInfo]::InvariantCulture).UtcDateTime
        }
        if ([Math]::Abs(($taskStarted - $taskRecorded).TotalSeconds) -gt 10) { return $null }
    } catch { return $null }
    return $taskProcess
}
