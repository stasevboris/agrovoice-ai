# Продолжение YOLO26s → YOLO26m

Рабочая папка `F:\agrovoice-ai`. В этой инструкции перечислены будущие действия; их наличие не означает, что они выполнены. Фактическое состояние — [REPORT.md](REPORT.md), [status.json](status.json), [состояние сеанса](../../session/accuracy_work_state.json). Никакую вторую GPU-нагрузку не запускать. Прежнюю очередь 80, DETR и повторные классификаторы не возобновлять.

## 1. Довести S и сохранить рабочий APK

Сначала проверить `ml-pipeline/logs/active_training.json`, реальный процесс, S status и логи. Если S-worker жив — только наблюдать и оценивать уже экспортированные S на CPU. Восстановление после его фактического выхода — `ml-pipeline/Start-Yolo26sFocus.ps1`. Данные, рецепт и защищённые исходники остаются прежними.

```powershell
.\ml-pipeline\Get-AccuracyStatus.ps1
# Оценивать только завершённые экспорты; остальные seed перечислить при их готовности.
wsl.exe --distribution AgroVoice-ML --user root --exec bash /mnt/f/agrovoice-ai/ml-pipeline/accuracy_python.sh /mnt/f/agrovoice-ai/ml-pipeline/evaluate_accuracy_pipeline.py --models yolo26n yolo26s --seeds 20261003 20261004 20261005 20261006 20261007 20261008 20261009 20261010 20261011 20261012
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/summarize_accuracy_seeds.py
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/build_yolo26s_focus_report.py
```

После десяти реальных S-экспортов и десяти полных общих S val / десяти N val:

```powershell
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/package_yolo26s_focus.py
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/evaluate_yolo26s_focus_test.py
```

Первый скрипт фиксирует S по val и сохраняет остальные семь примеров / N12; второй блокирует выбор до исторического test. Не менять старый guard80. Затем повысить versionCode/versionName выше уже установленной версии, подготовить отдельные 142 голосовых WAV с -IncludeLab и проверить новую версию:

```powershell
.\ml-pipeline\Prepare-VoiceControlsSamples.ps1 -ReportFolder docs/android/yolo26s_final_phone/audio_samples -IncludeLab
.\ml-pipeline\Test-ModelLabPhone.ps1 -Label yolo26s_final_phone -Voice -Lab -Benchmark
```

Этот помощник собирает и реально устанавливает APK, запускает десять инструментальных тестов (камера/галерея/голос + журнал/сравнение + заметка/TTS) с отдельной папкой доказательств, три фото на всех режимах, Android/Desktop-паритет и десять серий bitmap-анализа. Предыдущий общий отчёт/тензоры/замеры сохраняются в `docs/android/archive_models/<APK-SHA>`. Он не меняет аппаратное управление или Play Защиту. При ошибке остановиться и сохранить доказательства; успешную установку подтверждать фактическим Success и SHA.

Не запускать прежние `Test-VoiceControlsPhone.ps1` / `build_voice_controls_report.py` для новой версии: они относятся к исторической 0.11 и папке voice_v3. Не переписывать старые голосовые доказательства.

## 2. Полный технический M03

Только после завершённого S-worker, всех десяти S и заблокированного выбора S:

```powershell
.\ml-pipeline\Start-Yolo26mPilot.ps1
```

Он использует уже скачанный `ml-pipeline/yolo26m.pt`, SHA закреплён в [protocol_lock.json](protocol_lock.json). Повторная загрузка не нужна. Полные 60 эпох на прежних данных; один запуск — технический пилот, не итог качества M. Восстановление после фактического выхода M-pilot допускается тем же launcher: используется `last_complete.pt`, готовый экспорт не обучается повторно. Если OOM / технический отказ — записать журнал и логи; не менять batch в этой серии.

После фактического завершения/экспорта M03:

```powershell
wsl.exe --distribution AgroVoice-ML --user root --exec bash /mnt/f/agrovoice-ai/ml-pipeline/accuracy_python.sh /mnt/f/agrovoice-ai/ml-pipeline/evaluate_yolo26m.py --seeds 20261003
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/package_yolo26m.py --pilot
```

Упаковка требует реальный M-граф, три desktop-сравнения и полную общую val; добавляет M девятым режимом к восьми итоговым S-примерам. Далее в `VoiceCommands.modelIds` добавить **последним** `yolo26m`, обновить подсказку/грамматику/нумерацию только при фактическом наличии M. Проверить фразу «модель девять», обеспечить сохранение всех прежних функций. Повысить версию APK относительно уже установленной S-версии. Новый режим должен содержать обученный граф, а не COCO-веса.

