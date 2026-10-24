# AgroVoice: запуск проверяющего модуля отказа

## Комплект и задача

Архив: `artifacts/gpu_kit/rejection_20261023/agrovoice_rejection_20261023.zip`.
Рядом — SHA256SUMS, отчёт CRC и копия этой инструкции. Для кафедры скопируйте
папку на внешний SSD, затем на внутренний SSD машины. Данные и pretrained-веса
уже внутри; интернет нужен для установки Python-пакетов. Драйверы не устанавливаются.

Порядок: **MobileNetV3-small → DINOv2-small**. Замороженный backbone извлекает
признаки, обучается только небольшая линейная голова. Это первый пилот,
не полное дообучение зрительной сети. Детектор S фиксирован.

Модуль принимает реальные вырезки S, различает 15 культур и «отклонить».
Ответ сохраняется только при согласии по культуре и прохождении порога.
Модуль убирает ответ, но не заменяет культуру или диагноз собственным вариантом.
`__reject__` — выход отдельного модуля, не новый ID `taxonomy_v1` и не класс «не лист».
Предметы и неизвестные растения учитываются отдельно по причинам отказа.

**При подготовке обучение не запускалось.** Проверены CPU-инференс обоих backbone,
правила разбиения/отказа и ONNX-схема на необученной тестовой голове. Обучение,
RTX 5090 и Android проверяющего модуля ещё не проверены.

## Данные и ограничения

- 10 391 вырезка: 5 988 train, 2 843 calibration, 1 560 audit. Это вырезки,
  а не новые независимые снимки. Все 15 культур и выход отказа представлены в train.
- Известные культуры — из исходных GT: GT-вырезки и сопоставленные с GT рамки S.
  Никаких новых диагнозов по изображениям не назначалось.
- Фасоль и DeepWeeds — слабый outlier exposure по метке сцены. Вид растения
  на каждой рамке не подтверждался: возможны фон и сопутствующие растения.
- OpenImages — небольшой просмотренный набор предметов. Авторство/лицензии:
  `data/provenance/`, `ATTRIBUTION.md`. Права на pretrained ImageNet также сохраняют ограничения.
- 116 несопоставленных train-рамок исключены: `data/review_queue.jsonl`.
  Среди них возможны неразмеченные листья, автоматических негативных меток нет.
- Calibration/audit разделены по исходным наблюдениям и camera/date. Группы
  не дробятся, поэтому размеры частей неравны. Точные вырезки между частями проверены;
  все почти-дубликаты этим не исключены. Audit не участвует в выборе головы/порога.
- Test-изображения не загружались и не вошли в комплект. Исходные manifest
  могут перечислять test-метаданные, но обработаны только train/val записи.
- Полевая оценка охватывает 13 культур. Огурец/апельсин имеют лабораторные
  вырезки; их полевое качество этим опытом не подтверждается.
- Оценка цепочки использует фиксированные реальные предложения S: verifier
  заново обрабатывает вырезки, сам детектор в оценке не исполняется повторно.
  Это не измерение камеры или телефона. Сохраняется смещение прежнего выбора S.

## RTX 5090, Windows / PowerShell

Нужны Python 3.11–3.13 и работающий `nvidia-smi`. В папке с архивом:

```powershell
Expand-Archive -LiteralPath .\agrovoice_rejection_20261023.zip -DestinationPath .\rejection_training
Set-Location .\rejection_training\agrovoice_rejection_20261023
powershell -ExecutionPolicy Bypass -File .\setup_windows.ps1
if ($LASTEXITCODE -ne 0) { throw 'Установка или проверка не прошла' }
.\.venv\Scripts\python.exe check_environment.py --encoder dinov2 --device cuda
if ($LASTEXITCODE -ne 0) { throw 'DINO не прошёл проверку' }
```

Первым — MobileNet. После его завершения и просмотра отчёта — DINO:

```powershell
$deadline = (Get-Date).AddHours(4).ToString('o')
.\.venv\Scripts\python.exe -u train.py --encoder mobilenet --device cuda --out .\runs\mobilenet_01 --deadline $deadline
```

```powershell
$deadline = (Get-Date).AddHours(4).ToString('o')
.\.venv\Scripts\python.exe -u train.py --encoder dinov2 --device cuda --out .\runs\dinov2_01 --deadline $deadline
```

Четыре часа — пример предоставленного бюджета, не замер длительности.
Задайте реально разрешённое время. Процессы запускайте последовательно,
окно держите открытым, сон компьютера отключите. По умолчанию 20 эпох головы,
FP32, batch признаков 32, два CPU-процесса чтения. При OOM снизьте `--batch 16`,
записав изменение. Новый опыт — новый `--out`; одинаковые данные и seed сохраняйте.

## RTX 5090, Linux

```bash
unzip agrovoice_rejection_20261023.zip -d rejection_training
cd rejection_training/agrovoice_rejection_20261023
bash setup_linux.sh
source .venv/bin/activate
python check_environment.py --encoder dinov2 --device cuda
```

Если проверки прошли, по одной команде:

```bash
python -u train.py --encoder mobilenet --device cuda --out runs/mobilenet_01 --deadline "$(date -d '+4 hours' --iso-8601=seconds)"
python -u train.py --encoder dinov2 --device cuda --out runs/dinov2_01 --deadline "$(date -d '+4 hours' --iso-8601=seconds)"
```

При ошибке сначала прочитайте `runs/<имя>/training.log`. Не заменяйте отсутствующую
GPU молчаливым переходом на CPU и не запускайте два извлечения признаков одновременно.

