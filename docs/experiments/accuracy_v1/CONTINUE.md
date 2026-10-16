# Историческая инструкция — очередь80 отменена

**Для текущей работы читать [yolo26s_focus_v1/CONTINUE.md](../yolo26s_focus_v1/CONTINUE.md). Борис отменил дальнейшее DETR-обучение; далее только10YOLO26s и фиксированный лучшийN. Не возобновлять приведённый ниже старый launcher80. Все APK-примеры остаются.**

## Прежнее продолжение пяти направлений

Сначала PROJECT_GUIDE.md, docs/session/accuracy_work_state.json и PROTOCOL.md. Старые20 обучений завершены; в новой очереди80 заданий. Не менять исходные clean v2, производный manifest, параметры или три защищённых скрипта. FanControl и оборудование не трогать.

## Лимит и текущий процесс

Проверить настоящий get_usage_limits. При остатке≤15% пятичасового окна сохранить состояние через update_accuracy_state.py с фактическими остатками и ждать01:00 следующего календарного дня Minsk. Сохранённое resume_not_before_local соблюдать даже при более раннем сбросе окна. Недельный запас также сохранять. Обучение продолжает работать без токенов.

```powershell
.\ml-pipeline\Get-AccuracyStatus.ps1
```

Смотреть running, architecture_status, актуальные stdout/stderr из active_training.json. Старые status.json/progress.json могут описывать остановленный компонент; актуальный DETR пишет detector_progress.json. Если worker работает, не запускать другой GPU-процесс. Если реально вышел — разобраться в сохранённой ошибке и продолжить:

```powershell
.\ml-pipeline\Start-AccuracyStudy.ps1 -AllArchitectures
```

После исправления экспорта YOLO26s её60 завершённых эпох не повторяются. Обёртка экспортирует обычную голову nms=None, проверяет форму1×34×3549 и вызывает неизменный evaluator. Его nms=False означало бы другую, one-to-one голову. DEIM впервые реально обучается, сохранённые эпохи продолжаются из last.pt. Его известное предупреждение grid_sampler о недетерминированности не означает ошибку обучения.

Первый DEIM завершил60 эпох и экспорт; сбой строгого raw TopK guard исправлен после диагностики всех349 val-фото. Новая проверка каждого seed требует строгого паритета полного набора score≥0,1 (минимум APK) и стабильного AP всех кандидатов score≥0,001; background raw строки не объявляются идентичными. Политика и допуски записаны в PROTOCOL.md и export_validation.json. Рецепт обучения и общий evaluator не изменены. При повторном сбое после60 эпох --export-only позволяет проверить и экспортировать best.pt без GPU-обучения; training code SHA не перезаписывается. Не выдавать status=passed без фактической полной проверки.

## Когда появились новые завершённые экспорты

Проверять одновременно manifest.status=completed и export.status=passed, реальные SHA и содержимое. CPU-пробы с необученной головой не упаковывать. Сначала добавить реальные готовые режимы в source manifest:

```powershell
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/package_accuracy_models.py
$taskSeeds=@(20261003..20261012 | ForEach-Object { [string]$_ })
wsl.exe --distribution AgroVoice-ML --user root --exec bash /mnt/f/agrovoice-ai/ml-pipeline/accuracy_python.sh /mnt/f/agrovoice-ai/ml-pipeline/evaluate_accuracy_pipeline.py --seeds $taskSeeds
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/summarize_accuracy_seeds.py
```

Это только CPU ONNX, не второй GPU-worker. Незавершённые seed явно пропускаются; существующие результаты с прежними графами и текущим кодом переиспользуются. Когда все готовые10 seed есть, использовать --require-full. Не утверждать10 завершённых оценок по10 сообщениям SKIP. Сегментатор и учитель сами по себе не являются диагнозом на фото: оцениваются соответствующие masked/distilled цепочки.

