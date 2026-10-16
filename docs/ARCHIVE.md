# Что не хранится в git и где это лежит

Git содержит исходники, конфиги, отчёты и небольшие изображения. Тяжёлые файлы лежат на дисках этого ПК.

## На F: (рабочие, в `.gitignore`)

| Что | Где | Зачем нужно |
|---|---|---|
| JSONL аудитов данных (очередь r2, геометрия PlantVillage/OLID, ~245 МБ) | `docs/experiments/yolo26s_data_v1/**.jsonl` | **нужны** `build_allcrops_v1.py` для пересборки набора |
| Превью и контактные листы проверок (~2 ГБ) | `docs/**/previews/` | визуальные доказательства прежних аудитов |
| WAV и BIN проверок голоса и телефона (~400 МБ) | `docs/android/**` | история проверок APK 0.10–0.16 |
| Исходные данные (PlantVillage ZIP, OLID, Zenodo, WUR) | `ml-pipeline/source_candidates/` | источники для набора allcrops |
| Наборы | `ml-pipeline/dataset_clean_v2`, `dataset_allcrops_v1(_smoke)`, `dataset_accuracy_v1` | обучение и пересборка |
| Модели APK (`.onnx`) и голос Vosk | `android-app/app/src/main/assets/models`, `.../voice/ru` | сборка APK (план — GitHub Release, задача G-05) |
| Комплект для арендованной GPU | `artifacts/gpu_kit/` | обучение (`pack_gpu_kit.py` пересобирает) |
| Текущий APK 0.16 | `artifacts/apk/` | установка на телефон |

## На E: — `E:\Перенесено_с_F` (архив, 16–17.10.2026)

Полный список «откуда → куда» — `E:\Перенесено_с_F\ЧТО_ПЕРЕНЕСЕНО.txt`. Пути повторяют прежние: `F:\agrovoice-ai\runs\detect` → `E:\Перенесено_с_F\agrovoice-ai\runs\detect`.

- `agrovoice-ai\runs\` — все прежние обучения (N/S/M clean v2, accuracy_v1, пилот M);
- `agrovoice-ai\artifacts\apk\` — APK 0.2–0.15; `artifacts\documents\` — DOCX от 05.10 с рендерами страниц;
- `agrovoice-ai\ml-pipeline\dataset`, `dataset_clean_v1`, `plantdoc_yolo.zip` — исторические наборы и исходный архив PlantDoc;
- `agrovoice-ai\ml-pipeline\runtime\wsl\ext4.vhdx` — WSL-дистрибутив AgroVoice-ML (ROCm), зарегистрирован в WSL по этому пути;
- `agrovoice-ai\git\agrovoice_full_63d9a7e.bundle` — полная история git до чистки 17.10 (включая все тяжёлые файлы старого коммита `63d9a7e`). Восстановить: `git clone E:\Перенесено_с_F\agrovoice-ai\git\agrovoice_full_63d9a7e.bundle restored`.
- `agrovoice-ai-12.10.2026\` — копия проекта от 12.10.

DOCX описания темы и истории разработки (05.10) продублированы в git: `docs/thesis/2026-10-05/`.
