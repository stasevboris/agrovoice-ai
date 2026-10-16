"""Report 0.10 honestly: a built candidate is not an installed, phone-verified APK."""
import hashlib,json,zipfile,xml.etree.ElementTree as ET
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FOLDER=ROOT/'docs/android/voice_v2'
def read(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def sha(path):
    with Path(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def optional(name):
    path=FOLDER/name
    return read(path) if path.is_file() else None
def main():
    FOLDER.mkdir(parents=True,exist_ok=True)
    apkpath=ROOT/'artifacts/apk/AgroVoice-model-lab-0.10.apk'
    assert apkpath.is_file()
    digest=sha(apkpath)
    current=read(ROOT/'artifacts/apk/current_model_lab.json')
    installed=current.get('version_code')==10 and current.get('installed') and current.get('sha256')==digest
    provenance=read(ROOT/'docs/android/voice_v1/model_provenance.json')
    assets=ROOT/'android-app/app/src/main/assets'
    original=read(ROOT/'docs/experiments/yolo26s_focus_v1/pilot_apk_manifest_0.8.json')
    assert read(assets/'models/manifest.json')==original
    graphs=[]
    with zipfile.ZipFile(apkpath) as bundle:
        assert json.loads(bundle.read('assets/models/manifest.json'))==original
        for model in original['models']:
            for stage in [model]+([model['classifier']] if 'classifier' in model else []):
                assert hashlib.sha256(bundle.read('assets/'+stage['asset'])).hexdigest()==stage['sha256']
                graphs.append({'asset':stage['asset'],'sha256':stage['sha256']})
        for row in provenance['files']:
            assert hashlib.sha256(bundle.read('assets/voice/ru/'+row['path'])).hexdigest()==row['sha256']
    unit=ROOT/'android-app/app/build/test-results/testDebugUnitTest/TEST-com.agronomy.phytomonitor.VoiceCommandsTest.xml'
    units=ET.parse(unit).getroot();assert units.get('failures')=='0' and units.get('errors')=='0'
    speech=optional('recognition_results.json');camera=optional('camera_flow.json');gallery=optional('gallery_flow.json')
    log=(FOLDER/'instrumentation.txt').read_text(encoding='utf-8-sig') if (FOLDER/'instrumentation.txt').exists() else ''
    tested=bool(installed and current.get('status')=='verified' and camera and camera.get('status')=='passed' and gallery and gallery.get('status')=='passed'
                and speech and speech['false_actions_on_negative_or_unrelated']==0 and speech['accepted_commands']==speech['expected_commands'] and 'OK (5 tests)' in log)
    sources=[];snapshots=FOLDER/'source_snapshots';snapshots.mkdir(exist_ok=True)
    names=['FieldDemoActivity.kt','voice/VoiceCommands.kt','voice/OfflineVoiceController.kt','photos/LocalPhotoGallery.kt','photos/PlantCamera.kt','photos/NumberedGallery.kt']
    paths=[ROOT/'android-app/app/src/main/java/com/agronomy/phytomonitor'/n for n in names]+[
        ROOT/'android-app/app/src/main/AndroidManifest.xml',ROOT/'android-app/app/build.gradle.kts',
        ROOT/'android-app/app/src/test/java/com/agronomy/phytomonitor/VoiceCommandsTest.kt',
        ROOT/'android-app/app/src/androidTest/java/com/agronomy/phytomonitor/PhotoVoiceFlowTest.kt',
        ROOT/'android-app/app/src/androidTest/java/com/agronomy/phytomonitor/VoiceRecognitionTest.kt',
        ROOT/'android-app/app/src/androidTest/java/com/agronomy/phytomonitor/VoiceUiTest.kt',
        ROOT/'ml-pipeline/Prepare-VoiceSamples.ps1',ROOT/'ml-pipeline/prepare_voice_model.py',
        ROOT/'ml-pipeline/Test-VoiceFlowPhone.ps1',Path(__file__)]
    for path in paths:
        source_sha=sha(path);snapshot=snapshots/(path.stem+'_'+source_sha[:16]+path.suffix)
        snapshot.write_bytes(path.read_bytes())
        sources.append({'file':str(path.relative_to(ROOT)),'sha256':source_sha,'snapshot':str(snapshot.relative_to(ROOT))})
    evidence={'status':'verified' if tested else 'assembled_pending_phone_verification','updated_utc':datetime.now(timezone.utc).isoformat(),
              'apk':'artifacts/apk/AgroVoice-model-lab-0.10.apk','apk_sha256':digest,'bytes':apkpath.stat().st_size,'installed':bool(installed),
              'unit_tests':dict(units.attrib),'speech':speech,'real_camera':camera,'gallery_flow':gallery,
              'plant_graphs_unchanged_from_0_8':graphs,'speech_model_archive_sha256':provenance['archive_sha256'],
              'sources':sources,'field_speech_accuracy':None,'gpu_training_changes':False}
    (FOLDER/'verification.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    (ROOT/'artifacts/apk/candidate_model_lab_0.10.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    status='Установлен и проверен на Note7.' if tested else 'APK собран; установка и новые проверки на телефоне ещё не завершены. Не считать сборку проверенным результатом.'
    acoustic=(f"{speech['accepted_commands']}/{speech['expected_commands']} команд; ложных действий на отрицательных/посторонних фразах {speech['false_actions_on_negative_or_unrelated']}." if speech else 'Для новых команд ещё нет Android-результата. Прежняя0.9 имела22/22 на синтезированных образцах.')
    text=f'''# Настоящая съёмка и голосовой выбор галереи — APK0.10

{status}

Борис уточнил поведение: «сделай фото» должна делать снимок; галерея должна позволять назвать номер, увидеть снимок и подтвердить его голосом. [APK0.10](../../artifacts/apk/AgroVoice-model-lab-0.10.apk): {apkpath.stat().st_size} байт, SHA256 `{digest}`. Предыдущая [0.9](../../artifacts/apk/AgroVoice-model-lab-0.9.apk) и её [отчёт](VOICE_COMMANDS_2026-10-04.md) сохранены как история.

## Съёмка

На основном экране нажми «Голосовая команда», дождись «Слушаю» и скажи **«сделай фото»**. AgroVoice открывает собственный предпросмотр задней камеры, ждёт первого кадра и примерно0.4с для начальной экспозиции, затем вызывает затвор автоматически. Второе нажатие не требуется. Разрешение камеры Android при первом запуске всё равно нужно подтвердить. Снимок JPEG сохраняется в **DCIM/AgroVoice**, становится доступен обычной галерее и импортируется в прежнюю цепочку анализа растений.

Ручная кнопка «Сделать фото» открывает предпросмотр с кнопкой «Снять сейчас». Уход в фон закрывает камеру и отменяет ещё не начатую автоматическую съёмку. Автоматический затвор не повторяется при перерисовке экрана. Фокусировка и качество в поле отдельно не измерены; начальная задержка не обещает резкий кадр при любых условиях.

## Галерея: открыть → номер → просмотр → подтвердить

1. Скажи **«открой галерею»**. Откроется галерея внутри AgroVoice с номерами на миниатюрах; Android может попросить доступ к фотографиям.
2. Скажи **«фото номер три»**, «номер двадцать один» или просто «три». Фото показывается крупно и выделяется. На этом шаге снимок ещё не импортируется и не анализируется.
3. Если снимок подходит, скажи **«подтвердить»**. Только после этого приложение копирует выбранное фото и запускает анализ. Если нет — назови другой номер; «следующее фото» / «предыдущее фото» переключают соседние номера.
4. **«Первое фото»** показывает самое новое, **«последнее фото»** — самое старое. «Отмена» / «стоп» закрывают выбор без импорта. Есть и ручные миниатюры, ввод номера, просмотр и кнопка подтверждения.

Порядок: время съёмкиMediaStore.DATE_TAKEN по убыванию; если его нет — DATE_ADDED. При равенстве: время добавления, затемID по убыванию. Номера начинаются с1 и закрепляются в снимке списка до закрытия/повторного открытия галереи: новое фото, появившееся во время выбора, не сдвигает текущие номера. При корректных датах новый снимок камеры имеет№1. В галерее показаны все доступные локальные изображения, не только папка камеры; облачные фотографии без локального файла сюда не входят.

Android14+ позволяет дать доступ только к выбранным фото: в таком случае приложение явно пишет, что список и номера относятся к доступной части. Для порядка всей галереи нужно разрешить все фотографии. Ничего не выдаём за всю галерею при частичном доступе. При отказе остаётся «Системный выбор фото» с обычнымPhoto Picker; в чужом системном интерфейсе наш голосовой выбор по номеру недоступен.

## Голосовой диалог

После голосового открытия галереи повторно нажимать микрофон между номером и подтверждением не нужно. Каждая законченная фраза останавливает запись; следующий сеанс включается автоматически примерно через0.6с, пока открыт этот выбор. Говори после «Слушаю». Безопасный общий предел — **2 минуты**; затем можно включить голос снова кнопкой. «Остановить микрофон», подтверждение, отмена и уход в фон выключают диалог. При возвращении из фона микрофон самостоятельно не включается. Вне галереи по-прежнему одна команда за нажатие.

Русские числительные и явно заданные формы распознаются фиксированным парсером; отрицания и произвольные предложения с числом не запускают действие. Для обычных библиотек до10000 фото строится словарь реальных номеров; для больших используется общий русский словарьVosk. Парсер поддерживает1..999999; большие библиотеки и очень длинные номера на телефоне ещё не испытаны. Порог средней confidence словASR0.65 — прежний, не оценка точности болезни.

## Стек и разрешения

К прежним Kotlin/Compose/Vosk0.3.75/JNA5.18.1 добавлены **CameraX1.4.2** (camera2/lifecycle/view) и MediaStore. Версии закреплены. [Документация затвораCameraX](https://developer.android.com/media/camera/camerax/take-photo), [частичный доступAndroid14](https://developer.android.com/about/versions/14/changes/partial-photo-video-access).

Разрешения: RECORD_AUDIO, CAMERA, READ_MEDIA_IMAGES; Android14+ READ_MEDIA_VISUAL_USER_SELECTED. НаAndroid26–32 чтение READ_EXTERNAL_STORAGE; запись WRITE_EXTERNAL_STORAGE нужна только26–28 для новогоJPEG. Нет разрешения на видео, геолокацию, INTERNET или ACCESS_NETWORK_STATE. Фото и аудио не отправляются в сеть, аудио не сохраняется. Голосовая модель и лицензии прежние; архивSHA закреплён в загрузчике, тяжёлые воспроизводимые файлы исключены изGit, manifest/лицензии сохранены.

## Проверки и пределы доказательств

Unit-тесты VoiceCommandsTest: {units.get('tests')} теста,0 ошибок/падений. Проверены отрицания, точные действия, отдельное подтверждение, номера и границы синтаксиса. ТестоваяAPK собирается. Синтетическое распознавание наAndroid: **{acoustic}** Синтезированная речь не измеряетWER людей в поле.

Новые инструментальные сценарии проверяют настоящий MediaStore/порядок/крайние номера, предварительный просмотр без импорта, невозможность подтверждения без снимка, неверный номер без сдвига выбора, точное совпадение байтов после подтверждения, начало/остановку диалога в фоне и настоящийJPEG с камеры без нажатия затвора. Диспетчер действий в этихUI-тестах вызывается напрямую, акустическое распознавание отдельно проверяет настоящаяVosk-модель наAndroidCPU. Итоговые статусы берутся только из фактических сохранённых файлов.

Все восемь визуальных графовAPK сверены поSHA с0.8; модельныйmanifest совпадает полностью. N12 и пилотS03 сохранены, выбор финальногоS до10 не выполнялся. ОбучениеS, замороженные данные, планы и защищённые скрипты не менялись; второйGPU-worker не запускался. Новая голосовая навигация не увеличивает точность модели растений.

Доказательства: [verification.json](voice_v2/verification.json), распознавание/инструментальные логи/сценарии вvoice_v2; [текущий установленныйAPK](../../artifacts/apk/current_model_lab.json). Исходники и ихSHA сохранены вvoice_v2/source_snapshots. A56/Redmi9T, ветер и реальная речь Бориса ещё не испытаны.

Продолжение после подключенияNote7: `./ml-pipeline/Test-VoiceFlowPhone.ps1`. Сценарий собирает/устанавливает0.10 и тестовуюAPK, загружает54 новыхWAV в отдельную папкуvoice_validation_v2, сохраняет пять инструментальных тестов и новую визуальную проверку. Прежние0.9-доказательства остаются на компьютере. Проверка камеры удаляет только собственный новый тестовыйJPEG из общей галереи и восстанавливает прежний рабочий снимок; фотографии пользователя не удаляются.
'''
    (ROOT/'docs/android/VOICE_GALLERY_2026-10-04.md').write_text(text,encoding='utf-8')
    statefile=ROOT/'docs/session/accuracy_work_state.json';state=read(statefile)
    state['voice_feature']={'status':evidence['status'],'apk':evidence['apk'],'sha256':digest,'installed':bool(installed),
        'report':'docs/android/VOICE_GALLERY_2026-10-04.md','previous_verified':'docs/android/voice_v1/verification.json',
        'all_eight_examples_preserved':True,'real_camera':camera,'gallery_confirmation':gallery,
        'field_speech_accuracy':None,'continuation':None if tested else 'Install0.10 when Note7 reappears; run five voice/flow instrumented tests and new recognition WAVs, visual parity, refresh report. Preserve0.9 evidence.'}
    statefile.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
    print({'voice_flow':evidence['status'],'installed':bool(installed),'apk':evidence['apk'],'sha256':digest})
if __name__=='__main__':main()
