# Авторы данных и компонентов

Код проекта распространяется под [GNU AGPL-3.0](LICENSE). Ниже перечислены источники действующих экспериментов и подготовленных наборов для будущего обучения; при использовании весов сохраняйте соответствующие ссылки.

## Данные для обучения моделей allcrops

| Набор | Авторы | Лицензия | Что взято |
|---|---|---|---|
| [PlantDoc Object Detection Dataset](https://github.com/pratikkayal/PlantDoc-Object-Detection-Dataset) | D. Singh, N. Jain, P. Jain, P. Kayal, S. Kumawat, N. Batra. *PlantDoc: A Dataset for Visual Plant Disease Detection*, CoDS-COMAD 2020 | CC BY 4.0 | фото листьев в поле с рамками; очищенная версия clean v2 |
| [PlantVillage Dataset](https://github.com/spMohanty/PlantVillage-Dataset), ревизия `7f7ecc7e1eaca78107e3affe7cb5abd9427e139a` | D. P. Hughes, M. Salathé. *An open access repository of images on plant health…*, arXiv:1511.08060, 2015; S. P. Mohanty, D. P. Hughes, M. Salathé. *Using Deep Learning for Image-Based Plant Disease Detection*, Frontiers in Plant Science, 2016 | CC BY-SA 3.0 | одиночные листья; рамки построены по маскам авторов |
| [OLID I: An Open Leaf Image Dataset of Bangladesh's Major Crops](https://doi.org/10.5281/zenodo.8105154) | N. A. Orka, M. N. Uddin, F. M. Toushique, M. S. Hossain, 2023 | CC BY 4.0 | листья огурца и томата: здоровые, дефициты N/K, вредители |
| [Cucumber Disease and Freshness Classification Dataset – Curated Annotations](https://doi.org/10.5281/zenodo.16816441) | A. C. Menco Tovar, E. Puertas, J. C. Martinez-Santos, 2025 (на основе набора Sultana et al., 2022, [Mendeley y6d3z6f8z9](https://data.mendeley.com/datasets/y6d3z6f8z9/1)) | CC BY 4.0 | листья огурца: здоровые, антракноз, бактериальное увядание, ложная мучнистая роса |

Изменения относительно источников: удаление точных дубликатов, разбиение по группам, рамки целого листа (у части снимков Zenodo — автоматические, помечены), повтор редких классов. Подробно — `ml-pipeline/build_allcrops_v1.py` и `report.json` набора.

### Дополнение ext_v2 (модели allcrops_v2)

Для будущего дообучения моделей `allcrops_v2_*` подготовлены внешние наборы Kaggle: Plant Pathology 2021 FGVC8 (Thapa et al., Cornell), D-KAP (Sharma et al. 2022), PlantCity 2025 (Khan et al.; яблоня, вишня, виноград, томат; ODbL 1.0), Esca grapes (Alessandrini et al., CC BY 4.0), Corn Leaf Disease Polagan (MIT), Cucumber Leaf Disease (Apache 2.0), Potato Disease Leaf PLD (Rashid et al. 2021), Potato Leaf Disease in uncontrolled environment (Shabrina et al. 2023, CC BY 4.0), Potato leaf Ethiopia (CC0), Soyabean 4-class (MIT), Pumpkin leaf diseases Bangladesh (CC BY 4.0), SmartBerry (Ihianle, CC BY 4.0), Vietnamese Tomato Leaf Disease (Apache 2.0), Real-image tomato leaf disease (CC BY 4.0), Tomato spider mites (Apache 2.0), Pepper bell leaf disease (CC BY 4.0). Ссылки, лицензии и сколько снимков взято из каждого — [docs/data/SOURCES.md](docs/data/SOURCES.md).

Веса, обученные на PlantVillage (CC BY-SA 3.0), публикуются с указанием авторства. Вопрос, распространяется ли условие «share-alike» на веса модели, юридически не решён. Открытость кода сама по себе не доказывает совместимость всех условий данных и весов; перед публикацией необходима отдельная проверка.

## Дополнительные подготовленные корпуса (19.10.2026)

| Источник | Авторство и условия | Подготовленная производная |
|---|---|---|
| [iBean](https://github.com/AI-Lab-Makerere/ibean/) | AIR Lab Makerere University, NaCRRI; MIT по авторскому README/LICENSE | Фасоль для unsupported-crop; исходные метки и splits, pHash-проверка. Не метки сои и не новые классы taxonomy_v1 |
| [DeepWeeds](https://github.com/AlexOlsen/DeepWeeds), [статья](https://doi.org/10.1038/s41598-018-38343-3) | A. Olsen et al., Scientific Reports, 2019; CC BY 4.0 для изображений и аннотаций согласно README | Поднабор видов, разделение по виду и camera/day, исключение близких копий; без выдуманных диагнозов. Класс Negative не использован как отсутствие листа |
| [Open Images](https://storage.googleapis.com/openimages/web/download_v7.html) | Авторы отдельных Flickr-фото, CC BY 2.0; аннотации CC BY 4.0 | Малый просмотренный поднабор предметов; оригинальная страница, автор, license URL и изменения/отбор сохранены в `dataset_nonplant_openimages_v2/manifest.jsonl` и `review.json` |
| [MASSIVE](https://github.com/alexa/massive) | J. FitzGerald et al., Amazon, 2022; CC BY 4.0 | Русский поднабор версии 1.1 с исходными намерениями, слотами и split; содержание фраз не изменено |
| [FLEURS](https://huggingface.co/datasets/google/fleurs) | A. Conneau et al., Google, 2022; CC BY 4.0 | Русские аудио/TSV закреплённой ревизии; исходный float WAV, добавлены проверенные manifests, пути, SHA256 и длительности |
| Сценарии AgroVoice | Авторский синтетический корпус проекта, условия AGPL-3.0 проекта | `ml-pipeline/assistant/scenarios.json`; это не реальные записи пользователей, не экспертные диагнозы и не проверенный агрономический справочник |

Веса на этих корпусах ещё не обучались. Обработка не меняет условий исходников;
атрибуцию каждого фото Open Images сохранять вместе с поднабором. PlantWild и
PlantSeg в новый обучающий пакет не включены: условия разных страниц семейства
расходятся. Подробнее — [отбор источников](docs/research/DATA_AND_MODEL_SELECTION_2026-10-19.md).

## Программные компоненты и модели

| Компонент | Лицензия | Где используется |
|---|---|---|
| [Ultralytics YOLO](https://github.com/ultralytics/ultralytics) (YOLO11, YOLO26) | AGPL-3.0 | обучение и экспорт детекторов; веса — производные работы |
| [Vosk](https://alphacephei.com/vosk/), модель `vosk-model-small-ru-0.22` | Apache-2.0 | офлайн-распознавание речи; модель скачивается с сайта авторов, в релизах проекта не перевыкладывается |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | запуск моделей на телефоне |
| [HTMX 2.0.11](https://htmx.org) | Zero-Clause BSD | веб-панель; лицензия и исходные URL/SHA в `server/static/vendor/` |
| [Leaflet 1.9.4](https://leafletjs.com) | BSD-2-Clause | карта осмотров; лицензия и исходные URL/SHA в `server/static/vendor/` |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) | ODbL, © OpenStreetMap contributors | необязательная интернет-подложка карты, атрибуция показывается на карте |
| JNA | Apache-2.0 / LGPL-2.1 | зависимость Vosk |
| [sherpa-onnx 1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) | Apache-2.0; встроенный eSpeak NG — GPL-3.0 | локальный синтез речи; точные исходники и лицензии: `android-app/app/src/main/assets/licenses/TTS-NOTICE.md` |
| Piper Denis medium INT8 | репозиторий голосов декларирует MIT; исходные записи Denis — CC0 по MODEL_CARD | встроенный русский голос; оригинальный архив upstream с SHA в `assets/tts/manifest.json`, без публикации весов проектом; происхождение и оговорки в TTS-NOTICE |
| DEIMv2 | собственная лицензия правообладателя (all rights reserved), см. `android-app/app/src/main/assets/licenses/DEIMv2-LICENSE.md` | только экспериментальный режим lab-сборки; **веса DEIMv2 проектом не публикуются** |
# Дополнительные источники: обычные фото и OPTIMA (19.10.2026)

- **iNaturalist, индивидуальные фотографии CC BY / CC0.** Авторы, ID наблюдений,
  ссылки на страницы фото, код лицензии и SHA256 сохранены в
  `ml-pipeline/external/web_photo_sources_v1.json`; решения просмотра —
  `web_photo_review_v1.json`. Вид — исходная community/research-grade метка,
  наличие листа и частичные рамки — визуальная разметка ИИ. Диагнозы не назначались.
  Изображения: `ml-pipeline/dataset_web_leaf_presence_v1/`. При передаче сохранять
  индивидуальную атрибуцию; вырезки обозначать как производные изображения.
- **OPTIMA RGB/MS, Blok, Polder, Peller, van Daalen (Wageningen University & Research).**
  DOI: https://doi.org/10.5281/zenodo.6778647, лицензия **CC BY 4.0**.
  Подготовлен исторический RGB-поднабор v1; актуальная найденная версия —
  https://doi.org/10.5281/zenodo.8339572, здесь не скачана.
  `ml-pipeline/dataset_optima_rgb_v1/source_metadata.json` сохраняет оригинальную
  запись. Исходные рамки областей болезни преобразованы в отдельный COCO;
  они не являются рамками целых листьев taxonomy_v1. Multispectral не включён.
