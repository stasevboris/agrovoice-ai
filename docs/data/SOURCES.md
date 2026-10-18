# Внешние наборы ext_v2 (дополнение к allcrops_v1)

Набор `ml-pipeline/dataset_ext_v2` (в git не идёт) — снимки с Kaggle для тех же 49 классов `taxonomy_v1` (порядок и ID классов не менялись). Собран 18.10.2026 скриптом `ml-pipeline/external/build_external_v2.py`; источники — `ml-pipeline/external/sources_v2.json`, сопоставление папок с классами — `mapping_v2.json`. Все числа ниже — из `dataset_ext_v2/report.json` (отпечаток `a5be23e8…`).

## Как собирался

1. **Метка** — только из папки (или таблицы) источника. Детектор листьев класс не назначает.
2. **Повторы.** pHash 8 поворотов/отражений; снимок с расстоянием ≤ 6 бит до любого из 94 401 снимка текущих источников (PlantDoc, PlantVillage, OLID, Zenodo) удалён как `duplicate_of_current`. Внутри новых данных близкие копии собраны в группы, берётся один представитель; группа целиком идёт в один split.
3. **Отбор:** не больше 1 500 снимков на класс в домене field и 600 в lab; split train/val/test = 80/10/10 по группам.
4. **Рамки листьев.** Где в источнике есть рамки листьев (`tomato_vn`) — они. Иначе — детектор листьев YOLO26s, обученный на PlantDoc clean v2 (`leaf_yolo26s_cleanv2.pt`, порог 0,25, CPU). Здоровый снимок — все найденные листья; больной — до 3 крупнейших листьев (≥ ½ площади самого крупного), остальное обрезается; если мелкий неразмеченный лист попадает внутрь обрезки — снимок исключён. Ничего не найдено — исключён (кроме крупных планов: рамка во весь кадр).
5. **Части.** Train разбит на 4 сбалансированные по классам части по 3 318 снимков (`images/part01…part04`): на арендованной GPU можно взять столько частей, сколько успевает.

Итог: 16 645 снимков, 33 049 рамок; train 13 272 / val 1 665 / test 1 708; field 11 584, lab 5 061. При записи исключено: 772 — лист не найден, 1 015 — неразмеченный лист внутри обрезки.

## Источники

«Всего» — снимков в использованных папках; «повтор текущих» — удалены как копии PlantDoc/PlantVillage/OLID/Zenodo; «уникальных» — после свёртки внутренних копий; «взято» — в наборе.

