# Продолжение текущей работы

Сначала PROJECT_GUIDE.md, docs/session/accuracy_work_state.json и [PROTOCOL.md](PROTOCOL.md). Проверить реальные get_usage_limits и сохранённое время ожидания. Если другой текущий turn уже работает, не дублировать его. Не запускать старый Start-AccuracyStudy.ps1 или architecture_queue.py: очередь80 отменена пользователем.

## Один GPU-процесс

```powershell
.\ml-pipeline\Get-AccuracyStatus.ps1
```

Актуально поле **current_status** из active.status_path = docs/experiments/yolo26s_focus_v1/status.json. Старые architecture_status/progress в выводе — исторические сведения других компонентов. Состояние текущей S берётся из её runs/detect/accuracy_v1_yolo26s_seedNN/results.csv; stderr — из active_training.json. Проверить PID вместе с временем создания через TrainingProcess.ps1. Отсутствие смены статуса при росте CSV нормально; тихий heartbeat не должен уведомлять без причины.

Только если процесс действительно завершился, а серия не окончена:

```powershell
.\ml-pipeline\Start-Yolo26sFocus.ps1
```

Launcher запускает скрытый WSL-процесс. Готовые экспорты переиспользуются; незавершённая S продолжает last_complete.pt тем же trainer/адаптером. Проверить лог с возобновлением; не принимать новый checkpoint за новый seed. При ошибке сохранить сообщение и установить причину до повторного запуска. Вторую GPU-нагрузку не запускать.

## Общая val на CPU

Можно оценивать уже готовые экспорты во время последовательного обучения. Изолированная среда accuracy_python.sh использует CPU ONNX, два потока; не запускать для этой цели PyTorch GPU.

```powershell
$taskSeeds=@(20261003..20261012 | ForEach-Object { [string]$_ })
wsl.exe --distribution AgroVoice-ML --user root --exec bash /mnt/f/agrovoice-ai/ml-pipeline/accuracy_python.sh /mnt/f/agrovoice-ai/ml-pipeline/evaluate_accuracy_pipeline.py --models yolo26n yolo26s --seeds $taskSeeds
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/summarize_accuracy_seeds.py
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/build_yolo26s_focus_report.py
```

SKIP незавершённого seed не является оценкой. После status=completed10 повторить общую val с **--require-full**. Данные/ядро оценки не менять. Исходные исследования и архив старой очереди сохраняются. Не вызывать package_accuracy_models.py для текущего APK: он может сменить пилотные классификаторы, которые Борис попросил сохранить.

## Только после десяти S и всех парных оценок

```powershell
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/package_yolo26s_focus.py
wsl.exe --distribution AgroVoice-ML --user root --exec bash /mnt/f/agrovoice-ai/ml-pipeline/accuracy_python.sh /mnt/f/agrovoice-ai/ml-pipeline/evaluate_yolo26s_focus_test.py
```

Оба guard отказывают до полного набора. Выбор N12 фиксирован; S выбирается по общей val. Test-lock исключает смену выбранных весов после итогового test. Отказ старого evaluate_accuracy_final_test.py требовать80 закономерен: его не редактируем и не обходим. Новая серия имеет отдельное пользовательское основание и протокол.

Сохранить docs/android/accuracy_v1/verification.json, текущие benchmark/summary.json, batch-результаты и бинарные traces в архив0.8, не удаляя исходное свидетельство. Повысить Android versionCode/versionName до следующего свободного0.9+; проверить все8 IDs и SHA неизменных семи режимов.

```powershell
.\ml-pipeline\Build-AccuracyApk.ps1 -Install
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
& $taskAdb -s b861c4e0 shell am start -S -n com.agronomy.phytomonitor/.FieldValidationActivity --es photos 01_potato_late_blight.jpg,05_tomato_septoria_2026.jpg,09_cucumber_downy_mildew_2024.jpg
```

Дождаться **нового** complete summary с24 результатами и фактическими входами. Затем:

```powershell
& $taskAdb -s b861c4e0 pull /sdcard/Android/data/com.agronomy.phytomonitor/files/field_validation docs/android/accuracy_v1
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/verify_accuracy_phone.py
.\ml-pipeline\Measure-AccuracyPhone.ps1 -Series 10
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/summarize_accuracy_phone.py
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/build_yolo26s_focus_report.py
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/snapshot_accuracy_sources.py
```

Проверить реальные кнопки, фото/галерею и выборS/N; восстановить наблюдавшийся пользовательский выбор. После APK-паритета current_model_lab.json должен быть verified с новым SHA. Обновить инструкции, отчёт07, журнал, state по реальным лимитам. Остановить именно автоматику agrovoice через automation_update, когда текущая задача закончена. Новую автоматику не создавать. Отменённые ветки не отмечать полными десятью обучениями.
