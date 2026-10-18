# Авторы данных и компонентов

Код проекта распространяется под [GNU AGPL-3.0](LICENSE). Модели обучены на данных и компонентах ниже; при использовании весов сохраняйте эти ссылки.

## Данные для обучения моделей allcrops

| Набор | Авторы | Лицензия | Что взято |
|---|---|---|---|
| [PlantDoc Object Detection Dataset](https://github.com/pratikkayal/PlantDoc-Object-Detection-Dataset) | D. Singh, N. Jain, P. Jain, P. Kayal, S. Kumawat, N. Batra. *PlantDoc: A Dataset for Visual Plant Disease Detection*, CoDS-COMAD 2020 | CC BY 4.0 | фото листьев в поле с рамками; очищенная версия clean v2 |
| [PlantVillage Dataset](https://github.com/spMohanty/PlantVillage-Dataset), ревизия `7f7ecc7e1eaca78107e3affe7cb5abd9427e139a` | D. P. Hughes, M. Salathé. *An open access repository of images on plant health…*, arXiv:1511.08060, 2015; S. P. Mohanty, D. P. Hughes, M. Salathé. *Using Deep Learning for Image-Based Plant Disease Detection*, Frontiers in Plant Science, 2016 | CC BY-SA 3.0 | одиночные листья; рамки построены по маскам авторов |
| [OLID I: An Open Leaf Image Dataset of Bangladesh's Major Crops](https://doi.org/10.5281/zenodo.8105154) | N. A. Orka, M. N. Uddin, F. M. Toushique, M. S. Hossain, 2023 | CC BY 4.0 | листья огурца и томата: здоровые, дефициты N/K, вредители |
| [Cucumber Disease and Freshness Classification Dataset – Curated Annotations](https://doi.org/10.5281/zenodo.16816441) | A. C. Menco Tovar, E. Puertas, J. C. Martinez-Santos, 2025 (на основе набора Sultana et al., 2022, [Mendeley y6d3z6f8z9](https://data.mendeley.com/datasets/y6d3z6f8z9/1)) | CC BY 4.0 | листья огурца: здоровые, антракноз, бактериальное увядание, ложная мучнистая роса |

Изменения относительно источников: удаление точных дубликатов, разбиение по группам, рамки целого листа (у части снимков Zenodo — автоматические, помечены), повтор редких классов. Подробно — `ml-pipeline/build_allcrops_v1.py` и `report.json` набора.

### Дополнение ext_v2 (модели allcrops_v2)

Модели `allcrops_v2_*` дообучены также на внешних наборах Kaggle: Plant Pathology 2021 FGVC8 (Thapa et al., Cornell), D-KAP (Sharma et al. 2022), PlantCity 2025 (Khan et al.; яблоня, вишня, виноград, томат; ODbL 1.0), Esca grapes (Alessandrini et al., CC BY 4.0), Corn Leaf Disease Polagan (MIT), Cucumber Leaf Disease (Apache 2.0), Potato Disease Leaf PLD (Rashid et al. 2021), Potato Leaf Disease in uncontrolled environment (Shabrina et al. 2023, CC BY 4.0), Potato leaf Ethiopia (CC0), Soyabean 4-class (MIT), Pumpkin leaf diseases Bangladesh (CC BY 4.0), SmartBerry (Ihianle, CC BY 4.0), Vietnamese Tomato Leaf Disease (Apache 2.0), Real-image tomato leaf disease (CC BY 4.0), Tomato spider mites (Apache 2.0), Pepper bell leaf disease (CC BY 4.0). Ссылки, лицензии и сколько снимков взято из каждого — [docs/data/SOURCES.md](docs/data/SOURCES.md).

Веса, обученные на PlantVillage (CC BY-SA 3.0), публикуются с указанием авторства. Вопрос, распространяется ли условие «share-alike» на веса модели, юридически не решён; проект открыт, поэтому совместимость сохраняется в любом случае.

## Программные компоненты и модели

| Компонент | Лицензия | Где используется |
|---|---|---|
| [Ultralytics YOLO](https://github.com/ultralytics/ultralytics) (YOLO11, YOLO26) | AGPL-3.0 | обучение и экспорт детекторов; веса — производные работы |
| [Vosk](https://alphacephei.com/vosk/), модель `vosk-model-small-ru-0.22` | Apache-2.0 | офлайн-распознавание речи; модель скачивается с сайта авторов, в релизах проекта не перевыкладывается |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | запуск моделей на телефоне |
| JNA | Apache-2.0 / LGPL-2.1 | зависимость Vosk |
| DEIMv2 | собственная лицензия правообладателя (all rights reserved), см. `android-app/app/src/main/assets/licenses/DEIMv2-LICENSE.md` | только экспериментальный режим lab-сборки; **веса DEIMv2 проектом не публикуются** |
