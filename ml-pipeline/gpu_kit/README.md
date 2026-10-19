# AgroVoice — обучение на арендованной RTX 5090

Комплект самодостаточный: скрипты, набор `allcrops_v1` (49 классов, 15 культур), исходные веса YOLO26 n/s/m.
Интернет на машине нужен только для установки PyTorch и пакетов (`pip`).
Всё проверено на этом ПК: пробный прогон, лимит времени, STOP с продолжением, экспорт ONNX и паритет с PyTorch (на CPU, 16.10.2026). Сама 5090 не проверялась — первые 15 минут сеанса уходят на `check_env.py` и `--smoke`.

## Что получится на выходе

Для каждой модели из очереди (`queue.json`):
- `runs/<имя>/weights/best.pt` — лучшая эпоха по val;
- `runs/<имя>/weights/best.onnx` — граф для Android, выход `[1, 53, 3549]` = 4 координаты + 49 классов, обычная голова с внешним NMS, как в APK 0.16;
- `runs/<имя>/eval/evaluation.json` — mAP по доменам: **field** (PlantDoc, фото в поле) и **lab** (одиночные листья PlantVillage/OLID/Zenodo), отдельно val и test, плюс по классам;
- `runs/<имя>/android_manifest_entry.json` — готовая запись для `assets/models/manifest.json` (классы, русские подписи, культуры, SHA);
- `run_summary.json` — параметры, время, GPU, отпечаток набора.

## Перед сеансом (дома)

1. Узнать у владельца машины: ОС (Linux или Windows), сколько часов доступно, есть ли интернет и `nvidia-smi`, свободное место (нужно ≥ 25 ГБ: архив, распаковка, PyTorch ~5 ГБ, результаты).
2. Скопировать на флешку или в облако `artifacts/gpu_kit/agrovoice_gpu_kit_YYYYMMDD.zip` (размер и SHA-256 — в `.manifest.json` рядом).
3. Решить, сколько часов **реально** отдаётся под обучение (сеанс минус ~30 мин на установку и ~15 мин на упаковку).

## На арендованной машине: пошагово

### Linux (SSH)

```bash
unzip agrovoice_gpu_kit_*.zip && cd agrovoice_gpu_kit_*
bash setup_linux.sh 2>&1 | tee setup.log        # 5–15 мин, в конце печатает READY или список проблем
source .venv/bin/activate
python train_queue.py --smoke                    # 2–3 мин: весь путь на крошечном наборе
tmux new -s train                                # чтобы обрыв SSH не убил обучение
python -u train_queue.py --budget-hours 3.5 2>&1 | tee -a logs/queue.log
#   Ctrl+B, затем D — отцепиться;  tmux attach -t train — вернуться
```

### Windows (RDP / за компьютером)

```powershell
Expand-Archive agrovoice_gpu_kit_*.zip -DestinationPath .
cd agrovoice_gpu_kit_*
powershell -ExecutionPolicy Bypass -File .\setup_windows.ps1
.\.venv\Scripts\python.exe train_queue.py --smoke
powercfg /change standby-timeout-ac 0            # чтобы ПК не уснул
.\.venv\Scripts\python.exe -u train_queue.py --budget-hours 3.5 2>&1 | Tee-Object -Append logs\queue.log
```

`--budget-hours N` — сколько часов отдаётся очереди, считая от запуска. Вместо этого можно указать `--deadline 18:30` (время машины — проверьте часовой пояс). Очередь сама не начнёт модель, если на неё и экспорт не хватает времени.

### Что в очереди и сколько времени

| # | Модель | Лимит, ч | Зачем |
|---|---|---:|---|
| 1 | YOLO26s 416, seed 20261003 | 1,4 | основной кандидат для телефона (S в APK 0.16 ≈ 0,47 с на Note 7) |
| 2 | YOLO26n 416, seed 20261003 | 0,8 | быстрая модель для слабых телефонов (≈ 0,17 с) |
| 3 | YOLO26m 416, seed 20261003 | 1,6 | потолок точности и сравнение для диплома (≈ 1,4 с на Note 7) |
| 4 | YOLO26s 416, seed 20261004 | 1,4 | второй seed основной модели — если останется время |

