# Полный каталог документации

Все версионируемые текстовые документы проекта: Markdown, документы диплома и тексты лицензий.
Порядок чтения и обязательный минимум — [READING_GUIDE](READING_GUIDE.md); структура — [PROJECT_MAP](PROJECT_MAP.md).
Каталог не требует прочитать всё подряд. Дата в названии определяет область исторического документа.

## Как читать категории

- Вход/архитектура — сначала; состояние проверять по STATUS.
- Исследования/данные/инструкции — под выбранную карточку.
- Эксперименты/Android — для проверки конкретного результата и версии.
- История и DOCX — для происхождения решений; старые поручения не действуют.
- Лицензии — перед использованием и распространением соответствующего компонента.

## Вход, состояние, архитектура, инструкции и аудиты

| Файл | Название / назначение |
|---|---|
| [AGENTS.md](../AGENTS.md) | Инструкция для ИИ-агентов (Claude, Codex, Gemini, локальные модели) |
| [CLAUDE.md](../CLAUDE.md) | CLAUDE |
| [README.md](../README.md) | AgroVoice AI |
| [server/README.md](../server/README.md) | Локальный сервер, версии осмотров и загрузка фото |
| [docs/android/ci_2026-10-19/README.md](android/ci_2026-10-19/README.md) | CI и локальная проверка чистой копии без моделей |
| [docs/experiments/home_training_2026-10-19/README.md](experiments/home_training_2026-10-19/README.md) | AMD-пилоты зрения и LoRA помощника с ограничениями |
| [artifacts/field_photos/ATTRIBUTION.txt](../artifacts/field_photos/ATTRIBUTION.txt) | ATTRIBUTION |
| [docs/ARCHIVE.md](ARCHIVE.md) | Что не хранится в git и где это лежит |
| [docs/DOCUMENTATION_CATALOG.md](DOCUMENTATION_CATALOG.md) | Полный каталог документации |
| [docs/PROJECT_MAP.md](PROJECT_MAP.md) | Карта проекта |
| [docs/READING_GUIDE.md](READING_GUIDE.md) | Борису: что прочитать для дальнейшей работы |
| [docs/README.md](README.md) | Документация AgroVoice AI |
| [docs/ROADMAP.md](ROADMAP.md) | AgroVoice AI — подробный план разработки (все культуры) |
| [docs/STATUS.md](STATUS.md) | Состояние проекта |
| [docs/architecture/PROJECT_REPORT_2026-10-19.md](architecture/PROJECT_REPORT_2026-10-19.md) | AgroVoice AI: полный технический отчёт |
| [docs/audits/AUDIT_AND_PLAN_2026-10-16.md](audits/AUDIT_AND_PLAN_2026-10-16.md) | AgroVoice AI — аудит проекта и новый план разработки |
| [docs/audits/PROJECT_AUDIT_2026-10-19.md](audits/PROJECT_AUDIT_2026-10-19.md) | Аудит AgroVoice AI — 19 октября 2026 |
| [docs/environment/hardware_without_fancontrol_report_2026-10-03.txt](environment/hardware_without_fancontrol_report_2026-10-03.txt) | hardware_without_fancontrol_report_2026-10-03 |
| [docs/guides/TRAINING_PLAYBOOK.md](guides/TRAINING_PLAYBOOK.md) | Что ставить на обучение AgroVoice |
| [docs/reports/README.md](reports/README.md) | Отчёты перенесены |

## Лицензии и авторство

| Файл | Название / назначение |
|---|---|
| [ATTRIBUTION.md](../ATTRIBUTION.md) | Авторы данных и компонентов |
| [LICENSE](../LICENSE) | Условия использования и распространения компонента |
| [android-app/app/src/main/assets/licenses/DEIMv2-LICENSE.md](../android-app/app/src/main/assets/licenses/DEIMv2-LICENSE.md) | Условия использования и распространения компонента |
| [android-app/app/src/main/assets/licenses/JNA-LICENSE.txt](../android-app/app/src/main/assets/licenses/JNA-LICENSE.txt) | Условия использования и распространения компонента |
| [android-app/app/src/main/assets/licenses/VOSK-APACHE-2.0.txt](../android-app/app/src/main/assets/licenses/VOSK-APACHE-2.0.txt) | Условия использования и распространения компонента |

