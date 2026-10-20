# Локальная подготовка Android

`field` содержит текущую YOLO26s, `lab` — все модели исходного манифеста.
Порядок и подписи классов берутся из `app/src/main/assets/models/manifest.json`;
allcrops не включается автоматически. Field скрывает смену модели, порога и
сравнение. Это разделение интерфейса, а не новая проверка качества распознавания.
Неизвестные виды/предметы по-прежнему могут получать неверный ответ.

## Ассеты и чистый checkout

Python 3.12+, JDK 25 и Android SDK должны быть установлены заранее. В
`local.properties` задаётся `sdk.dir`, этот локальный файл не коммитится.
`fetch_assets.py` проверяет SHA-256 оригинальных моделей, русского Vosk,
Denis и закреплённого sherpa runtime. Можно передать каталог `android-app`
другого checkout с уже подготовленными разрешёнными ассетами:

```powershell
python -X utf8 android-app/fetch_assets.py --flavor field --offline --local-dir F:/agrovoice-ai/android-app
cd android-app
.\gradlew.bat testFieldDebugUnitTest assembleFieldDebug
```

Для lab заменить `field` на `lab`, задачи — `testLabDebugUnitTest assembleLabDebug`.
`--offline` запрещает сетевое скачивание. Без него используются только источники
из манифестов и разрешённый список GitHub Release; пустой список релиза не
заменяется фиктивными весами. DEIMv2 остаётся только локальным экспериментом.
Ассеты исходного дерева общие; в APK включаются только выбранные файлы.
Упаковка проверяет именно сгенерированные ассеты и не создаёт APK без моделей.

[Проверка чистого checkout от 20.10.2026](../docs/android/reproducible_build_2026-10-20/report.json):
оба варианта собраны после локального импорта; содержимое APK сверено по SHA
манифестов. Проверка использовала существующие JDK/SDK и кеш зависимостей.
Она не подтверждает побайтовую идентичность сборок на разных компьютерах
или доступность ещё не опубликованного релиза моделей.

Для CI без моделей достаточно `fetch_assets.py --runtime-only`, затем
`testFieldDebugUnitTest testLabDebugUnitTest lintFieldDebug lintLabDebug`.
Это проверка кода, не работающий APK и не проверка реального inference.

## Подпись и R8

```powershell
python -X utf8 android-app/prepare_local_signing.py
cd android-app
.\gradlew.bat assembleFieldRelease assembleLabRelease -PreleaseSigningProperties=C:/Users/Boris/.agrovoice/signing/release.properties
```

Скрипт создаёт ключ и свойства вне репозитория; существующую подпись не заменяет.
Путь можно задать `--directory`. Пароли не выводятся в консоль и не передаются
аргументами keytool. На Windows каталог наследует права профиля; храните его
и резервную копию в закрытом пользовательском хранилище.
R8 включён для release; JNI-связи ONNX/Vosk/sherpa/JNA сохранены правилами.
Без `releaseSigningProperties` release собирается неподписанным.

Field сохраняет пакет `com.agronomy.phytomonitor`, lab использует
`com.agronomy.phytomonitor.lab`. `-PisolateApp=true` добавляет `.verification`
для debug и release обоих вариантов. Для изолированного пакета также применимы
`assembleFieldDebugAndroidTest` / `assembleLabDebugAndroidTest`.

Новый release-ключ **не позволяет обновить установленную debug-сборку**:
для обновления нужны тот же пакет, не меньший versionCode и прежний сертификат.
Не удалять установленное приложение ради смены подписи. Проверять сертификаты
через `apksigner verify --print-certs`, перенос пользовательского архива —
отдельным согласованным сценарием. Подготовка подписи не разрешает установку
на телефон, push или публикацию; внешний выпуск моделей идёт только через
`prepare_model_release.py`.
