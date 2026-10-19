# Данные и источники

Назначение наборов — [отбор данных](../research/DATA_AND_MODEL_SELECTION_2026-10-19.md).
Порядок запуска — [PLAYBOOK](../guides/TRAINING_PLAYBOOK.md). Состояние — [STATUS](../STATUS.md).

| Документ | Для чего читать |
|---|---|
| [SOURCES.md](SOURCES.md) | Источники ext_v2, авторство, условия использования, ограничения авторамок |
| [TRAINING_PREPARATION_REPORT.json](TRAINING_PREPARATION_REPORT.json) | Состав новых наборов, исключения и отсутствующие train-классы |
| [WEB_DATA_2026-10-19.md](WEB_DATA_2026-10-19.md) | Назначение веб-фото и OPTIMA; неизвестное состояние не объявлять здоровым |
| [WEB_RESEARCH_PREPARATION_REPORT.json](WEB_RESEARCH_PREPARATION_REPORT.json) | Количества, ограничения и подготовка дополнения |
| [DATASET_CANDIDATES](DATASET_CANDIDATES_2026-10-19.csv) | Каталог вариантов, не перечень проверенных независимых наборов |
| [DISCOVERY_CANDIDATES](DISCOVERY_CANDIDATES_2026-10-19.csv) | Новые кандидаты и причины исключения |
| [dataset_clean_v2.md](dataset_clean_v2.md) | История раннего PlantDoc; ограничения пересечений сохраняются |
| [cucumber_sources.md](cucumber_sources.md) | Источники прежнего огуречного дополнения |

Остальные JSON/JPG — сохранённые результаты ранних проверок. Изображения наборов
лежат в `ml-pipeline/dataset*/`, исходники — в `ml-pipeline/source_candidates/`;
см. [ARCHIVE](../ARCHIVE.md). Вырезка не новое независимое наблюдение, вид растения
не диагноз, unknown-лист не негатив «без листа».
