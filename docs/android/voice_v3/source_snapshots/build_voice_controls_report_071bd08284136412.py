"""APK0.11 evidence; keep verified 0.9/0.10 reports immutable."""
import hashlib,json,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1]
FOLDER=ROOT/'docs/android/voice_v3'
def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def sha(p):
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    current=read(ROOT/'artifacts/apk/current_model_lab.json');apk=ROOT/current['apk']
    assert current['version_code']==11 and sha(apk)==current['sha256']
    original=read(ROOT/'docs/experiments/yolo26s_focus_v1/pilot_apk_manifest_0.8.json')
    provenance=read(ROOT/'docs/android/voice_v1/model_provenance.json')
    with zipfile.ZipFile(apk) as bundle:
        assert json.loads(bundle.read('assets/models/manifest.json'))==original
        for m in original['models']:
            for stage in [m]+([m['classifier']] if m.get('classifier') else []):
                assert hashlib.sha256(bundle.read('assets/'+stage['asset'])).hexdigest()==stage['sha256']
        for row in provenance['files']:
            assert hashlib.sha256(bundle.read('assets/voice/ru/'+row['path'])).hexdigest()==row['sha256']
    units=ET.parse(ROOT/'android-app/app/build/test-results/testDebugUnitTest/TEST-com.agronomy.phytomonitor.VoiceCommandsTest.xml').getroot()
    assert units.get('failures')=='0' and units.get('errors')=='0'
    proofs={}
    for name in ['recognition_results','continuous_flow','camera_flow','gallery_flow','controls_flow']:
        p=FOLDER/(name+'.json');proofs[name]=read(p) if p.exists() else None
    speech=proofs['recognition_results'];log=(FOLDER/'instrumentation.txt').read_text(encoding='utf-8-sig') if (FOLDER/'instrumentation.txt').exists() else ''
    verified=bool(current['installed'] and current['status']=='verified' and 'OK (6 tests)' in log and all(proofs.values())
                  and all(proofs[x].get('status')=='passed' for x in proofs if x!='recognition_results')
                  and speech['accepted_commands']==speech['expected_commands'] and speech['false_actions_on_negative_or_unrelated']==0)
    snapshots=FOLDER/'source_snapshots';snapshots.mkdir(parents=True,exist_ok=True);sources=[]
    names=['FieldDemoActivity.kt','FieldDemoViewModel.kt','voice/VoiceCommands.kt','voice/OfflineVoiceController.kt','photos/LocalPhotoGallery.kt','photos/PlantCamera.kt','photos/NumberedGallery.kt']
    paths=[ROOT/'android-app/app/src/main/java/com/agronomy/phytomonitor'/n for n in names]+[
        ROOT/'android-app/app/build.gradle.kts',ROOT/'android-app/app/src/main/AndroidManifest.xml',
        ROOT/'android-app/app/src/androidTest/java/com/agronomy/phytomonitor/VoiceControlsTest.kt',
        ROOT/'android-app/app/src/androidTest/java/com/agronomy/phytomonitor/VoiceRecognitionTest.kt',
        ROOT/'android-app/app/src/test/java/com/agronomy/phytomonitor/VoiceCommandsTest.kt',
        ROOT/'ml-pipeline/Prepare-VoiceControlsSamples.ps1',ROOT/'ml-pipeline/Test-VoiceControlsPhone.ps1',Path(__file__)]
    for p in paths:
        digest=sha(p);snapshot=snapshots/(p.stem+'_'+digest[:16]+p.suffix);snapshot.write_bytes(p.read_bytes())
        sources.append({'file':str(p.relative_to(ROOT)),'sha256':digest,'snapshot':str(snapshot.relative_to(ROOT))})
    evidence={'status':'verified' if verified else 'pending_phone_verification','updated_utc':datetime.now(timezone.utc).isoformat(),
              'apk':current['apk'],'sha256':current['sha256'],'bytes':apk.stat().st_size,'installed':current['installed'],
              'unit_tests':dict(units.attrib),'proofs':proofs,'sources':sources,'visual_models_unchanged_from_0_8':True,
              'visual_evidence':'docs/android/accuracy_v1/verification.json','field_speech_accuracy':None,
              'prior_verified':'docs/android/voice_v2/verification.json','gpu_training_changes':False}
    (FOLDER/'verification.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    description=f'''# Полное голосовое управление — APK0.11, 4 октября2026

Статус: **{evidence['status']}**. APK: [{apk.name}](../../{current['apk']}); {apk.stat().st_size} байт; SHA256 `{current['sha256']}`.

## Что изменилось по новому запросу Бориса

Переключатель **«Постоянно слушать»** есть на главном экране, в камере и галерее. Он включает один непрерывный сеанс Vosk без12-секундного таймаута. Законченную фразу приложение исполняет, продолжая слушать. При переходе между экранами микрофон кратко освобождается, а грамматика меняется на нужную для экрана. Выключить: повторное нажатие переключателя, «Остановить микрофон», «стоп» или «выключи голос».

Состояние переключателя сохраняется в SharedPreferences. В фоне, при блокировке или закрытии Activity микрофон выключается. При возвращении на экран снова включается только если переключатель остался включён. Фонового сервиса записи нет. Включённый режим не блокирует ручные кнопки. Команды «стоп», помощь и навигация обрабатываются и во время анализа; смену модели/культуры/фото нужно делать после окончания анализа. При ошибке микрофона режим выключается, бесконечного перезапуска ошибки нет.

Без переключателя можно по-прежнему нажать «Голосовая команда» для одной фразы. После голосового открытия камеры/галереи диалог продолжается автоматически до подтверждения/выхода/двух минут. Переключатель снимает ограничение двух минут. Если микрофон ещё не разрешён, стандартное разрешение Android нужно выдать один раз.

## Камера: два разных сценария

- **«Сделай фото»** / «открой камеру»: собственный предпросмотр CameraX. Наведи на лист, дождись «Слушаю», скажи **«подтвердить»**. Только тогда затвор сохраняетJPEG и запускается анализ. Можно нажать «Снять сейчас» вручную.
- **«Сделай фото сразу»**: камера ждёт первого отображённого кадра и короткой задержки0.4с, затем снимает автоматически. Это задержка подготовки камеры, без дополнительного подтверждения.
- «Отмена» / «назад»: закрыть предпросмотр. Уход в фон отменяет ещё не начатую съёмку и освобождает камеру. JPEG сохраняется вDCIM/AgroVoice, виден обычной галерее. Голосовое подтверждение вызывает тот же затвор, что ручная кнопка, и не делает повторный снимок при перерисовке.

## Галерея: номера, листание и подтверждение

**«Открой галерею» → «фото номер три» / «три» → «подтвердить»**. До подтверждения показывается только предварительный просмотр; импорт и анализ не выполняются. Неверный номер сохраняет текущий выбор. «Следующее фото» / «предыдущее фото» переключают соседние снимки. Есть ручные кнопки **«Следующее» / «Предыдущее»** и горизонтальный свайп по крупному просмотру: влево следующий, вправо предыдущий. На краях списка кнопки отключены, зацикливания нет.

№1 — самый новый доступный снимок, последнее фото — самый старый. Список фиксируется до закрытия галереи. Сортировка: DATE_TAKEN, при отсутствии — DATE_ADDED; затем добавление/ID по убыванию. Частичный доступAndroid14+ явно отмечается; номера относятся только к доступным локальным изображениям. В облачном/системном PhotoPicker голосового выбора по номеру нет, для этого нужна собственная галерея AgroVoice.

## Команды остальных элементов

| Фраза | Действие |
|---|---|
| Модель один | YOLO11n |
| Модель два / быстрая модель | YOLO26n, сохранённыйseed12 |
| Модель три | YOLO26n + MobileNetV3 |
| Модель четыре | YOLO26n + EfficientNetB0 |
| Модель пять | Маска + MobileNetV3 |
| Модель шесть | Дистиллированный ученик |
| Модель семь / большая модель | YOLO26s, пока пилотseed03 |
| Модель восемь | DEIMv2-N, сохранённый пример |
| Картофель / томаты / огурцы / все культуры | Фильтр культуры |
| Порог двадцать пять [процентов] | Установить0.25, диапазон10..80% |
| Увеличь порог / уменьши порог | Шаг5 процентных пунктов, границы10..80 |
| Проверь фото / проверь ещё раз | Повторить анализ основного фото |
| Прокрути вниз / вверх, в начало / в конец | Прокрутить основной экран или сетку галереи |
| Помощь / покажи команды / список моделей | Открыть подсказку |
| Понятно / закрой подсказку | Закрыть подсказку |
| Назад / отмена | Закрыть подсказку или экран выбора |
| Слушай постоянно | Включить переключатель голосом |
| Стоп / выключи голос / выключи микрофон | Выключить режим и запись |
| Закрой приложение | Выключить микрофон и выйти |

На кнопках моделей показаны те же номера. Настройки модели/культуры/порога в камере или галерее сохраняются для следующего снимка без анализа старого фото на заднем плане.

## Реальные проверки и ограничения

Unit: {units.get('tests')} тестов,0 ошибок. Синтетический ASR: {speech['accepted_commands'] if speech else '?'} / {speech['expected_commands'] if speech else '?'} поддержанных команд; ложных действий на отрицательных/посторонних фразах {speech['false_actions_on_negative_or_unrelated'] if speech else '?'}.

Проверка Vosk выполняется настоящей моделью на AndroidCPU с114WAV двух локально синтезированных голосов MicrosoftIrina/Pavel, PCM16кГц/mono/16bit. Это техническая проверка фиксированных фраз, неWER живой речи в поле. Действия UI проверены отдельно прямым вызовом того же диспетчера; это не выдаётся за разговор с человеком через микрофон.

Новые сценарии: постоянный микрофон13.5с (дольше таймаута одной команды), включение/выключение переключателем, прекращение в фоне и корректное возвращение; доступность ручных кнопок; помощь голосом; отсутствиеJPEG до подтверждения; настоящие дваJPEG по подтверждённой/немедленной команде без нажатия затвора; последовательность экранов при постоянном режиме; настоящееMediaStore, сортировка, свайпы/кнопки/края/номер без импорта, подтверждение с побайтным сравнением; все8моделей/4культуры/порог/навигация. Итоги лежат в[verification.json](voice_v3/verification.json), логи вvoice_v3. Если статусpending — эти сценарии ещё нельзя считать успешными.

Первоначальная0.11-грамматика получила94/98 и одно ложное действие: фраза о погоде была принята за голый номер на главном экране. Исправлены допустимые действия по контексту: номера исполняются только в галерее, подтверждение только в камере/галерее; проверена причина нераспознавания прокрутки: «пролистай» / «пролисти» отсутствуют влексиконеVosk. Эти формы удалены изобещанныхкоманд; выбрана «прокрути». Сохранены первоначальные4WAV и ихошибки, замененытолькоэти4образца на явно указанную поддерживаемуюфразу; остальные110сэмплов не менялись. ПорогASR0.65 сохранён. Неудачный результат сохранён вrecognition_initial_94of98_false1.json. Отдельная ошибка пути загрузкиWAV также сохранена и исправлена; чужой старый результат не считается новой проверкой.

Все8визуальных графов иVosk-файлы внутриAPK сверены с предыдущимиSHA. Повторный Android/desktop-паритет всех8режимов: [evidence](accuracy_v1/verification.json);0.10следы сохранены отдельно. ТекущийS03 не заменялся до завершения10S. Голосовой функционал не повышает качество распознавания болезней и не меняет замороженныеML-данные/параметры.

Стек: Kotlin/Compose, CameraX1.4.2, MediaStore, Vosk0.3.75 + vosk-model-small-ru-0.22, JNA5.18.1, ONNXRuntimeAndroid1.30.0. Аудио не сохраняется/не отправляется; INTERNET/геолокации/видео разрешений нет. [VoskAndroid](https://alphacephei.com/vosk/android), [CameraX](https://developer.android.com/media/camera/camerax/take-photo), [доступAndroid14](https://developer.android.com/about/versions/14/changes/partial-photo-video-access).

Повторение: Prepare-VoiceControlsSamples.ps1 затем Test-VoiceControlsPhone.ps1. Старые отчёты0.9/0.10 сохранены. Тесты удаляют только собственные новыеJPEG поURI и возвращают прежнее рабочее фото. Реальная речь Бориса, ветер, A56/Redmi9T и большие библиотеки ещё требуют испытаний.
'''
    (ROOT/'docs/android/VOICE_CONTROLS_2026-10-04.md').write_text(description,encoding='utf-8')
    statefile=ROOT/'docs/session/accuracy_work_state.json';state=read(statefile)
    state['voice_feature']={'status':evidence['status'],'apk':current['apk'],'sha256':current['sha256'],'installed':current['installed'],
        'report':'docs/android/VOICE_CONTROLS_2026-10-04.md','previous_verified':'docs/android/voice_v2/verification.json',
        'all_eight_examples_preserved':True,'persistent_foreground_listening':True,'real_camera':proofs['camera_flow'],
        'gallery_confirmation':proofs['gallery_flow'],'field_speech_accuracy':None,
        'continuation':None if verified else 'Finish0.11 native voice/UI/camera/gallery checks and parity; keep0.10 history. Then build_voice_controls_report.py'}
    statefile.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
    print({'voice_controls':evidence['status'],'apk':current['apk'],'sha256':current['sha256']})
    if not verified:raise SystemExit('APK0.11 verification not complete; report remains pending')
if __name__=='__main__':main()
