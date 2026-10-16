"""Write the completed voice feature report from installed-APK and Android test evidence."""
import hashlib, json, zipfile
from datetime import datetime, timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REPORT=ROOT/'docs/android/voice_v1'
def read(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def sha(path):
    with Path(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    apk=read(ROOT/'artifacts/apk/current_model_lab.json')
    assert apk['status']=='verified' and apk['installed'] and apk['version_code']==9
    source=read(REPORT/'model_provenance.json')
    speech=read(REPORT/'recognition_results.json')
    assert speech['accepted_commands']==speech['expected_commands']==22
    assert speech['false_actions_on_negative_or_unrelated']==0 and len(speech['samples'])==28
    assert 'OK (2 tests)' in (REPORT/'instrumentation.txt').read_text(encoding='utf-8-sig')
    assets=ROOT/'android-app/app/src/main/assets'
    assert read(assets/'models/manifest.json')==read(ROOT/'docs/experiments/yolo26s_focus_v1/pilot_apk_manifest_0.8.json')
    with zipfile.ZipFile(ROOT/apk['apk']) as bundle:
        assert hashlib.sha256(bundle.read('assets/voice/manifest.json')).hexdigest()==sha(assets/'voice/manifest.json')
        for row in source['files']:
            assert hashlib.sha256(bundle.read('assets/voice/ru/'+row['path'])).hexdigest()==row['sha256']
    names=['android-app/app/src/main/java/com/agronomy/phytomonitor/FieldDemoActivity.kt',
           'android-app/app/src/main/java/com/agronomy/phytomonitor/voice/VoiceCommands.kt',
           'android-app/app/src/main/java/com/agronomy/phytomonitor/voice/OfflineVoiceController.kt',
           'android-app/app/src/test/java/com/agronomy/phytomonitor/VoiceCommandsTest.kt',
           'android-app/app/src/androidTest/java/com/agronomy/phytomonitor/VoiceRecognitionTest.kt',
           'android-app/app/src/androidTest/java/com/agronomy/phytomonitor/VoiceUiTest.kt',
           'ml-pipeline/prepare_voice_model.py','ml-pipeline/Prepare-VoiceSamples.ps1','ml-pipeline/build_voice_report.py']
    snapshots=REPORT/'source_snapshots';snapshots.mkdir(exist_ok=True)
    sources=[]
    for name in names:
        path=ROOT/name;digest=sha(path);snapshot=snapshots/(path.stem+'_'+digest[:16]+path.suffix)
        snapshot.write_bytes(path.read_bytes())
        sources.append({'source':name,'sha256':digest,'snapshot':str(snapshot.relative_to(ROOT))})
    evidence={'status':'passed','updated_utc':datetime.now(timezone.utc).isoformat(),'apk':apk,
              'model':source,'samples':{'accepted':22,'expected':22,'negative_or_unrelated':6,'false_actions':0,
                                       'voices':['Microsoft Irina Desktop','Microsoft Pavel'],'real_field_speech_accuracy':None},
              'ui_test':'Actual microphone start/stop, help dialog, stop on background and return without automatic listening',
              'camera_capture_by_voice':'Opens the existing system camera; shutter remains in the camera app',
              'sources':sources,'all_eight_plant_models_unchanged':True,
              'constraints':'CPU only; active GPU training recipe/data unchanged; no audio recording files or network in production'}
    (REPORT/'verification.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    text=f'''# Голосовые команды AgroVoice — 4 октября 2026

По выбору Бориса добавлены **фиксированные русские команды без интернета**. APK{apk['version']} установлен и проверен на Redmi Note7. Все восемь моделей растений сохранены с прежними SHA; YOLO26n12 и пилотYOLO26s03 остаются в приложении. Десять обученийS продолжаются отдельно. Журнал осмотров с заметками в этом изменении не реализовывался: пользователь выбрал голос как первый приоритет.

## Как пользоваться

Открой AgroVoice, нажми **«Голосовая команда»**, дождись **«Слушаю»** и произнеси одну фразу. При необходимости разреши микрофон. Можно отменить кнопкой «Остановить микрофон» или словом «стоп». «Фразы» открывает подсказку. Команда исполняется после завершённой фразы; промежуточный текст ничего не запускает.

| Пример фразы | Действие |
|---|---|
| Сделай фото / Сделать фото | Открывает штатную камеру. Нажать затвор нужно в приложении камеры; полностью бесконтактной съёмки пока нет |
| Открой галерею | Открывает выбор фото; после выбора выполняется анализ |
| Проверь фото / Проверь ещё раз | Повторяет анализ текущего снимка; без снимка показывает понятную подсказку |
| Быстрая модель / Маленькая модель | YOLO26n; текущий снимок проверяется выбранной моделью |
| Большая модель | YOLO26s; название не обещает безошибочный диагноз |
| Старая модель | YOLO11n |
| Картофель / Выбери картофель | Фильтр картофеля |
| Томаты / Выбери томаты | Фильтр томата |
| Огурцы / Выбери огурцы | Фильтр огурца, состояние пока неизвестно |
| Все культуры | Убирает фильтр культуры |
| Стоп / Отмена | Отменяет текущую голосовую команду |

Поддерживаются перечисленные выражения и явно записанные синонимы/формы слов. Это ещё не понимание произвольной естественной речи. Остальные пять моделей по-прежнему выбираются кнопками. Изменение культуры фильтрует результаты, отдельным диагнозом огуречных болезней не является.

## Микрофон и работа приложения

Микрофон включается только после нажатия и разрешения. Одна команда за включение; таймаут12 секунд. После результата/отмены или ухода приложения в фон AudioRecord освобождается. На время слушания остальные действия заблокированы, чтобы голос и ручное действие не запускались одновременно. Перед открытием камеры или галереи микрофон закрывается.

Звук обрабатывается в памяти и не сохраняется; история аудио не создаётся. Модель включена в APK, сетевое соединение и сервисGoogle не нужны. В APK добавлено разрешение RECORD_AUDIO; разрешение INTERNET по-прежнему отсутствует. При отказе от микрофона ручные кнопки остаются доступны.

## Стек и происхождение

Vosk Android0.3.75 + JNA5.18.1; русская **vosk-model-small-ru-0.22**, лицензияApache2.0. Пакет скачан с [официального каталога моделей](https://alphacephei.com/vosk/models), интеграция сверена с [Android-примером Vosk](https://github.com/alphacep/vosk-android-demo). Версии закреплены в Gradle, обучение и Python/ROCm не менялись.

Архив: {source['archive_bytes']} байт, SHA256 `{source['archive_sha256']}`. Распакованные файлы: {source['unpacked_bytes']} байт. При первом использовании они копируются во внутреннюю папку приложения с проверкой SHA каждого файла; последующие включения используют готовую копию. APK: **{apk['bytes']} байт**, SHA256 `{apk['sha256']}`. Это примерно+68МБ относительно0.8, плюс около91МБ внутренней копии голосовой модели. Голосовая и визуальная модели — разные компоненты.

Список файлов/контрольные суммы/источник: [model_provenance.json](voice_v1/model_provenance.json). ЛицензииVosk иJNA включены в assets/licenses. Готовый [APK0.9](../../artifacts/apk/AgroVoice-model-lab-0.9.apk); исходныйAPK0.8 сохранён.

## Что проверено

- Gradle сборка и unit-тесты: отрицательные/несвязанные фразы не превращаются в действие, регистр/пробелы/ё нормализуются, модели и культуры сопоставляются явно.
- На настоящем Note7 модель декодировала28 WAV-файлов, локально синтезированных двумя русскими голосамиWindows. **22 из22 команд** приняты; **6 отрицательных/несвязанных фраз —0 действий**. Это проверка всей ASR-цепочки на Android CPU, а не подача готового текста вместо распознавания.
- Compose/UI-тест на телефоне: начало реального слушания микрофона, ручная остановка, доступность камеры после остановки, окно фраз, уход в фон во время слушания и возврат без автоматического включения микрофона.
- НоваяAPK повторно прошла Android/desktop паритет всех восьми визуальных режимов:24 результата на трёх фото. Численные входы/выходы сохранены отдельно; прежнее доказательство0.8 архивировано.
- SHA упакованных голосовых файлов и manifest проверены относительно исходного архива. Восемь manifest-моделей растений совпадают с0.8 полностью. Прежние десять серий скорости визуальных моделей не проводились заново ради голосовой панели: код графов/предобработки не менялся, новые замеры необходимы после финального выбораS.

Итог: [verification.json](voice_v1/verification.json), [распознавание каждого WAV](voice_v1/recognition_results.json), [инструментальные тесты](voice_v1/instrumentation.txt), [источник синтезированных образцов](voice_v1/audio_samples/provenance.json). Точные версии нового кода сохранены в voice_v1/source_snapshots.

## Ограничения и следующая проверка

Синтезированные голоса — технический контроль, **не100% распознавания людей и не измерениеWER в поле**. Микрофонный тракт начал/остановил запись, но твою речь, расстояние, ветер и шум ещё нужно проверить. Если команда не сработала, смотри строку «Услышал» и повтори фразу из списка. Неизвестный текст, отрицательная команда и средняя confidence слов ниже0.65 не исполняются. Это консервативный порог ASR, не вероятность правильного диагноза растения.

SamsungA56/Redmi9T пока не испытаны. Полное понимание естественных формулировок, голосовой журнал и собственная камера с голосовым затвором остаются следующими этапами. Изменения и проверки внесены в PROJECT_GUIDE.md.
'''
    (ROOT/'docs/android/VOICE_COMMANDS_2026-10-04.md').write_text(text,encoding='utf-8')
    statefile=ROOT/'docs/session/accuracy_work_state.json';state=read(statefile)
    state['voice_feature']={'status':'verified','apk':apk['apk'],'sha256':apk['sha256'],
                            'report':'docs/android/VOICE_COMMANDS_2026-10-04.md','all_eight_examples_preserved':True,
                            'synthetic_commands':'22/22; 0 actions on six negative/unrelated samples','field_speech_accuracy':None}
    statefile.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
    print({'voice':'verified','apk':apk['version'],'commands':'22/22','negative_false_actions':0})
if __name__=='__main__':main()