## Практические инструкции рядом с кодом

| Файл | Название / назначение |
|---|---|
| [android-app/branding/README.md](../android-app/branding/README.md) | Логотип AgroVoice AI |
| [kb/README.md](../kb/README.md) | Справочник AgroVoice (K-01): формат карточек и правила проверки |
| [ml-pipeline/README.md](../ml-pipeline/README.md) | Пайплайн AgroVoice |
| [ml-pipeline/external/licenses/IBEAN-MIT.txt](../ml-pipeline/external/licenses/IBEAN-MIT.txt) | Условия использования и распространения компонента |
| [ml-pipeline/gpu_kit/README.md](../ml-pipeline/gpu_kit/README.md) | AgroVoice — обучение на арендованной RTX 5090 |
| [ml-pipeline/gpu_kit/README_ext_v2.md](../ml-pipeline/gpu_kit/README_ext_v2.md) | Дополнение ext_v2 к комплекту AgroVoice (дообучение на внешних снимках) |
| [ml-pipeline/gpu_kit/alternatives/README.md](../ml-pipeline/gpu_kit/alternatives/README.md) | Данные для сравнения моделей AgroVoice |

## Android: инструкции и доказательства версий

| Файл | Название / назначение |
|---|---|
| [docs/android/FIELD_DEMO_2026-10-03.md](android/FIELD_DEMO_2026-10-03.md) | AgroVoice AI: APK для проверки фотографий |
| [docs/android/FIELD_TOOLS_2026-10-04.md](android/FIELD_TOOLS_2026-10-04.md) | AgroVoice: голос и полевые инструменты 0.13 |
| [docs/android/FIELD_TOOLS_2026-10-05.md](android/FIELD_TOOLS_2026-10-05.md) | AgroVoice: полевые инструменты, голос и итоговая YOLO26s |
| [docs/android/MODEL_LAB.md](android/MODEL_LAB.md) | Как проверять AgroVoice на телефоне |
| [docs/android/PHONE_TEST_INSTALL_2026-10-04.md](android/PHONE_TEST_INSTALL_2026-10-04.md) | Запросы Google Play Защиты при установке тестового APK |
| [docs/android/README.md](android/README.md) | Проверки и история Android |
| [docs/android/VOICE_COMMANDS_2026-10-04.md](android/VOICE_COMMANDS_2026-10-04.md) | Голосовые команды AgroVoice — 4 октября 2026 |
| [docs/android/VOICE_CONTROLS_2026-10-04.md](android/VOICE_CONTROLS_2026-10-04.md) | Полное голосовое управление — APK0.11, 4 октября2026 |
| [docs/android/VOICE_GALLERY_2026-10-04.md](android/VOICE_GALLERY_2026-10-04.md) | Настоящая съёмка и голосовой выбор галереи — APK0.10 |
| [docs/android/features_v1_phone/instrumentation.txt](android/features_v1_phone/instrumentation.txt) | instrumentation |
| [docs/android/features_v2_phone/instrumentation.txt](android/features_v2_phone/instrumentation.txt) | instrumentation |
| [docs/android/phone_validation/gallery_checksums.txt](android/phone_validation/gallery_checksums.txt) | gallery_checksums |
| [docs/android/phone_validation/meminfo.txt](android/phone_validation/meminfo.txt) | meminfo |
| [docs/android/voice_v1/apk_permissions.txt](android/voice_v1/apk_permissions.txt) | apk_permissions |
| [docs/android/voice_v1/instrumentation.txt](android/voice_v1/instrumentation.txt) | instrumentation |
| [docs/android/voice_v1/instrumentation_before_case_alias.txt](android/voice_v1/instrumentation_before_case_alias.txt) | instrumentation_before_case_alias |
| [docs/android/voice_v2/apk_permissions.txt](android/voice_v2/apk_permissions.txt) | apk_permissions |
| [docs/android/voice_v2/instrumentation.txt](android/voice_v2/instrumentation.txt) | instrumentation |
| [docs/android/voice_v2/instrumentation_fixed_42of42.txt](android/voice_v2/instrumentation_fixed_42of42.txt) | instrumentation_fixed_42of42 |
| [docs/android/voice_v2/instrumentation_initial.txt](android/voice_v2/instrumentation_initial.txt) | instrumentation_initial |
| [docs/android/voice_v2/vosk_warnings_initial.txt](android/voice_v2/vosk_warnings_initial.txt) | vosk_warnings_initial |
| [docs/android/voice_v3/instrumentation.txt](android/voice_v3/instrumentation.txt) | instrumentation |
| [docs/android/voice_v3/instrumentation_fixture_path_failure.txt](android/voice_v3/instrumentation_fixture_path_failure.txt) | instrumentation_fixture_path_failure |
| [docs/android/voice_v3/recognition_instrumentation.txt](android/voice_v3/recognition_instrumentation.txt) | recognition_instrumentation |
| [docs/android/yolo26m_pilot_v16_phone/instrumentation.txt](android/yolo26m_pilot_v16_phone/instrumentation.txt) | instrumentation |
| [docs/android/yolo26s_final_v14_phone/instrumentation.txt](android/yolo26s_final_v14_phone/instrumentation.txt) | instrumentation |
| [docs/android/yolo26s_final_v15_phone/instrumentation.txt](android/yolo26s_final_v15_phone/instrumentation.txt) | instrumentation |