Каждая модель учится до 60 эпох (ранняя остановка через 15 эпох без улучшения). Если после первой эпохи видно, что 60 эпох не помещаются в лимит, скрипт переключает Ultralytics в режим лимита по времени: расписание LR подстраивается, лучшая эпоха всё равно сохраняется.
Ориентир (не измерен): на 5090 эпоха S на ~30 тыс. изображений 416×416 — порядка 1–2 мин. Реальное время эпохи видно в логе после первой эпохи.

Эти лимиты не гарантируют завершение всех эпох или всей очереди. Реальную
вместимость сеанса оценить после первых эпох и оставить резерв на экспорт.
Для нового исследования с одинаковым field-val и отдельным сравнением негативов
использовать [TRAINING_PLAYBOOK](../../docs/guides/TRAINING_PLAYBOOK.md), а не переносить
результаты этой baseline-очереди в другое сравнение без оговорок.

Поменять порядок или лимиты — в `queue.json`. Запустить только одну модель: `--only allcrops_v1_yolo26s_416_s20261003`.

### Наблюдение

- `nvidia-smi -l 5` — загрузка GPU (норма 80–100 %; если < 50 %, упираемся в CPU или диск: см. «Проблемы»).
- `runs/<имя>/results.csv` — метрики по эпохам; `logs/queue.log` — общий лог.
- После каждой готовой модели можно запустить `python package_results.py` (не мешает обучению) и сразу скачать промежуточный архив.

### Остановка и продолжение

- Мягко: создать файл `STOP` в корне комплекта (`touch STOP` / `New-Item STOP`) — эпоха доучится и сохранится, очередь остановится.
- Продолжить: удалить `STOP` и снова запустить ту же команду очереди. Готовые модели пропускаются, прерванная продолжится с `weights/last_resumable.pt`.
- Если процесс убит (Ctrl+C, обрыв) — тоже просто запустить очередь снова.

### В конце сеанса (обязательно, ~15 мин)

```bash
python package_results.py          # -> results_<host>_<дата>.zip (best/last.pt, ONNX, метрики, графики)
```

Скачать этот zip (scp, облако, флешка) и проверить, что он открывается. `best.pt` и `best.onnx` — главное, без них сеанс потерян.
Если машина чужая — после скачивания удалить каталог комплекта.

## Проблемы

| Симптом | Что делать |
|---|---|
| `no kernel image is available` / `sm_120` в check_env | PyTorch собран без Blackwell: `pip install --force-reinstall torch torchvision --index-url https://download.pytorch.org/whl/cu128` (или `cu130`) |
| `CUDA out of memory` | в `queue.json` у нужной модели добавить `"batch": 32` (для M — 16) и перезапустить |
| GPU загружен < 50 % | в `env.json` поднять `recommended.workers`; если хватает RAM — `"cache": "ram"`; не держать набор на сетевом диске |
| `ImportError: libGL.so.1` | `pip uninstall -y opencv-python && pip install opencv-python-headless` (setup_linux.sh делает это сам) |
| Обучение падает в начале на загрузке шрифта/весов | шрифт и веса уже в `config/` и `weights/`; проверить, что запускаете из корня комплекта |
| Очередь пишет «Not enough time left» | это защита дедлайна; увеличить `--budget-hours` или убрать модели из `queue.json` |
| Нужен другой размер входа (512/640) | добавить в run `"imgsz": 512`; на телефоне время вырастет примерно пропорционально площади |

## Что потом делать с результатами (дома)

1. Распаковать `results_*.zip` в `runs/allcrops_v1/` (задача M-04: разбор метрик).
2. Подготовить релиз: `python -X utf8 ml-pipeline/prepare_model_release.py runs/allcrops_v1/<S> runs/allcrops_v1/<N> --tag models-allcrops-v1 --update-config` → `artifacts/release/models-allcrops-v1/` (ONNX, SHA256SUMS, MODEL_CARD, RELEASE_NOTES, LICENSE, ATTRIBUTION).
3. Создать на GitHub релиз с тегом `models-allcrops-v1`, текст — `RELEASE_NOTES.md`, приложить все файлы папки.
4. Задача A-03 в `docs/ROADMAP.md`: модель в APK (манифест с 49 классами и 15 культурами), замер на Redmi Note 7 и A56.
