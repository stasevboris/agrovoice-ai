# AgroVoice AI

[![Checks рабочей ветки](https://github.com/stasevboris/agrovoice-ai/actions/workflows/checks.yml/badge.svg?branch=work%2Foffline-local-sync-20261019)](https://github.com/stasevboris/agrovoice-ai/actions/workflows/checks.yml?query=branch%3Awork%2Foffline-local-sync-20261019)

Дипломный проект: офлайн-приложение Android для осмотра растений с голосовым управлением.
Фото листа → предварительная оценка культуры и состояния на телефоне → голосовые команды и озвучка → журнал осмотров. В приложении есть офлайн-справочник с черновиками; мобильный клиент синхронизирует версии осмотров и фото с локальным сервером. Текущий APK использует старые 30 классов. Для целевых 49 классов и 15 культур подготовлены данные и проведены ограниченные AMD-пилоты; интеграция новых весов, надёжный отказ на неизвестном, свободный диалог и проверенное лечение ещё впереди.

- Что делаем и в каком порядке — [docs/ROADMAP.md](docs/ROADMAP.md)
- Что есть сейчас — [docs/STATUS.md](docs/STATUS.md)
- Что читать Борису — [маршрут чтения](docs/READING_GUIDE.md), [полный каталог документов](docs/DOCUMENTATION_CATALOG.md)
- Где что лежит — [карта проекта](docs/PROJECT_MAP.md), [разделы документации](docs/README.md), [вход в ML-пайплайн](ml-pipeline/README.md)
- Проверенное состояние и риски — [полный аудит 19.10.2026](docs/audits/PROJECT_AUDIT_2026-10-19.md)
- Как запускать обучение — [практическая инструкция](docs/guides/TRAINING_PLAYBOOK.md)
- Полный технический отчёт, ограничения и направления развития — [docs/architecture/PROJECT_REPORT_2026-10-19.md](docs/architecture/PROJECT_REPORT_2026-10-19.md)
- Аудит проекта на 16.10.2026 — [docs/audits/AUDIT_AND_PLAN_2026-10-16.md](docs/audits/AUDIT_AND_PLAN_2026-10-16.md)
- Правила для ИИ-агентов — [AGENTS.md](AGENTS.md)
- Что не хранится в git и где лежит — [docs/ARCHIVE.md](docs/ARCHIVE.md)

## Состав

| Каталог | Что внутри |
|---|---|
| `android-app/` | Kotlin, Jetpack Compose, CameraX, ONNX Runtime, Vosk (офлайн-речь) |
| `ml-pipeline/` | подготовка данных, обучение YOLO (Ultralytics), экспорт ONNX; `build_allcrops_v1.py` — текущий набор, `gpu_kit/` — обучение на арендованной GPU |
| `docs/` | план, состояние, отчёты экспериментов, документы диплома (`docs/thesis/`) |
| `kb/` | версионируемый офлайн-справочник, источники, схема и валидатор |
| `server/` | локальный FastAPI/PostgreSQL, JWT и синхронизация; [запуск](server/README.md) |
| `.github/workflows/` | Python/JVM/lint без локальных весов; [сборка с ресурсами и границы CI](android-app/RELEASE.md) |

## Сборка APK

Нужны JDK 25 (закреплён в `gradle-daemon-jvm.properties`) и Android SDK
(путь в `android-app/local.properties`). Используется Gradle Wrapper проекта.
Перед сборкой из корня проверь локальные ассеты:

```powershell
python -X utf8 android-app\fetch_assets.py --flavor field --check
```

Затем:

```powershell
cd android-app
.\gradlew.bat testFieldDebugUnitTest assembleFieldDebug
```

`field` содержит YOLO26s; `lab` — все локальные модели для экспериментов.
Для lab нужны `--flavor lab` и задачи `testLabDebugUnitTest assembleLabDebug`.
Модели (`app/src/main/assets/models/*.onnx`), Vosk и голос Denis в git не хранятся.
`model_release.json` пока содержит пустой список файлов: чистый клон не получает
текущие модели автоматически. На рабочем ПК они уже есть. Для другого компьютера
нужен каталог `android-app` другого checkout с проверенными ресурсами либо
завершённый G-05 с опубликованными разрешёнными ассетами. Команды из корня
нового checkout (путь в `--local-dir` указывает на исходный checkout):

```powershell
python -X utf8 android-app\fetch_assets.py --flavor field --offline --local-dir F:/agrovoice-ai/android-app
python -X utf8 android-app\fetch_assets.py --flavor field --check
```

План релиза — только разрешённые итоговые модели через `prepare_model_release.py`.
Lab-сборка содержит локальные эксперименты, включая DEIMv2, и не является
публичным релизом. [Подробная инструкция, подпись и R8](android-app/RELEASE.md).
[Проверка чистого checkout](docs/android/reproducible_build_2026-10-20/report.json)
подтверждает локальный импорт ресурсов и сборку обоих вариантов; автоматическая
загрузка весов из опубликованного релиза пока недоступна.

## Данные и обучение

```powershell
# тесты пайплайна
.\ml-pipeline\venv\Scripts\python.exe -X utf8 -m unittest discover -s ml-pipeline\tests
# собрать набор allcrops_v1 (нужны исходники в ml-pipeline/source_candidates)
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline\build_allcrops_v1.py --overwrite
# упаковать комплект для обучения на арендованной GPU
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline\pack_gpu_kit.py
```

Инструкция к сеансу обучения — [ml-pipeline/gpu_kit/README.md](ml-pipeline/gpu_kit/README.md).

## Лицензия

Код проекта — [GNU AGPL-3.0](LICENSE). Авторы и отдельные условия данных,
библиотек и моделей — [ATTRIBUTION.md](ATTRIBUTION.md); лицензия кода не заменяет
их проверку. Карточка модели появляется в `docs/MODEL_CARD_<тег>.md` при подготовке релиза.

## Ограничения (честно)

Модель распознаёт целый лист и его состояние, а не очаг болезни; уверенность — не процент поражения. Результат предварительный и требует проверки специалистом. Числа из исходного описания темы (mAP 93,4 % и т. п.) не подтверждены; актуальные метрики — в отчётах экспериментов.

История проекта до 16.10.2026: [docs/history/](docs/history/).
