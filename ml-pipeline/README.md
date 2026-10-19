# Пайплайн AgroVoice

Начало работы — [практическая инструкция обучения](../docs/guides/TRAINING_PLAYBOOK.md),
состояние и ограничения наборов — [STATUS](../docs/STATUS.md).
Длинное обучение выполняется на арендованной GPU; домашняя RX для него не используется.

## Рабочие входы

| Задача | Файлы | Что должно получиться |
|---|---|---|
| Сборка allcrops | [build_allcrops_v1.py](build_allcrops_v1.py), [taxonomy_v1.json](allcrops/taxonomy_v1.json) | Набор, manifest и report; порядок классов не менять |
| Источники и ext_v2 | [external/](external/), [источники](../docs/data/SOURCES.md) | Данные с происхождением и ограничениями геометрии |
| Строгий набор, unknown, негативы, веб-фото | [external/prepare_vision_alternatives.py](external/prepare_vision_alternatives.py), остальные `external/prepare_*` | Раздельные наборы по назначению, не новые диагнозы |
| Русский помощник | [assistant/prepare_corpus.py](assistant/prepare_corpus.py), [scenarios.json](assistant/scenarios.json) | Синтетические сценарии и проверяемые целевые действия; это ещё не Android-runtime |
| Baseline allcrops | [gpu_kit/README.md](gpu_kit/README.md), [train_queue.py](gpu_kit/train_queue.py) | Обучение, оценка, экспорт и пакет результатов |
| Дополнение ext_v2 | [gpu_kit/README_ext_v2.md](gpu_kit/README_ext_v2.md), [pack_ext_addon.py](pack_ext_addon.py) | Отдельный пакет дополнения |
| Новое сравнение моделей | [gpu_kit/alternatives/README.md](gpu_kit/alternatives/README.md) | Одинаковые данные и field-val, test отложен |
| Упаковка baseline | [pack_gpu_kit.py](pack_gpu_kit.py) | Самодостаточный архив в `artifacts/gpu_kit/` |
| Подготовка релиза | [prepare_model_release.py](prepare_model_release.py) | Проверенные файлы в `artifacts/release/`; публикация отдельным поручением |
| Регрессионные проверки | [tests/](tests/) | Тесты подготовки, разметки, загрузчика, упаковки и конфигов |

Старый baseline оценивает test после каждого запуска. Для нового контролируемого
сравнения используй конфигуратор из PLAYBOOK, который исключает test из конфига.
Это разные протоколы, их результаты нельзя смешивать.

## Исторические скрипты в корне

`accuracy_*`, `architecture_queue.py`, `train_clean.py`, `train_plantdoc.py`,
`yolo26*_queue.py`, `run_*`, `Start-*`, `TrainingProcess.ps1` и WSL/ROCm-скрипты
относятся к прежним опытам. Они сохранены для чтения и воспроизведения конкретной
серии, а не как очередь новых работ. `build_*report.py`, `evaluate_*`, `package_*`,
`audit_leaf_v3_*`, `prepare_olid_*`, `stage_plantvillage_*` и телефонные инструменты
нужно выбирать по протоколу той серии. Исключения — рабочие входы в таблице выше.

Физическое перемещение этих скриптов потребовало бы отдельно проверять импорты,
`Path(__file__)`, PowerShell/WSL-пути, упаковку и frozen snapshots. При сортировке
документации их расположение сохранено; обновлены только ссылки и пути перенесённых документов.

## Локальное окружение и файлы

`venv/` — существующее CPU-окружение. `requirements-cpu.lock.txt`,
`requirements-accuracy.lock.txt` и `requirements-wsl.lock.txt` относятся к своим
профилям и не являются единым актуальным окружением всех альтернатив.
`gpu_kit/requirements-gpu.txt` — зависимости базового GPU-комплекта.
Наличие локального venv не доказывает воспроизводимость чистой установки.

Наборы — `dataset*/`; источники — `source_candidates/`; кэш — `.cache/`;
результаты — `../runs/`; архивы — `../artifacts/gpu_kit/`. Перед повторной подготовкой
читать `report.json` существующего набора. Не редактировать изображения с hard link на месте.

```powershell
# Из корня репозитория
.\ml-pipeline\venv\Scripts\python.exe -X utf8 -m unittest discover -s ml-pipeline/tests
```