## Данные

| Файл | Название / назначение |
|---|---|
| [docs/data/README.md](data/README.md) | Данные и источники |
| [docs/data/SOURCES.md](data/SOURCES.md) | Внешние наборы ext_v2 (дополнение к allcrops_v1) |
| [docs/data/WEB_DATA_2026-10-19.md](data/WEB_DATA_2026-10-19.md) | Новые веб-фотографии и OPTIMA: назначение и расположение |
| [docs/data/cucumber_sources.md](data/cucumber_sources.md) | Данные для огурцов — проверка источников, 3 октября 2026 |
| [docs/data/dataset_clean_v2.md](data/dataset_clean_v2.md) | Данные clean v2 — происхождение и ограничения |

## Эксперименты и диагностика

| Файл | Название / назначение |
|---|---|
| [docs/diagnostics/cucumber_misclassification_2026-10-05/REPORT.md](diagnostics/cucumber_misclassification_2026-10-05/REPORT.md) | Огурец, ошибочно распознанный как томат — 5 октября 2026 |
| [docs/experiments/MODEL_CARD_plantdoc_clean_v1.md](experiments/MODEL_CARD_plantdoc_clean_v1.md) | Базовая модель PlantDoc clean v1 |
| [docs/experiments/README.md](experiments/README.md) | Эксперименты и доказательства |
| [docs/experiments/STUDY10_PROTOCOL_2026-10-03.md](experiments/STUDY10_PROTOCOL_2026-10-03.md) | Серия по десять обучений YOLO11n и YOLO26n |
| [docs/experiments/YOLO_COMPARISON_2026-10-03.md](experiments/YOLO_COMPARISON_2026-10-03.md) | YOLO11n и YOLO26n: сравнение для AgroVoice AI |
| [docs/experiments/v06_intent_baseline/README.md](experiments/v06_intent_baseline/README.md) | V-06: понимание команд без обучения (19.10.2026) |
| [docs/experiments/accuracy_v1/CONTINUE.md](experiments/accuracy_v1/CONTINUE.md) | Историческая инструкция — очередь80 отменена |
| [docs/experiments/accuracy_v1/ETA_2026-10-04.md](experiments/accuracy_v1/ETA_2026-10-04.md) | Оценка оставшегося времени accuracy_v1 |
| [docs/experiments/accuracy_v1/PROTOCOL.md](experiments/accuracy_v1/PROTOCOL.md) | Новая серия по пяти направлениям — accuracy_v1 |
| [docs/experiments/redmi_note_7_10series/REPORT.md](experiments/redmi_note_7_10series/REPORT.md) | Redmi Note 7 — десять серий на архитектуру |
| [docs/experiments/study10_v2/FINAL_REPORT_2026-10-04.md](experiments/study10_v2/FINAL_REPORT_2026-10-04.md) | Итог серии: 10 обучений YOLO11n и 10 YOLO26n |
| [docs/experiments/study10_v2/REPORT.md](experiments/study10_v2/REPORT.md) | YOLO11n и YOLO26n — серия по 10 обучений |
| [docs/experiments/yolo26m_v1/CONTINUE.md](experiments/yolo26m_v1/CONTINUE.md) | Продолжение YOLO26s → YOLO26m |
| [docs/experiments/yolo26m_v1/PILOT_MODEL_CARD.md](experiments/yolo26m_v1/PILOT_MODEL_CARD.md) | YOLO26m: карточка технического пилота |
| [docs/experiments/yolo26m_v1/PROTOCOL.md](experiments/yolo26m_v1/PROTOCOL.md) | YOLO26m — отдельная серия после YOLO26s |
| [docs/experiments/yolo26m_v1/REPORT.md](experiments/yolo26m_v1/REPORT.md) | YOLO26m: подготовка, испытания и сравнение с N/S |
| [docs/experiments/yolo26s_data_v1/ANNOTATION_CONTRACT_DRAFT.md](experiments/yolo26s_data_v1/ANNOTATION_CONTRACT_DRAFT.md) | Контракт нового листового набора — проект до заморозки |
| [docs/experiments/yolo26s_data_v1/HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md](experiments/yolo26s_data_v1/HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md) | Ограничения независимости исторических оценок — 5 октября 2026 |
| [docs/experiments/yolo26s_data_v1/PROTOCOL_DRAFT.md](experiments/yolo26s_data_v1/PROTOCOL_DRAFT.md) | Новая серия YOLO26s: данные и протокол подготовки |
| [docs/experiments/yolo26s_data_v1/REPORT.md](experiments/yolo26s_data_v1/REPORT.md) | Подготовка нового обучения YOLO26s — 5 октября |
| [docs/experiments/yolo26s_data_v1/SOURCE_AUDIT_REPORT_2026-10-05.md](experiments/yolo26s_data_v1/SOURCE_AUDIT_REPORT_2026-10-05.md) | Аудит источников новой YOLO26s: фактический срез |
| [docs/experiments/yolo26s_data_v1/candidate_overlap_full_r2/HUMAN_READABLE_REVIEW_2026-10-05.md](experiments/yolo26s_data_v1/candidate_overlap_full_r2/HUMAN_READABLE_REVIEW_2026-10-05.md) | Full overlap review — 5 October 2026 |
| [docs/experiments/yolo26s_data_v1/class_support_r1/README.md](experiments/yolo26s_data_v1/class_support_r1/README.md) | PlantVillage candidate class support |
| [docs/experiments/yolo26s_data_v1/legacy_overlap_visual_r1/README.md](experiments/yolo26s_data_v1/legacy_overlap_visual_r1/README.md) | Визуальная проверка наследованных похожих кадров |
| [docs/experiments/yolo26s_data_v1/olid_capture_metadata_r1/README.md](experiments/yolo26s_data_v1/olid_capture_metadata_r1/README.md) | OLID capture metadata audit — 5 October 2026 |
| [docs/experiments/yolo26s_focus_v1/CONTINUE.md](experiments/yolo26s_focus_v1/CONTINUE.md) | Продолжение текущей работы |
| [docs/experiments/yolo26s_focus_v1/MODEL_CARD.md](experiments/yolo26s_focus_v1/MODEL_CARD.md) | Карточка YOLO26s: десять обучений и ограничения |
| [docs/experiments/yolo26s_focus_v1/PROTOCOL.md](experiments/yolo26s_focus_v1/PROTOCOL.md) | YOLO26s + YOLO26n: действующий протокол |