| id | Kaggle | Лицензия | Происхождение | Домен | Всего | Повтор текущих | Уникальных | Взято | Классы |
|---|---|---|---|---|---:|---:|---:|---:|---|
| pp2021 | [thatgeeman/640p-plant-pathology-2021…](https://www.kaggle.com/datasets/thatgeeman/640p-plant-pathology-2021-resized-to-w-x-640) | Plant Pathology 2021 FGVC8 (Thapa et al., Cornell); копия Kaggle CC0 | [FGVC8](https://www.kaggle.com/c/plant-pathology-2021-fgvc8), сады США | field | 11 310 | 10 | 11 279 | 4 179 | яблоня: ржавчина, здоровая, парша |
| dkap | [hsmcaju/d-kap](https://www.kaggle.com/datasets/hsmcaju/d-kap) | DbCL 1.0 | Sharma et al. 2022, Кашмир | field | 205 | 13 | 183 | 39 | яблоня: здоровая, парша |
| apple_pc | [codewithsk/apple-leaf-disease-dataset-plantcity-2025](https://www.kaggle.com/datasets/codewithsk/apple-leaf-disease-dataset-plantcity-2025) | ODbL 1.0 | PlantCity, Khan et al. 2025, Пакистан | lab | 1 030 | 86 | 358 | 336 | яблоня: здоровая |
| cherry_pc | [codewithsk/cherry-leaf-disease-on-plantcity-2025](https://www.kaggle.com/datasets/codewithsk/cherry-leaf-disease-on-plantcity-2025) | ODbL 1.0 | PlantCity, Пакистан | lab | 625 | 42 | 219 | 197 | вишня: здоровая |
| corn_polagan | [ndisan/corn-leaf-disease](https://www.kaggle.com/datasets/ndisan/corn-leaf-disease) | MIT | поле в Polagan, Индонезия; листья на бумаге | lab | 4 000 | 998 | 2 988 | 1 804 | кукуруза: ржавчина, серая пятнистость, здоровая, северный гельминтоспориоз |
| cucumber_lk | [kaushigihanml/cucumber-leaf-disease-dataset](https://www.kaggle.com/datasets/kaushigihanml/cucumber-leaf-disease-dataset) | Apache 2.0 | снимки автора, 1366×768 | lab | 2 553 | 235 | 1 134 | 883 | огурец: ложная мучнистая роса, здоровый |
| esca | [nirmalsankalana/esca-grapes-black-measles-dataset](https://www.kaggle.com/datasets/nirmalsankalana/esca-grapes-black-measles-dataset) | CC BY 4.0 (Mendeley 89cnxc58kj) | Alessandrini et al., Марке, Италия | field | 1 770 | 0 | 1 768 | 1 288 | виноград: эска, здоровый |
| grape_pc | [codewithsk/grapes-leafs-disease-7-classes-plantcity-2025](https://www.kaggle.com/datasets/codewithsk/grapes-leafs-disease-7-classes-plantcity-2025) | ODbL 1.0 | PlantCity, Пакистан | field | 1 857 | 0 | 724 | 678 | виноград: здоровый |
| pepper_bell | [kafilatmusa11/pepper-bell-leaf-disease](https://www.kaggle.com/datasets/kafilatmusa11/pepper-bell-leaf-disease) | CC BY 4.0 | неизвестно; почти всё — копии PlantVillage | lab | 6 425 | 6 378 | 47 | 47 | перец: здоровый |
| potato_pld | [rizwan123456789/potato-disease-leaf-datasetpld](https://www.kaggle.com/datasets/rizwan123456789/potato-disease-leaf-datasetpld) | DbCL 1.0 | Rashid et al. 2021, Пенджаб | lab | 4 072 | 1 103 | 2 646 | 1 658 | картофель: альтернариоз, здоровый, фитофтороз |
| potato_ue | [warcoder/potato-leaf-disease-dataset](https://www.kaggle.com/datasets/warcoder/potato-leaf-disease-dataset) | CC BY 4.0 (Mendeley ptz377bwb8) | Shabrina et al. 2023, Индонезия | field | 548 | 0 | 503 | 460 | картофель: здоровый, фитофтороз |
| potato_eth | [nirmalsankalana/potato-leaf-healthy-and-late-blight](https://www.kaggle.com/datasets/nirmalsankalana/potato-leaf-healthy-and-late-blight) | CC0 | поля Эфиопии | field | 430 | 22 | 381 | 340 | картофель: здоровый, фитофтороз |
| soy_in | [syraverma/soyabean-4-class-leaf-disease-dataset](https://www.kaggle.com/datasets/syraverma/soyabean-4-class-leaf-disease-dataset) | MIT | реальные поля (часть набора Soyabean Leaf Disease) | field | 204 | 0 | 204 | 204 | соя: здоровая |
| pumpkin_bd | [tahmidmir/pumpkin-leaf-diseases-dataset-from-bangladesh](https://www.kaggle.com/datasets/tahmidmir/pumpkin-leaf-diseases-dataset-from-bangladesh) | CC BY 4.0 | поля Бангладеш | field | 400 | 0 | 391 | 299 | тыква: мучнистая роса |
| smartberry | [isiborihianle/smartberry-strawberry-field-image-dataset](https://www.kaggle.com/datasets/isiborihianle/smartberry-strawberry-field-image-dataset) | CC BY 4.0 | Ihianle, SmartBerry, Нигерия | field | 125 | 0 | 100 | 98 | земляника: здоровая |
| tomato_pc | [codewithsk/tomato-leaf-disease-from-plantcity-2025](https://www.kaggle.com/datasets/codewithsk/tomato-leaf-disease-from-plantcity-2025) | ODbL 1.0 | PlantCity, Пакистан | field | 14 848 | 16 | 3 014 | 2 468 | томат: 9 классов |
| tomato_vn | [aresusayhi/vietnameses-tomato-disease-dataset](https://www.kaggle.com/datasets/aresusayhi/vietnameses-tomato-disease-dataset) | Apache 2.0 | VTLD, Вьетнам; рамки листьев источника | field | 3 427 | 1 399 | 1 531 | 1 531 | томат: 9 классов |
| tomato_real | [vidyawaykule/real-image-tomato-leaf-disease-dataset](https://www.kaggle.com/datasets/vidyawaykule/real-image-tomato-leaf-disease-dataset) | CC BY 4.0 | реальные снимки, метки проверены специалистом (по описанию) | lab | 517 | 17 | 187 | 133 | томат: 6 классов |
| tomato_mites | [mohammeda5/tomato-spider-mites-dataset](https://www.kaggle.com/datasets/mohammeda5/tomato-spider-mites-dataset) | Apache 2.0 | повреждения паутинным клещом | lab | 121 | 116 | 3 | 3 | томат: клещ |

## Скачаны, но ничего не дали или отклонены

| id | Kaggle | Причина |
|---|---|---|
| bbox4 | [mahin661/plant-disease-detection-bounding-box-dataset](https://www.kaggle.com/datasets/mahin661/plant-disease-detection-bounding-box-dataset) | все 2 410 снимков — копии PlantVillage |
| cherry_kh | [khalil18/cherry-dataset](https://www.kaggle.com/datasets/khalil18/cherry-dataset) | все 3 509 — копии текущих источников |
| cucumber_bd | [sujaykapadnis/cucumber-disease-recognition-dataset](https://www.kaggle.com/datasets/sujaykapadnis/cucumber-disease-recognition-dataset) | все 640 — копии набора Zenodo (он построен на этом наборе) |
| corn_nlb | [rabbityashow/corn-leaf-diseasesnlb](https://www.kaggle.com/datasets/rabbityashow/corn-leaf-diseasesnlb) | куски листа, вырезанные по маске на чёрном фоне с произвольным поворотом: не целый лист и не то, что видит камера телефона (просмотр 18.10.2026) |
| escab | [projectlzp201910094/eapplescab](https://www.kaggle.com/datasets/projectlzp201910094/eapplescab) | макроснимки пятен без целого листа; «фон» — размытые части растения |
| tomato_slif | [romiyalgeorge/slif-tomato-dataset](https://www.kaggle.com/datasets/romiyalgeorge/slif-tomato-dataset) | общие планы с одной меткой на снимок; рамки отмечают пятна, а не листья |
| citrus_hlb | [oarcanjomiguel/citrus-greening](https://www.kaggle.com/datasets/oarcanjomiguel/citrus-greening) | лицензия GPL-2.0; у HLB уже ~5 000 снимков PlantVillage в v1 |

## Ограничения

- Рамки 91 % снимков (15 072 из 16 645) поставлены детектором листьев без ручной проверки (геометрия каждого снимка — `manifest.jsonl`, поле `geometry`; превью — `previews/boxes_by_class.jpg`). На кадрах с густой листвой (земляника, соя, перец) детектор находит не все листья: часть листьев остаётся без рамки.
- `tomato_pc` содержит повороты одних и тех же снимков (11 818 из 14 848 свёрнуты как копии). Повороты на произвольный угол pHash ловит не всегда, поэтому val/test ext_v2 может быть слегка завышен. Решения о модели — по field val/test набора allcrops_v1 (PlantDoc), а не по ext.
- Домены условные: `lab` = одиночный лист на однотонном фоне, `field` = снимок в поле или теплице.
- Классы не покрыты внешними данными: 20 из 49 (ext_v2 добавляет снимки только к 29 классам; см. `report.json` → `per_class`). Серая пятнистость кукурузы — всего 6 снимков.
