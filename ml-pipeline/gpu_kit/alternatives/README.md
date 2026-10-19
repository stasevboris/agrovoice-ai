# Данные для сравнения моделей AgroVoice

Это подготовленные данные и рецепты экспериментов. Ограниченные пилоты YOLO26n,
MobileNetV3-small и Qwen3-0.6B LoRA выполнены: [результаты и ограничения](../../../docs/experiments/home_training_2026-10-19/README.md).
Новые веса в APK не включены; скорость и память на телефонах не проверены. Обучать на
арендованной GPU либо дома в пределах разрешения Бориса от 19.10:
с его команды на обучение до 23:00 МСК того же дня, с сохранением прогресса
и остановкой до срока, без автоматического продолжения завтра (см. AGENTS.md).

## Состав пакета

После распаковки каталоги `dataset_*` должны оставаться рядом:

| Каталог | Назначение | Вход для обучения |
|---|---|---|
| `dataset_vision_training_v1` | Одинаковые данные для YOLO и D-FINE; вырезки для DINOv2/MobileNet | `data.yaml`, `annotations/train.json`, `crops.jsonl` с фильтром `split=train` |
| `dataset_nonplant_openimages_v2` | Малый проверенный набор предметов | Только `train.txt`; авторство каждого изображения в `manifest.jsonl` |
| `dataset_unknown_beans_screened_v1` | Фасоль вне taxonomy_v1 | `train.jsonl`, **не** пустые рамки детектора |
| `dataset_unknown_deepweeds_v1` | Неизвестные виды, разделённые по видам и дате/камере | `train.jsonl`; виды из val/test не использовать в обучении |
| `dataset_assistant_ru_v1` | Синтетические русские команды с контекстом | `train.jsonl` → `messages`; val/test — отдельные проверки |
| `dataset_intents_ru_massive_v1` | Вспомогательное распознавание русских намерений/слотов | Исходные `utt`, `annot_utt`, `intent`, `scenario`; не переименовывать в действия AgroVoice |
| `dataset_asr_ru_fleurs_v1` | Русская речь для ASR | `train.jsonl` → `audio`, `text`; исходный mono float WAV |

Точные количества, исключения и ограничения — `report.json` в каждом каталоге.
Тяжёлые файлы и архивы не входят в git. Веса, CUDA, пакеты внешних моделей в этот
пакет не включены. Существующий `agrovoice_gpu_kit_20261016.zip` остаётся отдельным
проверенным комплектом baseline allcrops.

## Зрение

1. Контроль: YOLO26n/s на конфигурациях, созданных `configure_yolo_pilot.py`
   по [PLAYBOOK](../../../docs/guides/TRAINING_PLAYBOOK.md): без и с просмотренными
   негативами, с одинаковой field-only val и без test. Исходные `data.yaml` и
   `data_with_negatives.yaml` набора используют mixed field+lab val; для нового
   сравнения их напрямую не применять.