## История и старые рабочие указатели

| Файл | Название / назначение |
|---|---|
| [docs/history/PROJECT_GUIDE_until_2026-10-16.md](history/PROJECT_GUIDE_until_2026-10-16.md) | AgroVoice AI — состояние проекта и журнал изменений |
| [docs/history/README.md](history/README.md) | История проекта |
| [docs/history/README_before_2026-10-03.md](history/README_before_2026-10-03.md) | 🌿 AgroVoice AI (PhytoGuard) |
| [docs/history/README_until_2026-10-16.md](history/README_until_2026-10-16.md) | AgroVoice AI |
| [docs/history/reports/01_PROJECT_STATUS_2026-10-04.md](history/reports/01_PROJECT_STATUS_2026-10-04.md) | Состояние AgroVoice AI, стек и решения |
| [docs/history/reports/02_MODEL_STUDY_2026-10-04.md](history/reports/02_MODEL_STUDY_2026-10-04.md) | Подробный эксперимент: выбор YOLO для AgroVoice AI |
| [docs/history/reports/03_ANDROID_IMPLEMENTATION_2026-10-04.md](history/reports/03_ANDROID_IMPLEMENTATION_2026-10-04.md) | Автономный Android-прототип: реализация и испытания |
| [docs/history/reports/04_DATA_AND_ROADMAP_2026-10-04.md](history/reports/04_DATA_AND_ROADMAP_2026-10-04.md) | Слабые классы, новые данные и план до диплома |
| [docs/history/reports/05_ACCURACY_OPTIONS_2026-10-04.md](history/reports/05_ACCURACY_OPTIONS_2026-10-04.md) | Варианты повышения точности и смены архитектуры AgroVoice |
| [docs/history/reports/06_FIVE_VARIANTS_PROGRESS_2026-10-04.md](history/reports/06_FIVE_VARIANTS_PROGRESS_2026-10-04.md) | Все пять вариантов: фактический ход работы |
| [docs/history/reports/07_YOLO26_FOCUS_2026-10-04.md](history/reports/07_YOLO26_FOCUS_2026-10-04.md) | YOLO26s и YOLO26n: текущий результат и принятое решение |
| [docs/history/reports/08_YOLO26M_PILOT_2026-10-05.md](history/reports/08_YOLO26M_PILOT_2026-10-05.md) | YOLO26m: первый запуск и APK 0.16 |
| [docs/history/reports/README.md](history/reports/README.md) | Подробные отчёты AgroVoice AI — 4 октября 2026 |
| [docs/history/research/ANDROID_STORES_PUBLICATION_2026-10-03.md](history/research/ANDROID_STORES_PUBLICATION_2026-10-03.md) | Публикация AgroVoice на Android |
| [docs/history/research/DATASETS_NEXT_TRAINING_2026-10-03.md](history/research/DATASETS_NEXT_TRAINING_2026-10-03.md) | Данные для следующего обучения AgroVoice |
| [docs/history/research/GEMINI_DATASET_PROPOSALS_2026-10-05.md](history/research/GEMINI_DATASET_PROPOSALS_2026-10-05.md) | Подборка Gemini: входящий материал для следующей серии |
| [docs/history/research/LOCAL_AGENTS_SETUP_2026-10-03.md](history/research/LOCAL_AGENTS_SETUP_2026-10-03.md) | Локальный агент для базовых задач |
| [docs/history/research/LOCAL_CODING_MODELS_2026-10-03.md](history/research/LOCAL_CODING_MODELS_2026-10-03.md) | Локальные модели для программирования на компьютере Бориса |
| [docs/history/research/YOLO_ALTERNATIVES_2026-10-04.md](history/research/YOLO_ALTERNATIVES_2026-10-04.md) | Другие YOLO для AgroVoice: что имеет смысл проверять |
| [docs/history/research/config_examples/local-agent-task.example.md](history/research/config_examples/local-agent-task.example.md) | Пример задания локальному агенту |
| [docs/history/research/gemini_dataset_proposals_2026-10-05.txt](history/research/gemini_dataset_proposals_2026-10-05.txt) | gemini_dataset_proposals_2026-10-05 |
| [docs/history/research/university_topic_check_2026-10-03.md](history/research/university_topic_check_2026-10-03.md) | Проверка похожих дипломных тем ГГТУ — 3 октября 2026 |
| [docs/session/CONTINUATION_CHECKPOINT_2026-10-05.md](session/CONTINUATION_CHECKPOINT_2026-10-05.md) | Контрольная точка AgroVoice — 2026-10-05T12:30:23.271054+00:00 |
| [docs/session/README.md](session/README.md) | Исторические состояния автоматизаций |