## Домашняя RX 9060 XT: существующий WSL

В Windows venv PyTorch CPU-only. Для RX используйте `AgroVoice-ML` и
`/opt/agrovoice/venv`; CUDA-установщик на RX не запускать. Из PowerShell в
`F:\agrovoice-ai` распакуйте в **новую** папку WSL, без наложения на старый опыт:

```powershell
wsl -d AgroVoice-ML -u root --exec /opt/agrovoice/venv/bin/python -m zipfile -e /mnt/f/agrovoice-ai/artifacts/gpu_kit/rejection_20261023/agrovoice_rejection_20261023.zip /root/rejection_stage_20261023
wsl -d AgroVoice-ML -u root --exec /opt/agrovoice/venv/bin/python -m pip install -r /root/rejection_stage_20261023/agrovoice_rejection_20261023/requirements.txt
wsl -d AgroVoice-ML -u root --exec bash /root/rejection_stage_20261023/agrovoice_rejection_20261023/run_rx.sh check --encoder mobilenet
```

Ручной запуск до 23:00 МСК дня команды:

```powershell
$trainingDate = (Get-Date).ToString('yyyy-MM-dd')
$deadline = "${trainingDate}T23:00:00+03:00"
wsl -d AgroVoice-ML -u root --exec bash /root/rejection_stage_20261023/agrovoice_rejection_20261023/run_rx.sh --encoder mobilenet --out runs/mobilenet_01 --deadline $deadline
```

Для DINO сначала `run_rx.sh check --encoder dinov2`, затем та же команда с
`--encoder dinov2 --out runs/dinov2_01`. После начала резерва поздний старт
отклоняется. Завтра нужна новая ручная команда с новой датой; автоматического
продолжения и перехода deadline на следующий день нет.

За 20 минут до deadline прекращаются новые шаги, за 5 минут родительский процесс
останавливает группу. Head-checkpoint сохраняется после завершённой эпохи.
В актуальном исходнике `core.py` допустим более ранний домашний deadline того же дня,
не позднее 23:00 МСК. На 24.10 отдельно разрешено только до 21:00 МСК:
используется `2026-10-24T21:00:00+03:00`, остановки 20:40/20:55.
Переносимый архив от 23.10 содержит прежнее правило «только 23:00»; для более
раннего deadline скопируйте актуальный `ml-pipeline/gpu_kit/rejection/core.py`
в корень распакованного комплекта. В сегодняшней WSL-копии это уже сделано.
При остановке первого извлечения признаков головы ещё может не быть — это не
успешное обучение. Окно WSL держите открытым.

## Лог, остановка и продолжение

Из папки комплекта Windows:

```powershell
Get-Content .\runs\mobilenet_01\training.log -Tail 15 -Wait
```

`Ctrl+C` здесь прекращает только просмотр. Мягкая остановка:

```powershell
New-Item -ItemType File -Path .\runs\mobilenet_01\STOP
```

Linux/WSL: `touch runs/mobilenet_01/STOP`. Дождитесь выхода процесса. Для
продолжения переименуйте только управляющий `STOP` в `STOP.saved`, повторите
команду с тем же `--out`, добавив `--resume` и актуальный deadline.
`--epochs` — общий предел, не дополнительные эпохи поверх checkpoint.
Данные/backbone менять при resume нельзя: их хеши проверяются.

После аварии может остаться `run.lock`. Только убедившись, что старого процесса
нет, переименуйте его в `run.lock.saved`. Не удаляйте каталог опыта и checkpoints.
Последовательные запуски используют разные `--out` для MobileNet и DINO.

## Как понять результат

`status: finished` означает завершение программы, **не улучшение надёжности**.
Главный файл — `runs/<имя>/report.json`:

- `no_feasible_policy`: на calibration нет порога, сохраняющего предложенные
  95% правильных ответов S. Это отрицательный результат, а не повод скрыть ограничение.
- `audit_not_improved`: выбранный на calibration порог не выполнил критерии на audit.
- `candidate`: на этом audit сохранена нужная полнота и снизилось среднее принятие
  неизвестных источников. Проверяйте каждый источник отдельно; это ещё не полевой допуск.

95% — предложенное ограничение потерь относительно **правильных ответов S**,
не вероятность диагноза. Порог выбирается только на calibration; не менять его
по результатам audit. Отчёт включает FP/FN/F1 и принятие предметов, фасоли,
DeepWeeds со знаменателями. Ноль ошибок на нескольких предметах не доказывает надёжность.

MobileNet сохраняет `verifier.onnx` с проверкой PyTorch/ONNX. DINO сохраняет
голову и оценку признаков; Android-экспорт и мобильная пригодность DINO не заявлены.
Сохраните исходный комплект: pretrained backbone остаётся в `weights/`.

## Забрать результаты

После выхода процессов, в папке комплекта:

```powershell
.\.venv\Scripts\python.exe collect.py
```

Linux/WSL: `python collect.py` в том же окружении. Для WSL доступен полный путь
`/opt/agrovoice/venv/bin/python /root/rejection_stage_20261023/agrovoice_rejection_20261023/collect.py`.
Скопируйте `rejection_results_*.zip` и `.report.json` на SSD. Сборщик проверяет
CRC, сохраняет optimizer головы и feature cache, отказывается копировать при `run.lock`.

Никаких автоматических отправок наружу или установки модели в APK нет.
Дальше — независимый OOD-test, разбор ошибок и проверка runtime на Redmi.
