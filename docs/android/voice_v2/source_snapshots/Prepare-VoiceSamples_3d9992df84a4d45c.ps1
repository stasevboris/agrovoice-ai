param([string]$ReportFolder='docs/android/voice_v2/audio_samples')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Speech
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskFolder=Join-Path $taskRoot $ReportFolder
New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
$taskCases=@(
    @{id='camera';text='сделай фото';action='CAMERA';value=''},
    @{id='gallery';text='открой галерею';action='GALLERY';value=''},
    @{id='analyze';text='проверь фото';action='ANALYZE';value=''},
    @{id='small';text='быстрая модель';action='MODEL';value='yolo26n'},
    @{id='large';text='большая модель';action='MODEL';value='yolo26s'},
    @{id='old';text='старая модель';action='MODEL';value='yolo11n'},
    @{id='all';text='все культуры';action='CROP';value='all'},
    @{id='potato';text='картофель';action='CROP';value='Potato'},
    @{id='tomato';text='томаты';action='CROP';value='Tomato'},
    @{id='cucumber';text='огурцы';action='CROP';value='Cucumber'},
    @{id='cancel';text='отмена';action='CANCEL';value=''},
    @{id='negative_camera';text='не делай фото';action='';value=''},
    @{id='negative_gallery';text='не открывай галерею';action='';value=''},
    @{id='unrelated';text='сегодня хорошая погода';action='';value=''}
    @{id='number_one';text='фото номер один';action='PHOTO_NUMBER';value='1';context='gallery'},
    @{id='number_three';text='фото номер три';action='PHOTO_NUMBER';value='3';context='gallery'},
    @{id='number_21';text='номер двадцать один';action='PHOTO_NUMBER';value='21';context='gallery'},
    @{id='number_125';text='фото номер сто двадцать пять';action='PHOTO_NUMBER';value='125';context='gallery'},
    @{id='number_1000';text='фото номер одна тысяча';action='PHOTO_NUMBER';value='1000';context='gallery'},
    @{id='first';text='первое фото';action='FIRST_PHOTO';value='';context='gallery'},
    @{id='last';text='последнее фото';action='LAST_PHOTO';value='';context='gallery'},
    @{id='next';text='следующее фото';action='NEXT_PHOTO';value='';context='gallery'},
    @{id='previous';text='предыдущее фото';action='PREVIOUS_PHOTO';value='';context='gallery'},
    @{id='confirm';text='подтвердить';action='CONFIRM';value='';context='gallery'},
    @{id='negative_confirm';text='не подтверждать';action='';value='';context='gallery'},
    @{id='negative_number';text='не выбирай фото';action='';value='';context='gallery'},
    @{id='gallery_unrelated';text='сегодня хорошая погода';action='';value='';context='gallery'}
)
$taskFormat=New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
$taskRecords=@()
$taskSynth=New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    foreach($taskVoice in @('Microsoft Irina Desktop','Microsoft Pavel')){
        $taskSynth.SelectVoice($taskVoice)
        foreach($taskCase in $taskCases){
            $taskSpeaker=if($taskVoice.Contains('Irina')){'irina'}else{'pavel'}
            $taskName=$taskSpeaker+'_'+$taskCase.id+'.wav'
            $taskPath=Join-Path $taskFolder $taskName
            $taskSynth.SetOutputToWaveFile($taskPath,$taskFormat)
            $taskSynth.Speak($taskCase.text)
            $taskSynth.SetOutputToNull()
            $taskRecords+=@{file=$taskName;text=$taskCase.text;action=$taskCase.action;value=$taskCase.value;context=if($taskCase.context){$taskCase.context}else{'main'};synthetic_voice=$taskVoice;sha256=(Get-FileHash -LiteralPath $taskPath).Hash.ToLowerInvariant()}
        }
    }
} finally {$taskSynth.Dispose()}
$taskRecords | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskFolder 'cases.json') -Encoding utf8
@{source='Locally generated Windows System.Speech samples; no human recordings';pcm='16000Hz mono 16-bit';count=$taskRecords.Count;scope='Technical command check, not field speech accuracy or WER';created_utc=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskFolder 'provenance.json') -Encoding utf8
Write-Output ('Prepared '+$taskRecords.Count+' synthetic WAV samples')