## Исследования и литература

| Файл | Название / назначение |
|---|---|
| [docs/research/AGRONOMY_LIBRARY_2026-10-19.md](research/AGRONOMY_LIBRARY_2026-10-19.md) | Литература и источники знаний для AgroVoice AI |
| [docs/research/ANALOGUES_2026-10-19.md](research/ANALOGUES_2026-10-19.md) | Аналоги AgroVoice AI и выбранные улучшения |
| [docs/research/DATA_AND_MODEL_SELECTION_2026-10-19.md](research/DATA_AND_MODEL_SELECTION_2026-10-19.md) | Данные и модели для следующего обучения AgroVoice |
| [docs/research/FEATURE_IDEAS_2026-10-19.md](research/FEATURE_IDEAS_2026-10-19.md) | Функции AgroVoice и система достижений |
| [docs/research/LOCAL_ASSISTANT_2026-10-19.md](research/LOCAL_ASSISTANT_2026-10-19.md) | Свободное голосовое управление и локальный агрономический помощник |
| [docs/research/README.md](research/README.md) | Исследования AgroVoice |
| [docs/research/ROBUST_VISION_2026-10-19.md](research/ROBUST_VISION_2026-10-19.md) | Надёжное распознавание растений: варианты за пределами YOLO |
| [docs/research/SCIENTIFIC_EVIDENCE_2026-10-19.md](research/SCIENTIFIC_EVIDENCE_2026-10-19.md) | Научные основания AgroVoice: что читать и что проверять |

