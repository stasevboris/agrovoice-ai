param([string]$ReportFolder='docs/android/voice_v3/audio_samples',[switch]$IncludeM,[switch]$IncludeLab)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Speech
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskFolder=Join-Path $taskRoot $ReportFolder
New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
$taskCases=@(
    @{id='camera_now';text='сделай фото сразу';action='CAMERA_NOW';value=''},
    @{id='listen_on';text='слушай постоянно';action='LISTEN_ON';value=''},
    @{id='listen_off';text='выключи голос';action='LISTEN_OFF';value=''},
    @{id='stop';text='стоп';action='LISTEN_OFF';value=''},
    @{id='help';text='помощь';action='HELP';value=''},
    @{id='close_help';text='понятно';action='CLOSE_HELP';value=''},
    @{id='back';text='назад';action='BACK';value=''},
    @{id='threshold25';text='порог двадцать пять процентов';action='THRESHOLD';value='25'},
    @{id='threshold_up';text='увеличь порог';action='THRESHOLD_UP';value=''},
    @{id='threshold_down';text='уменьши порог';action='THRESHOLD_DOWN';value=''},
    @{id='scroll_down';text='прокрути вниз';action='SCROLL';value='down'},
    @{id='scroll_up';text='прокрути вверх';action='SCROLL';value='up'},
    @{id='top';text='в начало';action='SCROLL';value='top'},
    @{id='bottom';text='в конец';action='SCROLL';value='bottom'},
    @{id='exit';text='закрой приложение';action='EXIT';value=''},
    @{id='negative_off';text='не выключай голос';action='';value=''},
    @{id='model1';text='модель один';action='MODEL';value='yolo11n'},
    @{id='model2';text='модель два';action='MODEL';value='yolo26n'},
    @{id='model3';text='модель три';action='MODEL';value='mobilenet_v3_large'},
    @{id='model4';text='модель четыре';action='MODEL';value='efficientnet_b0'},
    @{id='model5';text='модель пять';action='MODEL';value='masked_mobilenet_v3_large'},
    @{id='model6';text='модель шесть';action='MODEL';value='distilled_mobilenet_v3_large'},
    @{id='model7';text='модель семь';action='MODEL';value='yolo26s'},
    @{id='model8';text='модель восемь';action='MODEL';value='deimv2_n'},
    @{id='camera_confirm';text='подтвердить';action='CONFIRM';value='';context='camera'},
    @{id='camera_now_context';text='сделай фото сразу';action='CAMERA_NOW';value='';context='camera'},
    @{id='camera_cancel';text='отмена';action='CANCEL';value='';context='camera'},
    @{id='camera_negative';text='не подтверждать';action='';value='';context='camera'},
    @{id='camera';text='сделай фото';action='CAMERA';value=''},
    @{id='gallery';text='открой галерею';action='GALLERY';value=''},
    @{id='analyze';text='проверь фото';action='ANALYZE';value=''},
    @{id='small';text='быстрая модель';action='MODEL';value='yolo26n'},
    @{id='large';text='большая модель';action='MODEL';value='yolo26s'},
    @{id='old';text='старая модель';action='MODEL';value='yolo11n'},
    @{id='all';text='все культуры';action='CROP';value='all'},
    @{id='potato';text='картофель';action='CROP';value='potato'},
    @{id='tomato';text='томаты';action='CROP';value='tomato'},
    @{id='cucumber';text='огурцы';action='CROP';value='cucumber'},
    @{id='cancel';text='отмена';action='CANCEL';value=''},
    @{id='negative_camera';text='не делай фото';action='';value=''},
    @{id='negative_gallery';text='не открывай галерею';action='';value=''},
    @{id='unrelated';text='сегодня хорошая погода';action='';value=''}
    @{id='number_one';text='фото номер один';action='PHOTO_NUMBER';value='1';context='gallery'},
    @{id='number_three';text='фото номер три';action='PHOTO_NUMBER';value='3';context='gallery'},
    @{id='bare_three';text='три';action='PHOTO_NUMBER';value='3';context='gallery'},
    @{id='fourth';text='четвёртое фото';action='PHOTO_NUMBER';value='4';context='gallery'},
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
if($IncludeLab){
    $taskCases+=@(
        @{id='lab_note';text='добавь заметку';action='NOTE';value=''},
        @{id='lab_journal';text='открой журнал';action='JOURNAL';value=''},
        @{id='lab_save';text='сохрани осмотр';action='SAVE_INSPECTION';value=''},
        @{id='lab_correct';text='результат верный';action='ASSESS';value='correct'},
        @{id='lab_incorrect';text='результат неверный';action='ASSESS';value='incorrect'},
        @{id='lab_unsure';text='не уверен';action='ASSESS';value='uncertain'},
        @{id='lab_compare';text='сравни модели';action='COMPARE';value=''},
        @{id='lab_read';text='прочитай результат';action='SPEAK_RESULT';value=''},
        @{id='lab_hide';text='убери рамки';action='HIDE_BOXES';value=''},
        @{id='lab_show';text='покажи рамки';action='SHOW_BOXES';value=''},
        @{id='lab_negative';text='не сохраняй осмотр';action='';value=''},
        @{id='note_camera';text='сделай фото';action='';value='';context='note'},
        @{id='note_phrase';text='на этом кусте три листа с пятнами';action='';value='';context='note'},
        @{id='note_end';text='закончить заметку';action='';value='';context='note'}
    )
}
if($IncludeM){
    $taskManifest=Get-Content -LiteralPath (Join-Path $taskRoot 'android-app/app/src/main/assets/models/manifest.json') -Raw | ConvertFrom-Json
    $taskM=@($taskManifest.models | Where-Object {$_.id -eq 'yolo26m'})
    if($taskManifest.models.Count -ne 9 -or $taskM.Count -ne 1){throw 'Prepare model9 speech only after packaging the real ninth M graph.'}
    $taskDigest=(Get-FileHash -LiteralPath (Join-Path $taskRoot ('android-app/app/src/main/assets/'+$taskM[0].asset))).Hash.ToLowerInvariant()
    if($taskDigest -ne $taskM[0].sha256){throw 'M graph SHA mismatch.'}
    $taskCases+=@{id='model9';text='модель девять';action='MODEL';value='yolo26m'}
}
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
            $taskRecords+=@{id=$taskCase.id;file=$taskName;text=$taskCase.text;action=$taskCase.action;value=$taskCase.value;context=if($taskCase.context){$taskCase.context}else{'main'};synthetic_voice=$taskVoice;sha256=(Get-FileHash -LiteralPath $taskPath).Hash.ToLowerInvariant()}
        }
    }
} finally {$taskSynth.Dispose()}
$taskRecords | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskFolder 'cases.json') -Encoding utf8
@{source='Locally generated Windows System.Speech samples; no human recordings';pcm='16000Hz mono 16-bit';count=$taskRecords.Count;scope='Technical command check, not field speech accuracy or WER';created_utc=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskFolder 'provenance.json') -Encoding utf8
Write-Output ('Prepared '+$taskRecords.Count+' synthetic WAV samples')
