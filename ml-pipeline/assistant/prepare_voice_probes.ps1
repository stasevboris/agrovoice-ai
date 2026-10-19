param([Parameter(Mandatory=$true)][string]$Out)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$taskCases = Get-Content -Encoding UTF8 -Raw (Join-Path $PSScriptRoot 'voice_probes.json') | ConvertFrom-Json
$taskFormat = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
$taskSynth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$taskVoices = @($taskSynth.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'ru' } | ForEach-Object { $_.VoiceInfo.Name } | Sort-Object)
if ($taskVoices.Count -eq 0) { $taskSynth.Dispose(); throw 'No installed Russian SAPI voice; no voice will be installed automatically.' }
New-Item -ItemType Directory -Path $Out -ErrorAction Stop | Out-Null
$taskRecords = @()
try {
    $taskVoiceIndex = 0
    foreach ($taskVoice in $taskVoices) {
        $taskSynth.SelectVoice($taskVoice)
        $taskPrefix = 'voice' + $taskVoiceIndex
        $taskVoiceIndex++
        foreach ($taskCase in $taskCases) {
            $taskName = $taskPrefix + '_' + $taskCase.id + '.wav'
            $taskPath = Join-Path $Out $taskName
            $taskSynth.SetOutputToWaveFile($taskPath,$taskFormat)
            $taskSynth.Speak($taskCase.text)
            $taskSynth.SetOutputToNull()
            $taskRecords += @{id=$taskCase.id;file=$taskName;text=$taskCase.text;context=$taskCase.context;count=$taskCase.count;action=$taskCase.action;value=$taskCase.value;synthetic_voice=$taskVoice;sha256=(Get-FileHash -LiteralPath $taskPath).Hash.ToLowerInvariant()}
        }
    }
} finally { $taskSynth.Dispose() }
$taskRecords | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Out 'cases.json') -Encoding UTF8
@{source='Local Windows System.Speech; authored synthetic controls, not human recordings';count=$taskRecords.Count;voices=$taskVoices;pcm='16000 Hz mono PCM16'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Out 'provenance.json') -Encoding UTF8