```powershell
.\ml-pipeline\Prepare-VoiceControlsSamples.ps1 -ReportFolder docs/android/yolo26m_pilot_phone/audio_samples -IncludeM -IncludeLab
.\ml-pipeline\Test-ModelLabPhone.ps1 -Label yolo26m_pilot_phone -Voice -Lab -Benchmark
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/mark_yolo26m_phone_gate.py --serial b861c4e0 --evidence docs/android/yolo26m_pilot_phone
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/build_yolo26m_report.py
```

`-IncludeM` допустим только после упаковки реального девятого графа; добавляет два WAV для «модель девять», остальные 114 команды/контрольные фразы сохраняет. Сначала все десять Lab native-тестов / все команды без ложных действий, затем 27 визуальных результатов / все девять режимов и десять серий. Gate сверяет план, pilot-SHA, текущий APK, отдельные голосовые доказательства, свежесть замеров и **APK, извлечённый с реального телефона**. Не создавать phone_gate вручную. Скорость чужого CPU, COCO AP и результаты других телефонов не подставлять.

## 3. Остальные девять M

Только при `phone_gate.json: status=passed` и SHA, совпадающем с M03 / неизменным планом:

```powershell
.\ml-pipeline\Start-Yolo26mStudy.ps1
```

Очередь `yolo26m_queue.py` переиспользует готовый M03, последовательно обучает остальные девять, после выхода каждого GPU-child выполняет общую CPU val. `queue_status.json` — статус всей серии; `status.json` — текущего adapter/pilot. Активный указатель содержит правильный `status_path`. После фактического выхода worker восстановление — тот же Start-Yolo26mStudy.ps1, без параллельных запусков. План, recipe и SHA источников зафиксированы до первого M.

```powershell
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/build_yolo26m_report.py
```

Отчёт включает фактические n, средние, SD и парные разности N/S/M. Неполная серия не называется результатом десяти запусков. Предварительный M03 остаётся в APK до итогового выбора.

## 4. Финальная M и завершение

Только после десяти экспортов / десяти полных val и завершённой очереди:

```powershell
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/package_yolo26m.py
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/evaluate_yolo26m_test.py
```

Первый скрипт выбирает максимальный полный val AP50–95, при равенстве меньший seed, блокирует выбор и сохраняет восемь базовых режимов. Второй допускает исторический test только после блокировки выбора; test не используется для подбора. Повысить APK-версию и выполнить новые проверки:

```powershell
.\ml-pipeline\Prepare-VoiceControlsSamples.ps1 -ReportFolder docs/android/yolo26m_final_phone/audio_samples -IncludeM -IncludeLab
.\ml-pipeline\Test-ModelLabPhone.ps1 -Label yolo26m_final_phone -Voice -Lab -Benchmark
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/mark_yolo26m_phone_gate.py --final --serial b861c4e0 --evidence docs/android/yolo26m_final_phone
.\ml-pipeline\venv\Scripts\python.exe ml-pipeline/build_yolo26m_report.py
```

Сравнить качество и реальную задержку/память; оставить в APK N/S/M и все прежние примеры. Обновить подробные отчёты, карточки моделей, указатель APK и PROJECT_GUIDE.md. Затем остановить автоматику через automation_update. Не останавливать её только после S, пока разрешённая M ещё не проверена. Если M имеет честно проверенный технический отказ — сохранить доказательства и рабочий S/N APK вместо объявления M успешной.

После появления девятого режима старый `build_yolo26s_focus_report.py` не запускать: его проверка состава рассчитана на восемь базовых моделей. Исторический S-отчёт сохраняется; сравнение N/S/M обновляется новым M-report builder.

## Лимиты и ограничения

Проверять actual get_usage_limits. При остатке ≤15% пятичасового окна прекратить разработку, записать точку и 01:00 следующего календарного дня Europe/Minsk; до этого времени только краткая необходимая проверка обучения, даже если окно сбросилось раньше. Недельный лимит также сохранять. FanControl закрыт, HDD/вентиляторы/оборудование не менять. Ничего не публиковать/покупать, внешних сообщений не отправлять. mAP не равен доле правильных диагнозов; болезни огурцов и процент поражения пока не заявляются. Телефонный bitmap-таймер не включает наведение камеры и JPEG-декодирование; десять его серий — не десять обучений.

## Уточнение app-проверок5октября

Версия0.14 добавляет два meaningful native-теста NoteSpeechFlowTest к прежним восьми, поэтому полный -Lab профиль содержит10. M phone guard проверяет OK(10tests), четыре новых JSON-инструментов и все прежние доказательства. Training/evaluation lock/планы/recipe не изменены. Наличие callback PCM-проверки не означает речь людей через микрофон: реальный микрофон отдельно открыт, PCM Vosk подан в production callback без ночного звука. TTS либо даёт настоящий callback при volume0, либо явный отсутствующий offline-голос. Установка голосовых данных автоматически не выполняется. Stage-folder всегда свежая; прошедший S-профиль: yolo26s_final_v15_phone; неудачный v14 сохранён как история.