2. D-FINE-N, затем S при наличии эффекта. В авторском checkout создать конфиг:

   ```bash
   python configure_dfine.py --repo /work/D-FINE --data /work/data/dataset_vision_training_v1 --out /work/agrovoice_dfine_n.yml
   ```

   Он подключает `configs/dfine/dfine_hgnetv2_n_coco.yml`, устанавливает
   `num_classes=49`, `remap_mscoco_category=False` и пути COCO. Для абляции добавить
   `--negatives`. `img_folder` — корень набора, потому что COCO `file_name` уже
   содержит `images/train/...`. Взять авторский COCO checkpoint с проверенными
   условиями; конфиг **не скачивает веса**. Дальнейшее обучение — по инструкции
   [авторов](https://github.com/Peterande/D-FINE), в отдельном окружении.
   Генерация конфига проверена локально; D-FINE forward/обучение ещё не проверены.
3. DINOv2 ViT-S/14: замороженный backbone и линейная голова на GT-вырезках из
   `crops.jsonl`; MobileNetV3-Large — мобильный контроль, MobileNetV4-Conv-Small —
   дополнительный кандидат. Одинаковое разрешение/рецепт и выбор по field-val.
   Затем обязательно проверять **полную цепочку с предсказанными рамками**:
   высокая точность идеальных вырезок не доказывает качество камеры.
4. OOD: отдельная голова/score, outlier exposure на bean/deepweeds **train**,
   калибровка на val, окончательная проверка на отложенных видах test. Не добавлять
   unknown как произвольный ID в taxonomy_v1. Неизвестный лист — не отрицательный
   пример для детектора листьев.

В строгом наборе отсутствуют train-примеры `corn__healthy`,
`cucumber__bacterial_wilt`, `cucumber__downy_mildew`. Он годится для ограниченного
сравнения, **не для заявления о полноценной поддержке всех классов**. Сравнение
архитектур должно использовать одинаковый набор. Для полного релиза нужны
проверенные рамки этих классов; исходный allcrops_v1 сохранён.

`annotations/train_with_negatives.json` добавляет только train-негативы;
полевые `val_field.json`/`test_field.json` не меняются. Порог отказа выбирать на
val. Старый PlantDoc test сохраняет исторические ограничения почти-дубликатов.
Все пути с `../dataset_nonplant_openimages_v2` остаются внутри распакованного
пакета. Изображения локально могут быть hard link — не менять их на месте.

## Русский помощник

Первый опыт: Qwen3-0.6B, затем Qwen3.5-0.8B. Сначала baseline без дообучения,
потом один ограниченный SFT/LoRA-пилот на `messages` из **train**. В Qwen3 отключить
thinking (`enable_thinking=False`). Сохранять исходный tokenizer/chat template;
loss только на ответе assistant. Не обрезать JSON состояния/целевой ответ молча:
измерить длины токенов выбранным tokenizer перед запуском. Подбирать эпохи и
раннюю остановку по val, не по test. Не смешивать MASSIVE с командами AgroVoice без
отдельного multitask-рецепта: там другие действия и слоты.

FunctionGemma — запасной специализированный опыт: исходные `state`, `utterance`,
`target` сохранены, но нужен **родной формат функций FunctionGemma**. JSON-чат
Qwen не объявляется готовым FunctionGemma-корпусом. LFM2.5-2.6B — резерв для
более сильного устройства: русский заявлен, лицензия отдельная, RAM не измерена.

Выход корпуса — предложение действий, не выполнение. Контекстный исполнитель
этого JSON-протокола реализован в Android и проверяет аргументы и состояние. Оценивать точное действие и
аргументы, отрицания, диктовку, отмену, недопустимые команды; совпадение красивого
текста не показатель успеха. Синтетический regression test не заменяет новые
формулировки независимых говорящих. После SFT сравнить исходную модель и
квантизованный артефакт для телефона на одних и тех же примерах.

## ASR и знания

Сравнить существующий Vosk со Whisper tiny/base multilingual; GigaAM CTC —
сильный русский кандидат при приемлемой стоимости на устройстве. FLEURS содержит
чтение общих текстов, а не тепличные команды. Он подходит для общего WER и
ограниченного ASR-пилота, но не доказывает hands-free управление. Float WAV
преобразовывать в PCM16 только для runtime, которому это нужно; не выдавать
текстовый MASSIVE за аудиокорпус. У FLEURS нет проверенной здесь разметки личности
говорящего: не создавать фиктивные speaker_id из пола или номера файла.

Справочник лечения не обучать на сгенерированных диагнозах/дозировках. Пока нет
проверенных карточек РБ, корпус помощника учит отказу от назначения. Для поиска
сначала SQLite FTS/BM25, multilingual-e5-small — только после измерения пользы.

## Воспроизведение на этом ПК

`train_home_pilot.py` запускает отдельную группу процессов с обязательным
`--deadline` (ISO-время с часовым поясом) и остановкой до него. Режимы `--task`:
`detect` (пара без/с негативами), `crop-probe` (замороженный MobileNet),
`evaluate-detectors` (обычная голова, ONNX/parity, отказы на val), `assistant-sft`
(Qwen LoRA). `--after` позволяет дождаться предыдущего запуска; задачи с большой
памятью выполнять последовательно. Каждый запуск требует нового `--out`.
Точные аргументы и окружение сохраняются в каталоге запуска и отчётах опыта.
SFT использует отдельное проектное окружение с Transformers 4.57.1,
PEFT 0.17.1, Accelerate 1.10.1 и существующим ROCm PyTorch. Готовый snapshot
передаётся через `--model`; загрузки и изменение системного Python не выполняются
тренером. `--smoke` проверяет короткий запуск до большого опыта.

Из корня репозитория, Python из `ml-pipeline/venv` (Pillow, NumPy, SciPy,
imagehash уже установлены):

```powershell
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline/external/prepare_selected.py beans massive fleurs
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline/external/screen_unknown.py
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline/external/prepare_deepweeds.py
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline/external/prepare_vision_alternatives.py
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline/external/prepare_nonplant.py
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline/external/prepare_nonplant.py --review ml-pipeline/external/nonplant_review_v2.json
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline/external/attach_nonplant.py
.\ml-pipeline\venv\Scripts\python.exe -X utf8 ml-pipeline/assistant/prepare_corpus.py
```

Команды требуют отсутствующих/пустых выходных каталогов и не удаляют прежние
данные. Повторно загруженные архивы берутся из кэша после проверки SHA256.
`screen_unknown.py` и `prepare_deepweeds.py` используют существующий индекс
`external/hash_index/current_sources.npy`; без него нужно сначала выполнить
описанную в `external/hash_index.py` индексацию реальных исходных данных.
Review Open Images привязан к конкретным ID: при изменении выборки требуется
новый просмотр, а не применение старых решений к другим снимкам.

Измерения CPU-подготовки не являются скоростью инференса модели. Прежде чем
запускать большое обучение, выполнить короткий forward/export выбранной модели
на арендованной машине. Победителя выбирать по field risk–coverage и замерам
телефона, затем готовить релиз через `prepare_model_release.py`.