После оценки ещё раз package_accuracy_models.py: только при10 completed экспортов и10 paired-оценках он выбирает веса по AP50–95 полной цепочки. Для классификатора в финальном APK используется детектор с тем же seed. До этого выбор preliminary; число повторов хранится в manifest. Для новых APK повышать versionCode/versionName в Gradle; готовые предыдущие APK не удалять. Сборка автоматически сохраняет отличающийся предыдущий файл той же версии в archive.

```powershell
.\ml-pipeline\Build-AccuracyApk.ps1 -Install
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
& $taskAdb -s b861c4e0 shell am start -S -n com.agronomy.phytomonitor/.FieldValidationActivity --es photos 01_potato_late_blight.jpg,05_tomato_septoria_2026.jpg,09_cucumber_downy_mildew_2024.jpg
```

Дождаться нового complete summary с тремя результатами на каждый актуальный mode, не принять старый файл. Затем:

```powershell
& $taskAdb -s b861c4e0 pull /sdcard/Android/data/com.agronomy.phytomonitor/files/field_validation docs/android/accuracy_v1
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/verify_accuracy_phone.py
.\ml-pipeline\Measure-AccuracyPhone.ps1 -Series 10
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/summarize_accuracy_phone.py
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/build_accuracy_report.py
```

Перед перезаписью текущих сводок сохранить предыдущую версию verification/benchmark summary. Бинарные следы и JSON содержат реальные Android-входы и графы; verify сравнивает их с desktop ORT и файлами текущего APK. current_model_lab.json становится verified только после этой проверки. APK0.4 содержит4 режима и12 результатов; это не готовность всех пяти направлений.

Текущий APK0.8 содержит8 режимов и24 результата: все пять направлений имеют обученный пилот, включая «Маска → диагноз» и «Ученик MobileNet». Его телефонный паритет проверяет все потенциально видимые DETR строки≥0,1 на фактическом входе первого фото; все300 raw строк сохранены. Другие стадии сравниваются целиком. Предыдущие APK0.4/0.5/0.6/0.7, verification/benchmark summary/batch и архивы traces сохранены. Первый seed всех8 компонентов завершён; очередь продолжает остальные. Учитель и сегментатор сами по себе кнопками диагнозов не являются. Следующий APK делать0.9 или выше, не перезаписывать0.8.

Все кнопки пилотов уже установлены. Пока дообучаются seed04..12, не повторять APK/телефонные серии только ради появления ещё одного seed: сохранять и оценивать новые экспорты на общей val, контролировать очередь. Пересобирать при нужном исправлении, существенном подтверждённом результате либо после полного выбора по десяти seed. Последнюю финальную выбранную версию обязательно заново проверить на телефоне, включая численный паритет и10 серий скорости. Пилотные веса0.8 сохраняются для сравнения.

Дополнительно запустить evaluate_accuracy_teacher.py --seeds с доступными десятью seed через WSL accuracy_python.sh. Это только CPU ONNX и общая evaluate(), не второе GPU-обучение. Результат учителя — отдельный вспомогательный контроль полной фотографии; source/graph/core/launcher SHA сохраняются. Summarize учитывает фактическое число готовых seed. Не объявлять готовый учитель готовностью ученика или всех пяти направлений.

## Завершение

Нужно80 реальных заданий (либо честно подтверждённый технический отказ конкретного варианта), полные paired-оценки, выбор поval, отдельная итоговая проверка выбранных вариантов наtest без настройки, экспорт/телефон и APK с доступными вариантами. Для нормального завершения всех80: evaluate_accuracy_final_test.py проверяет завершённые экспорты и все10 paired-val оценок каждого из6 новых режимов, фиксирует selection до открытияtest и запрещает менять выбор при повторе. Запуск через WSL accuracy_python.sh, как общий evaluator. Сейчас финальныйtest НЕ запускать. Если конкретное семейство подтвердило технический отказ, не обходить guard: документировать отказ и явно зафиксировать отдельный сокращённый финальный протокол.

Далее разбор классов и независимые полевые снимки; новыйtrain-data требует другой версии эксперимента. Обновить шестой отчёт, индекс/README, журнал, точку продолжения. Только затем остановить существующую автоматику agrovoice инструментом automation_update. Не создавать дубль наблюдения.
