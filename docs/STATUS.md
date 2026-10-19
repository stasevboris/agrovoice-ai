# Состояние проекта

## Сейчас (19.10.2026)

- **Структура и аудит:** документация разнесена по назначению; [карта проекта](PROJECT_MAP.md), [маршрут чтения](READING_GUIDE.md), [полный каталог](DOCUMENTATION_CATALOG.md), [аудит](audits/PROJECT_AUDIT_2026-10-19.md). Ссылки и пути генераторов обновлены; данные, архивы, taxonomy и frozen snapshots сохранены.
- **Проверка:** Python unittest discovery успешно; Gradle `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` успешен (тесты/APK UP-TO-DATE, lint с предупреждениями). `fetch_assets.py --check`, `pip check`, UTF-8/ссылки/синтаксис проверены; [команды и результаты](audits/PROJECT_AUDIT_2026-10-19.report.json). Телефон, обучение и публикация не выполнялись.
- **Android 0.18-field:** старые классы, Compose/ONNX/Vosk, системный офлайн-TTS при наличии голоса, JSON-журнал. Логотип — `android-app/branding/logo-source.png`, локальный APK — `artifacts/apk/agrovoice-logo-debug.apk`. Нет калиброванного unknown, LLM, verified-лечения, Room/GPS/сервера; [устройство](architecture/PROJECT_REPORT_2026-10-19.md).
- **Данные готовы, новые веса не обучены:** COCO/YOLO/вырезки, Beans/DeepWeeds, просмотренные Open Images, FLEURS/MASSIVE/синтетические команды; [отбор](research/DATA_AND_MODEL_SELECTION_2026-10-19.md), [сводка](data/TRAINING_PREPARATION_REPORT.json). iNaturalist/OPTIMA — отдельное вспомогательное пополнение: [назначение](data/WEB_DATA_2026-10-19.md), [report](data/WEB_RESEARCH_PREPARATION_REPORT.json).
- **Пакеты:** `artifacts/gpu_kit/agrovoice_research_data_20261019.zip` и `agrovoice_discovery_addon_20261019.zip`; allcrops/ext_v2 и baseline сохранены. [SHA/CRC основного](../artifacts/gpu_kit/agrovoice_research_data_20261019.report.json), [воспроизведение](../ml-pipeline/gpu_kit/alternatives/README.md), [PLAYBOOK](guides/TRAINING_PLAYBOOK.md). Не скачивать повторно. Новый конфигуратор использует одинаковый field-val без test.
- **Исследования:** [зрение](research/ROBUST_VISION_2026-10-19.md), [помощник](research/LOCAL_ASSISTANT_2026-10-19.md), [литература](research/AGRONOMY_LIBRARY_2026-10-19.md), [наука](research/SCIENTIFIC_EVIDENCE_2026-10-19.md), [функции](research/FEATURE_IDEAS_2026-10-19.md), [аналоги](research/ANALOGUES_2026-10-19.md). D-FINE/DINOv2/Qwen и другие варианты остаются кандидатами, функции/достижения не внедрены.

## Следующее

На неделю без RTX 5090 приоритет — разработка ПО: V-06 → A-11 → K-01; далее навигация и участки. Это порядок работ, не обещание закрыть все карточки за неделю.
Домашнее обучение — с команды Бориса до 23:00 МСК того же дня: короткий замер, checkpoint и остановка до срока; без автоматического продолжения завтра. Это заменяет лимит 14 часов и прежний запрет RX; правило сохранено в AGENTS. Проверены UTF-8 и diff с учётом CRLF.
Проверка окружения: `ml-pipeline/venv/Scripts/python.exe -X utf8 -c "import torch; print(torch.__version__, torch.version.hip, torch.cuda.is_available())"` — Windows PyTorch CPU, HIP отсутствует; `wsl --list --quiet` показывает AgroVoice-ML. Ускорение WSL и время обучения ещё не проверены, обучение не запускалось.

1. **V-06:** общий исполнитель действий кнопок/голоса; связать подготовленный JSON-протокол с Android, проверить контекст, отрицания, отмену и повторы. **R-01/V-08:** протокол и собственные независимые фото/речь, разделение по растению/сессии/говорящему. **K-01:** формат карточек РБ и экспертного подтверждения.
2. **R-02/R-03, M-03:** ограниченное сравнение по PLAYBOOK: YOLO без/с негативами, затем альтернативы на тех же данных. Аренда GPU либо домашний запуск в пределах разрешения выше. Для полного релиза сначала исправить геометрию недостающих train-классов. Затем M-04 → A-01/A-02/A-03; релиз через `prepare_model_release.py`.
3. **A-11/G-05/A-12:** сохранность журнала с фото, field/lab и воспроизводимые ассеты чистого клона. После текущего поручения возвращаться к рабочим карточкам [ROADMAP](ROADMAP.md); новые поиски — под выявленный дефицит, используя уже собранные каталоги.

## Найдено, но не сделано

- Строгий train не покрывает `corn__healthy`, `cucumber__bacterial_wilt`, `cucumber__downy_mildew`; негативов мало, первый автоматический Open Images-пилот отклонён. Неизвестный лист не «нет листа». PlantWild/PlantSeg исключены из нового обучения из-за расхождений условий.
- Остаются авторамки ext_v2, перекос lab, редкие классы и почти-дубликаты PlantDoc. Нет независимого собственного field-test, измеренного выигрыша новых моделей, INT8 и свежих замеров Note 7/A56. Синтетические сценарии не заменяют людей.
- Экспорт не включает фото, импорта нет; повреждённые JSON журнала могут молча пропускаться. Есть дубли фото и повторный анализ при открытии. Модельный release-конфиг пуст: локальная сборка не доказывает чистый клон.
- Исторический `saf_export_first_pull_pending.json` пуст: это заготовка, не доказательство экспорта. Старые APK направлены через [ARCHIVE](ARCHIVE.md); архив на `E:\Перенесено_с_F` и резервные копии телефона сохранены. DEIMv2 не публиковать; push, удаление данных, установка ПО и телефон — только по явному поручению.