## Диплом

| Файл | Название / назначение |
|---|---|
| [docs/thesis/2026-10-05/AgroVoice_Opisanie_Temy_i_Steka_2026-10-05.docx](thesis/2026-10-05/AgroVoice_Opisanie_Temy_i_Steka_2026-10-05.docx) | Исторический снимок записки/описания; не текущая спецификация |
| [docs/thesis/2026-10-05/AgroVoice_Polnaya_Istoriya_Razrabotki_2026-10-05.docx](thesis/2026-10-05/AgroVoice_Polnaya_Istoriya_Razrabotki_2026-10-05.docx) | Исторический снимок записки/описания; не текущая спецификация |
| [docs/thesis/2026-10-05/FINAL_QA_REPORT.md](thesis/2026-10-05/FINAL_QA_REPORT.md) | DOCX delivery and final verification — 2026-10-05 |
| [docs/thesis/2026-10-05/original_2026-09-22.docx](thesis/2026-10-05/original_2026-09-22.docx) | Исторический снимок записки/описания; не текущая спецификация |
| [docs/thesis/README.md](thesis/README.md) | Документы диплома |

## Машинные доказательства и локальные материалы

| Где | Что читать |
|---|---|
| [data/](data/) | CSV источников, отчёты подготовки, происхождение и исключения |
| [experiments/](experiments/README.md) | Протокол серии → evaluation/results/summary JSON/CSV; не путать разные split и checkpoint |
| [android/](android/README.md) | verification/benchmark/summary JSON конкретной версии; PNG — иллюстрации, не самостоятельные метрики |
| [audits/](audits/PROJECT_AUDIT_2026-10-19.md) | Машинный отчёт проверки проекта и документации |
| [Манифест моделей](../android-app/app/src/main/assets/models/manifest.json), [голоса](../android-app/app/src/main/assets/voice/manifest.json), [taxonomy](../ml-pipeline/allcrops/taxonomy_v1.json) | Состав ассетов и неизменяемое сопоставление ID |
| [ARCHIVE](ARCHIVE.md) | Локальные наборы, ZIP, веса, APK, JSONL, WAV и внешний архив; их наличие не гарантируется чистым клоном |

JSON/CSV, программные исходники, изображения и lock-файлы не перечисляются как отдельные книги.
Полнота этого поимённого каталога проверена по списку текстовых файлов git и записана
в [машинном отчёте](audits/PROJECT_AUDIT_2026-10-19.report.json). Временные файлы вне git в каталог не входят.
