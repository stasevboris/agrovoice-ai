# AgroVoice AI

Дипломный проект: офлайн-приложение Android для осмотра растений с голосовым управлением.
Фото листа → предварительная оценка культуры и состояния на телефоне → голосовые команды и озвучка → журнал осмотров. Текущий APK использует старые 30 классов. Набор на 49 классов и 15 культур подготовлен, обучение и интеграция новой модели ещё впереди. В плане — распознавание неизвестных объектов, свободный русский голос, справочник СЗР по Госреестру РБ и сервер синхронизации.

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

## Сборка APK

Нужны JDK 25 (закреплён в `gradle-daemon-jvm.properties`) и Android SDK
(путь в `android-app/local.properties`). Используется Gradle Wrapper проекта.
Перед сборкой из корня проверь локальные ассеты:

```powershell
python android-app\fetch_assets.py --check
```

Затем:

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Модели (`app/src/main/assets/models/*.onnx`) и Vosk в git не хранятся.
`model_release.json` пока содержит пустой список файлов: чистый клон не получает
текущие модели автоматически. На рабочем ПК они уже есть. Для другого компьютера
нужна локальная папка с соответствующими ONNX либо завершённый G-05 с опубликованными
разрешёнными ассетами. Команды из корня репозитория:

```powershell
python android-app\fetch_assets.py --local-dir E:\models  # существующая папка с нужными ONNX; Vosk при отсутствии скачивается
python android-app\fetch_assets.py --check                # проверка без скачивания
```

План релиза — только разрешённые итоговые модели через `prepare_model_release.py`.
Текущая смешанная debug-сборка содержит локальные эксперименты, включая DEIMv2;
она не является готовым публичным релизом.

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
