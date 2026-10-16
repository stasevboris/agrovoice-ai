# AgroVoice AI

Дипломный проект: офлайн-приложение Android для осмотра растений с голосовым управлением.
Фото листа → нейросеть на телефоне определяет культуру и состояние листа (49 классов, 15 культур) → голосовые команды и озвучка → журнал осмотров. В плане — справочник СЗР по Госреестру РБ и сервер синхронизации.

- Что делаем и в каком порядке — [docs/ROADMAP.md](docs/ROADMAP.md)
- Что есть сейчас — [docs/STATUS.md](docs/STATUS.md)
- Аудит проекта на 16.10.2026 — [docs/AUDIT_AND_PLAN_2026-10-16.md](docs/AUDIT_AND_PLAN_2026-10-16.md)
- Правила для ИИ-агентов — [AGENTS.md](AGENTS.md)
- Что не хранится в git и где лежит — [docs/ARCHIVE.md](docs/ARCHIVE.md)

## Состав

| Каталог | Что внутри |
|---|---|
| `android-app/` | Kotlin, Jetpack Compose, CameraX, ONNX Runtime, Vosk (офлайн-речь) |
| `ml-pipeline/` | подготовка данных, обучение YOLO (Ultralytics), экспорт ONNX; `build_allcrops_v1.py` — текущий набор, `gpu_kit/` — обучение на арендованной GPU |
| `docs/` | план, состояние, отчёты экспериментов, документы диплома (`docs/thesis/`) |

## Сборка APK

Нужны JDK 17+ (проверено на JDK 25) и Android SDK (путь в `android-app/local.properties`).

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Модели (`app/src/main/assets/models/*.onnx`) и речевая модель Vosk (`app/src/main/assets/voice/ru/`) в git не хранятся: они лежат на рабочем ПК (см. [docs/ARCHIVE.md](docs/ARCHIVE.md)). Скрипт загрузки — задача G-05 в ROADMAP. Без них APK соберётся, но распознавание и голос работать не будут.

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

## Ограничения (честно)

Модель распознаёт целый лист и его состояние, а не очаг болезни; уверенность — не процент поражения. Результат предварительный и требует проверки специалистом. Числа из исходного описания темы (mAP 93,4 % и т. п.) не подтверждены; актуальные метрики — в отчётах экспериментов.

История проекта до 16.10.2026: [docs/history/](docs/history/).
