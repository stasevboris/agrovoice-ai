# Состояние проекта

## Сейчас (19.10.2026)

- **Новый поиск (`e5e812e`):** [функции и достижения](research/FEATURE_IDEAS_2026-10-19.md), [наука/университеты](research/SCIENTIFIC_EVIDENCE_2026-10-19.md), [карта чтения](READING_GUIDE.md). Продолжение: ODK/CameraX/ONNX, проверка ложного обещания offline в голосовом примере, PPDRD/NZDL; [кандидаты и исключения](data/DISCOVERY_CANDIDATES_2026-10-19.csv). Локальные ссылки/UTF-8/diff проверены; функции ещё не внедрены.
- **Новое пополнение:** обычные фото iNaturalist просмотрены и частично размечены; OPTIMA RGB подготовлен отдельно. [Где файлы и назначение](data/WEB_DATA_2026-10-19.md), [report.json](data/WEB_RESEARCH_PREPARATION_REPORT.json); архив `artifacts/gpu_kit/agrovoice_discovery_addon_20261019.zip`, CRC проверен. Это вспомогательный train, не новые подтверждённые классы болезней.
- **Обучение и проверка:** [что запускать, эпохи/повторы](TRAINING_PLAYBOOK.md); `configure_yolo_pilot.py` проверен на реальных данных, field-val одинаковый, test не включён. `.\ml-pipeline\venv\Scripts\python.exe -X utf8 -m unittest discover -s ml-pipeline/tests` — успешно; [отчёт](../artifacts/verification/discovery_20261019/report.json). Android и GPU-обучение не запускались.
- **Ранее (`22badb2`):** COCO/YOLO, вырезки, Beans/DeepWeeds, просмотренные Open Images; FLEURS/MASSIVE/синтетические команды. [Отбор](research/DATA_AND_MODEL_SELECTION_2026-10-19.md), [каталог](data/DATASET_CANDIDATES_2026-10-19.csv), [сводка](data/TRAINING_PREPARATION_REPORT.json), [проверка данных](../artifacts/verification/training_preparation_20261019/data_report.json). Не скачивать повторно; ограничения сохраняются.
- **Основной пакет:** `artifacts/gpu_kit/agrovoice_research_data_20261019.zip`, [SHA/CRC/состав](../artifacts/gpu_kit/agrovoice_research_data_20261019.report.json), сборщик `gpu_kit/alternatives/pack_data.py`; [воспроизведение](../ml-pipeline/gpu_kit/alternatives/README.md). Allcrops/ext_v2 и baseline-пакеты сохранены; новые веса не обучались.
- **Кандидаты:** YOLO26n/s → D-FINE-N/S, DINOv2 → мобильный ученик; правила/классификатор → Qwen3-0.6B/Qwen3.5-0.8B, резерв LFM/GigaAM. Это направления испытаний, не выбор победителя.
- **Android 0.18-field:** старые 30 классов, Compose/ONNX/Vosk, офлайн-TTS, JSON-журнал; калиброванного unknown и локальной LLM нет. Логотип встроен, исходник — `android-app/branding/logo-source.png`, APK — `artifacts/apk/agrovoice-logo-debug.apk`; [предыдущая проверка приложения](../artifacts/verification/research_20261019/report.json). Телефон не использовался.
- **Контекст:** [полный технический отчёт](PROJECT_REPORT_2026-10-19.md), [зрение](research/ROBUST_VISION_2026-10-19.md), [помощник](research/LOCAL_ASSISTANT_2026-10-19.md), [литература](research/AGRONOMY_LIBRARY_2026-10-19.md), [аналоги](ANALOGUES_2026-10-19.md), [источники ext_v2](data/SOURCES.md), [ROADMAP](ROADMAP.md).

## Следующее

1. **R-02/R-03, M-03:** ограниченное сравнение на арендованной GPU: одинаковые данные → YOLO без/с негативами → D-FINE/представления. Перед полным обучением проверить геометрию недостающих классов. После выбора — M-04 → A-01/A-02/A-03 и релиз через `prepare_model_release.py`. RX не использовать.
2. **V-06/V-07:** общий исполнитель действий и связь подготовленного JSON-протокола с Android; сравнить правила/малый классификатор, исходные Qwen и SFT. **R-01/V-08:** собственный независимый набор фото, пар свет/тень и речи; разделение по растению/сессии/говорящему. **K-01/K-04:** карточки знаний РБ, экспертная верификация и поиск.
3. По поручению Бориса продолжать полезный поиск GitHub/статей до остановки: каталог PPDRD, недостающие полевые классы, предметные негативы; сохранять законченные инкременты. Веб-фото без диагноза не смешивать с disease labels, достижения реализовывать после выбора задачи и слоя журнала.

## Найдено, но не сделано

- Строгий vision-набор не содержит train для `corn__healthy`, `cucumber__bacterial_wilt`, `cucumber__downy_mildew`. Негативов мало; первоначальный автоматический Open Images-пилот отклонён (`training_ready=false`). Неизвестный вид не означает отсутствие листа. PlantWild/PlantSeg исключены из нового обучения из-за расхождений условий страниц семейства.
- Непроверенные авторамки ext_v2, перекос lab, редкие классы и исторические почти-дубликаты PlantDoc остаются. Нет измеренного выигрыша новых моделей, калиброванного отказа, INT8 и замеров на Note 7/A56. Синтетические команды и общий речевой корпус не заменяют реальные сценарии AgroVoice.
- Нет verified-базы лечения, Room/GPS/сервера. Фото дублируются, открытие снимка запускает анализ, JSON-экспорт не содержит фото; голос/прокрутка и подсказки качества не проверены полным сценарием на телефоне.
- Старые результаты — `E:\Перенесено_с_F`, [ARCHIVE](ARCHIVE.md); резервные копии телефона сохранены. DEIMv2 не публиковать. Push, публикация, удаление данных, установка системного ПО и действия на телефоне требуют явного поручения.
