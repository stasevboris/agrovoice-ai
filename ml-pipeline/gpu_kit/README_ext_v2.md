# Дополнение ext_v2 к комплекту AgroVoice (дообучение на внешних снимках)

Дополнение распаковывается **поверх** уже распакованного комплекта `agrovoice_gpu_kit_20261016` (та же корневая папка) и добавляет:

- `datasets/ext_v2/` — внешние снимки с Kaggle для тех же 49 классов (`taxonomy_v1`, порядок классов не менялся). Повторы снимков PlantDoc/PlantVillage/OLID/Zenodo и внутренние копии убраны по pHash. Рамки листьев — из источника или от детектора листьев (класс — всегда из папки источника). Обучающая часть поделена на сбалансированные по классам части `images/part01`, `part02`, … Числа — в `datasets/ext_v2/report.json`.
- `datasets/allcrops_v2_p1`, `allcrops_v2_p2`, …, `allcrops_v2_all` — только yaml-файлы: train = весь `allcrops_v1` + первые K частей ext_v2. Снимки не копируются.
- `queue_ext_v2.json` — очередь дообучения с весов готовых моделей v1 (S, затем N).
- `train_queue.py` — новая версия (умеет `dataset` и путь к весам в очереди; старая `queue.json` работает как прежде).

Проверено на CPU 18.10.2026 на распакованной копии комплекта с этим дополнением: `--smoke`, затем дообучение с `runs/.../best.pt` на `allcrops_v2_p1` (доля train 0,5 %, 1 эпоха): веса перенесены полностью, оценка по 8 наборам, экспорт ONNX `[1, 53, 3549]`, паритет < 0,001. На 5090 не запускалось.

## Когда запускать

Только **после** основной очереди `queue.json`: дообучение стартует с `runs/allcrops_v1_yolo26s_416_s20261003/weights/best.pt`. Если модели v1 нет, её строка очереди завершится ошибкой, и очередь перейдёт к следующей.

## Как запустить

```bash
# в папке комплекта, после основной очереди
unzip -o ../agrovoice_gpu_kit_20261016_ext_v2.zip -d ..     # Windows: Expand-Archive ... -DestinationPath .. -Force
python train_queue.py --queue queue_ext_v2.json --smoke      # 2–3 мин: проверка путей на крошечной выборке
python -u train_queue.py --queue queue_ext_v2.json --budget-hours 2 2>&1 | tee -a logs/queue_ext_v2.log
python package_results.py
```

## Сколько частей брать

По умолчанию берутся все части (`datasets/allcrops_v2_all`). Если времени мало, в `queue_ext_v2.json` замените `"dataset"` на `datasets/allcrops_v2_p1` (или `p2`, …): эпоха будет короче. Сколько снимков в каждой части — `datasets/ext_v2/report.json` → `parts`.

Ориентир (не измерен): эпоха пропорциональна числу снимков train. Время эпохи основной очереди видно в `runs/allcrops_v1_yolo26s_416_s20261003/results.csv`; эпоха на `allcrops_v2_all` будет дольше во столько раз, во сколько больше снимков (`report.json` → `v1_splits.train` + `ext_train_images`).

## Что получится

Как и в основной очереди: `runs/allcrops_v2_*/weights/best.pt`, `best.onnx`, `eval/evaluation.json`. Оценка теперь по четырём наборам:

- `field_*`, `lab_*` — те же val/test, что у v1: **сравнивать модели v1 и v2 только по ним**;
- `ext_field_*`, `ext_lab_*` — val/test из ext_v2 (новые фотографии, другие страны и камеры).

Решение «брать v2 или нет» — по `field_test` и `field_val`, как и для v1.
