# Аудит источников новой YOLO26s: фактический срез

ОбновленоUTC: 2026-10-05T06:29:29.268537+00:00.

**Новый набор ещё не готов; новых принятых train-фото0, нового GPU-обучения0.** M отменена, M03/M04/M05checkpoint сохранены. APK0.16 и девять режимов не менялись.

## PlantVillage: все прежние культуры

Публичный закреплённый snapshot полностью получен; требуемые77388файлов (38694RGB и38694масок) проверены по авторскимGitblob. Архив2508320118байт, SHA256 `08c1367853c3b4afe636755ea84d454f73920e82ffcfd3453bd26b1c5d26a070`. Это38694оригинала, не77388обучающих изображений. Источник: [официальныйрепозиторий](https://github.com/spMohanty/PlantVillage-Dataset/tree/7f7ecc7e1eaca78107e3affe7cb5abd9427e139a), лицензияCCBY-SA3.0. SHA/версии сохранены вsnapshot и исходномmanifest.

Полный CPU-аудит: 38694пар; 27688без автоматических замечаний, 11006с замечаниями. Отсутствие флага не является приёмкой. Флаги пересекаются; их нельзя складывать как число отклонённых фото.

| ID | Прежний класс | Пар оригинал–маска | Без автофлагов |
|---:|---|---:|---:|
|0|Apple Scab Leaf|630|608|
|1|Apple leaf|1645|1625|
|2|Apple rust leaf|275|275|
|3|Bell_pepper leaf|1478|1441|
|4|Bell_pepper leaf spot|997|951|
|5|Blueberry leaf|1502|1499|
|6|Cherry leaf|854|847|
|7|Corn Gray leaf spot|513|0|
|8|Corn leaf blight|985|0|
|9|Corn rust leaf|1192|0|
|10|Peach leaf|360|356|
|11|Potato leaf|152|152|
|12|Potato leaf early blight|1000|991|
|13|Potato leaf late blight|1000|998|
|14|Raspberry leaf|371|371|
|15|Soyabean leaf|5090|4866|
|16|Squash Powdery mildew leaf|1835|0|
|17|Strawberry leaf|456|454|
|18|Tomato Early blight leaf|1000|926|
|19|Tomato Septoria leaf spot|1771|987|
|20|Tomato leaf|1591|995|
|21|Tomato leaf bacterial spot|2127|2117|
|22|Tomato leaf late blight|1909|817|
|23|Tomato leaf mosaic virus|373|0|
|24|Tomato leaf yellow virus|5357|2721|
|25|Tomato mold leaf|952|856|
|26|Tomato two spotted spider mites leaf|1676|1670|
|27|grape leaf|423|0|
|28|grape leaf black rot|1180|1165|

Все29 ID0…28 сохраняются. Ноль в последнем столбце чаще означает неизвестную физическую группу; он не означает отмену культуры. Прежние PlantDocклассы не удаляются. Девять прочих категорий исходногоPVархива не подменяют эти29 и пока не приняты без отдельного разбора.

| Автоматический флаг | Число пар с этим флагом |
|---|---:|
|mask_spans_background_or_truncated_leaf|3048|
|mask_area_or_background_leakage|413|
|multiple_components|193|
|physical_leaf_group_unknown_or_ambiguous|10295|
|empty_mask|1|
|mask_rgb_geometry_mismatch|1|

## OLID: авторские новые состояния

Проверены три архивныхMD5 и все1047оригиналов (249огурцов/798томатов). Все1047предложений действительно просмотрены на66листах; точныеID/наблюдения иSHAиндекса сохранены в[visual_review.json](olid_full_review_r2/visual_review.json). Этот просмотр относится к1024pxсжатымpreview. Авторские диагнозы не переопределялись по картинке. [Статьяметодов](https://www.frontiersin.org/journals/plant-science/articles/10.3389/fpls.2023.1251888/full), [выпуск1.1.0/CCBY4](https://zenodo.org/records/8105154).

| Исходная метка | Оригиналов |
|---|---:|
|cucumber__K|50|
|cucumber__N|89|
|cucumber__N_K|76|
|cucumber__healthy|34|
|tomato__JAS_MIT|32|
|tomato__K|36|
|tomato__LM|207|
|tomato__MIT|200|
|tomato__N|47|
|tomato__N_K|40|
|tomato__healthy|236|

LM=минёр, не плесень; MIT=повреждение клещом без автоматического подтверждения двупятнистого вида; JASMIT=комбинированное повреждение. N/K/NK — отдельные дефициты, не инфекции. Здоровье сохраняется по авторскому происхождению метки, не по нашей визуальной догадке.

Часть томатных образцов — связанные составные листья. Уточнён[контрактобъекта](ANNOTATION_CONTRACT_DRAFT.md): один авторский изолированный листовой образец может иметь соединённые боковые пластинки; общая рамка должна включать их. Отдельный лежащий рядом лист не объединяется молча. Перваяlargest-componentверсия могла пропускать боковые части. Пересчёт из SHA-проверенных оригиналов сохраняется отдельно, без перезаписи предыдущих доказательств.

Текущий оригинальныйCPU-аудит: `running`, проверено352/1047. EXIF-время исследуется для блоков съёмки, но не объявляетсяIDрастения. Чёрный фон и обе стороны листа ограничивают перенос на поле.

## Огурец, поле и независимая проверка

640листовых оригиналов Sultana/Zenodo16816441 проинвентаризированы;165предложений рамок просмотрены. Разметка bacterialwilt/downymildew не становится целолистовой автоматически. FreshLeaf не доказывает здоровье любого неоднозначного примера. Старый огуречный unknown29 сохраняется только в старых графах; его новый источник-обоснованный перевод ещё не завершён.

Полевые PLDD-UP15519 и PakistanTomato7200 подтверждены карточками и авторскимиSHA, однако завершённыеZIP ещё не получены обычной загрузкой. Эти числа не входят в принятыйtrain. Wageningen823JPG содержит разметку участков ткани на78кадрах, не готовые рамки всех листьев. Родственные копии Sultana/Zenodo не являются независимымtest.

Проверка1919ранних кандидатов против3354исторических записей дала7внутренних и89исторических близких связей. Это связи, не уникальные фото. Повороты/обратные стороны/новые38694PV требуют расширенного аудита. Историческиеval/test не переходят вtrain. Для новых состояний нужны честно описанные группы и поддержкаval/test; случайное разбиение файлов безIDрастений не выдаётся за полевую независимость.

## Что именно будет обучаться

Два свежих YOLO26s Detect на RX9060XT16ГБ/WSL, последовательно seed20261003/04. Сохранить все29PlantDocID и все прежние культуры; новые достоверные состояния добавлять отдельно. COCOвеса уже на диске. Проект416,batch16,FP32/AMPoff,AdamWlr0.001,cos_lr, максимум60эпох,patience15.60 — прежний проверенный предел, не установленный оптимум новых данных. Первый неизменный технический запуск входит в этидва; третьего автоматического пилота/десяти обучений нет.

ДоGPU: окончательные рамки/происхождение/дедупликация/группы/схема/разбиение, точный фактический состав сообщён Борису, затем data+recipe lock и строгийpreflight. После двух: отдельныеval/кривые/ошибки, выбор поval до новогоtest, корректныйevaluator расширенной головы, экспорт и отдельный новый режимAPK с собственной схемой при сохранении девяти примеров/всехголосовыхи полевых функций. Старые защищённые evaluator/данные не меняются.

AP не процент правильных диагнозов; уверенность не медицинская/агрономическая достоверность. Процент поражения не заявляется без подходящихметок.

Реальныйлимит при проверке2026-10-05T06:27:10Z: остаток79%пятичасового/19%недельного. Сохранённое старое ожидание до05октября01:00Minsk исполнено; новой обязательной паузы на этомсрезе нет.


## Актуализация после завершения CPU-процессов

Контроль 2026-10-05T07:10:36.936055+00:00. Старые running/7/89 в предыдущем срезе являются историей. OLID original_geometry_r3 завершён:1047 оригиналов/938 captureEXIF/42 flags; geometry_adjudication:1047 решений/4manualoverride, все66+3 листа визуально просмотрены. PlantVillage29:38694 проверенных пар/11006 flags; extra9:15611/3662 flags, всего54305 RGB в38 категориях. Full overlap:43735 записей (40381 новых кандидатов+3354 история),28/792/37 связей;9extra ещё не включены. Все числа относятся к исходным кандидатам и проверкам, trainaccepted0. GPU0, новая серия не началась. Принятие геометрии не решает достоверность класса, физические группы и независимый split.


## Review of exact/near-duplicate links — 2026-10-05T08:41:41.193149+00:00

The full-source overlap file was reviewed by relation type; its SHA-256 was checked against the completed run status. The 792 candidate-to-historical links are all exact decoded-RGB copies across 739 PlantVillage and 53 cucumber-source records. Their historical copies lie in train (632) or val_aux (160), not the historical main test. These candidates remain archived but are excluded from a new independent split. Within new candidate sources, 28 links are exact pixel duplicates: 21 PlantVillage, six OLID, and one cucumber pair. Five OLID duplicate pairs carry conflicting publisher category labels (three tomato N/K, two tomato MIT/N); all ten records stay quarantined pending source metadata, with no diagnosis inferred from appearance. Thirty-seven non-exact links are internal to frozen v2 and do not alter that dataset. No training examples are accepted. Aggregate decisions (not a full row ledger) are in [the review JSON](candidate_overlap_full_r2/REVIEW_DECISIONS_2026-10-05.json) and [human-readable note](candidate_overlap_full_r2/HUMAN_READABLE_REVIEW_2026-10-05.md).


## OLID EXIF capture-session analysis — 2026-10-05T08:47:10.788377+00:00

`olid_original_geometry_r3/records.jsonl` contains 938 timestamped originals and 109 without capture time. `DateTimeOriginal` is consistent with `DateTimeDigitized`; eight generic `DateTime` tags differ by two days, so capture grouping uses `DateTimeOriginal`. The 938 dated records fall on five dates and are strongly crop/state clustered; date-only holdout would drop whole class/crop support. Eight same-second groups cover16 records; these may be grouped conservatively but do not establish plant identity. The Zenodo archives expose category-folder images, no per-image plant/session key. Physical grouping and an independent all-class split are still unresolved; no labels or boxes were accepted. Detailed date/class counts: [audit](olid_capture_metadata_r1/README.md), machine data: [audit.json](olid_capture_metadata_r1/audit.json).

## Полный аудит r3 и ограничения групп — 5 октября, 12:25 UTC

Проверены все 55 992 новых кандидата против 3 354 исторических записей. Дополнительные 15 611 PlantVillage-оригиналов девяти категорий проверены инкрементально: новых совпадений по прежнему правилу не найдено; 857 ранее сохранённых связей унаследованы без изменения байтов. Это не исключает пропущенные повороты, обрезки или другие стороны одного листа.

Создан настоящий построчный [реестр 55 992 кандидатов](candidate_overlap_full_r3/candidate_disposition.jsonl): 792 точные исторические копии; 46 записей с внутренними совпадениями требуют дедупликации; 10 записей пяти конфликтных пар OLID в карантине; 55 144 без совпадений по этому правилу всё ещё требуют проверки семантики, рамок и групп. Предыдущий REVIEW_DECISIONS r2 содержал сводные числа, а не полный построчный реестр; текущий реестр r3 устраняет этот пробел. Все пять пар OLID сверены с оригинальными ZIP: JPEG-байты внутри каждой пары также идентичны, метки противоречат.

Авторский leaf-map проверен по закреплённому архиву. Связи физических листьев, копий и временных групп распространены транзитивно: [реестр ограничений](group_closure_r1/candidate_split_constraints.jsonl). 1 781 кандидат связан с историческими train-семействами и пригоден только для train после остальных проверок; 447 связаны с историческими контрольными семействами и не допускаются в новый train или независимый test. Это ограничения групп, а не 2 228 независимых растений. Остальные неизвестные группы не объявлены независимыми.

Все 37 исторических близких пар реально просмотрены: одинаковые исходные фотографии, девять пар между разделами и 19 пар с различием наборов классов. [Дополнение об ограничениях прежних метрик](HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md). Старые данные/оценки сохранены; 71 затронутое изображение временно исключается только из проекта новой базы до проверки разметки. Поддержка всех 29 исходных классов при таком исключении остаётся ненулевой.

Все новые CPU-аудиты завершены и проверены SHA; не запускать повторно без обнаруженной ошибки. Новых принятых train-примеров0, GPU-запусков0. Остаются достоверные соответствия состояний, рамки видимого листа, неизвестные физические группы и независимые val/test. Оба DOCX завершены до этого аудита и не пересобираются; новые исходники и результаты хранятся отдельно с SHA и точкой продолжения.
