"""Refresh the current experiment report from saved evidence, without running models."""
import hashlib,json
from pathlib import Path
from datetime import datetime,timezone

ROOT=Path(__file__).resolve().parents[1];REPORT=ROOT/'docs/experiments/accuracy_v1';RUNS=ROOT/'runs/accuracy_v1'
def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def sha(p):
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    protected={'train_clean.py':'b7bd5296722c991deab36ac2b2481113cdccc6d96333b3fcb7731efbc16e59a5',
      'evaluate_clean.py':'38706b2a05f9e83f9cbb75bc65f61a1893289208318d05ee77afbb6b949c36a7',
      'run_study.py':'b0f1736dcb999df80edf3ba562e427745507fc0ea9a7d1139353052c7f8ec7ca'}
    for file,digest in protected.items():assert sha(ROOT/'ml-pipeline'/file)==digest,file
    scope=[]
    if (ROOT/'docs/experiments/yolo26s_focus_v1/plan.json').exists():
        scope=['**Исходная очередь80 отменена решением Бориса 4 октября. Этот отчёт сохраняет историю пяти пилотов. '
               'Продолжаются только десять YOLO26s, лучший прежний YOLO26n остаётся в APK; остальные кнопки сохранены как примеры. '
               'Текущее состояние: [отчёт07](07_YOLO26_FOCUS_2026-10-04.md) и [новый протокол](../../experiments/yolo26s_focus_v1/PROTOCOL.md).**','']
    lines=['# Все пять вариантов: фактический ход работы','',*scope,f'Обновлено по файлам: {datetime.now(timezone.utc).isoformat()}.',
      '', 'Это новая разрешённая серия accuracy_v1. Отчёты 01–05 сохраняют предыдущий результат и предложения. '
      'Здесь фиксируется фактическая реализация. Запуск очереди не означает, что все модели уже обучены.',
      '', '## Что именно проверяем','',
      '| Направление | Реализация |','|---|---|',
      '| 1. Лист и отдельный диагноз | YOLO26n предлагает листья без фильтра болезни; MobileNetV3-Large и EfficientNet-B0 классифицируют вырезку |',
      '| 2. DETR | DEIMv2-N / HGNetv2-B0, 30 классов, вход 416, перенос опубликованных COCO-весов |',
      '| 3. Более крупная YOLO | YOLO26s, та же clean v2 и 60 эпох, прежние защищённые trainer/evaluator |',
      '| 4. Сегментация | Маленький U-Net выделяет весь лист; MobileNet обучается на результате этой маски. Это не сегментация больных пятен |',
      '| 5. Дистилляция | EfficientNetV2-S учит MobileNetV3-Large: 0.5 CE + 0.5 KL, температура 3 |',
      '', 'Очередь содержит 80 заданий: 8 компонентов × 10 seed. Учитель и сегментатор — отдельные компоненты, '
      'поэтому число обучений больше числа направлений. Сначала один seed по всем компонентам, затем остальные. '
      'Первый MobileNet сохранён; второй был мягко остановлен после 8 эпох, затем продолжен из checkpoint. '
      'В процессе менялся порядок исполнения, данные и параметры компонента сохранены.',
      '', '## Сколько фактически закончено','',
      '| Компонент | Завершённые обучения и экспорты | Лучшая val компонента |','|---|---:|---:|']
    kinds=['mobilenet_v3_large','efficientnet_b0','yolo26s','deimv2_n','leaf_mask','teacher_efficientnet_v2_s','masked_mobilenet_v3_large','distilled_mobilenet_v3_large']
    evidence={'protected_scripts':protected,'data_sha256':sha(ROOT/'ml-pipeline/dataset_accuracy_v1/manifest.json'),'components':{}}
    for kind in kinds:
        finished=[]
        for run in RUNS.glob(kind+'_seed*'):
            if (run/'manifest.json').exists() and (run/'export.json').exists():
                info=read(run/'manifest.json');ex=read(run/'export.json')
                if info['status']=='completed' and ex['status']=='passed':
                    assert sha(run/'model.onnx')==ex['sha256'];finished.append(info)
        best=max([r['best_validation_score'] for r in finished],default=None)
        label='—' if best is None else f'{best:.5f}'
        lines.append(f'| {kind} | {len(finished)}/10 | {label} |');evidence['components'][kind]=finished
    lines+=['', 'В таблице разные метрики: classifier macro-F1 на истинных вырезках, mask IoU на лабораторных масках, '
      'detector AP50–95. Они не сравниваются напрямую. Контрольный snapshot отражает только уже завершённое.',
      '']
    lines+=['### Отдельное качество на истинных вырезках','',
      'Здесь лист уже выделен правильной разметкой. Выбрана та же эпоха с лучшим GT-crop macro-F1, '
      'которая сохранена в best.pt; это не результат полного фото и не независимый полевой контроль. '
      'Пропущенные детектором листья в эту таблицу не входят, поэтому ниже отдельно оценивается весь путь.',
      '', '| Компонент / seed | Эпоха | macro-F1 | balanced accuracy | accuracy | Вырезок / представленных классов |',
      '|---|---:|---:|---:|---:|---:|']
    evidence['crop_metrics']=[]
    for kind in ['mobilenet_v3_large','efficientnet_b0','teacher_efficientnet_v2_s',
                 'masked_mobilenet_v3_large','distilled_mobilenet_v3_large']:
        for info in sorted(evidence['components'][kind],key=lambda row:row['seed']):
            history=read(RUNS/f'{kind}_seed{info["seed"]}'/'history.json')
            best=max(history,key=lambda row:row['validation']['macro_f1']);m=best['validation']
            assert abs(m['macro_f1']-info['best_validation_score'])<1e-12
            evidence['crop_metrics'].append({'kind':kind,'seed':info['seed'],'epoch':best['epoch'],'validation':m})
            lines.append(f'| {kind} / {info["seed"]} | {best["epoch"]} | {m["macro_f1"]:.5f} | '
                f'{m["balanced_accuracy"]:.5f} | {m["accuracy"]:.5f} | {m["samples"]} / {m["represented_classes"]} |')
    lines+=['','Метрики каждого класса, support и confusion matrix выбранной эпохи сохранены в '
      'progress_evidence.json → crop_metrics; все эпохи — в history.json соответствующего run. '
      'Несколько вырезок одного кадра остаются зависимыми наблюдениями; высокий crop score не означает '
      'такой же процент правильных диагнозов на фото.','']
    queue=read(REPORT/'architecture_status.json');evidence['queue']=queue
    current=queue.get('current',{});progress=None
    for name in ['detector_progress','progress']:
        file=REPORT/f'{name}.json'
        if file.exists() and read(file).get('job')==current.get('id'):progress=read(file)
    evidence['current_progress']=progress
    actual_completed=sum(item.get('status')=='completed' for item in queue.get('jobs',{}).values())
    lines.append(f'По сохранённым статусам заданий: **{queue["status"]}**, завершено {actual_completed}/80 заданий. '
                 f'Текущий компонент: {current.get("id","—")}.')
    if progress:lines.append(f'Последняя сохранённая эпоха: {progress["epoch"]}/{progress["epochs"]}, '
                            f'UTC {progress["updated_utc"]}. Это текущий checkpoint, не готовый вариант.')
    lines+=['', '## Полная цепочка на одной проверочной выборке','',
      'Все режимы заново оцениваются единым CPU ONNX evaluator на 349 фото / 1309 размеченных листьях. '
      'Он учитывает неверные диагнозы, пропущенные листья и лишние рамки. В отличие от старого Ultralytics evaluator, '
      'YOLO-кандидат выбирает один класс; поэтому ниже сравниваем только модели из этой таблицы.',
      '', '| Режим | AP50–95 | AP50 | Верный класс + рамка среди всех GT листьев при пороге 0.25 | macro-F1 всей цепочки |',
      '|---|---:|---:|---:|---:|']
    for file in sorted((REPORT/'pipeline').glob('*_val.json')):
        if '_seed' in file.name:continue
        r=read(file)
        if r.get('partial') or r.get('code_sha256')!=sha(ROOT/'ml-pipeline/evaluate_accuracy_pipeline.py'):continue
        ap=r['disease_detection'];d=r['diagnosis_at_fixed_threshold']
        lines.append(f'| {r["model"]["id"]} | {ap["map50_95"]:.5f} | {ap["map50"]:.5f} | {d["correct_class_and_box_recall"]:.5f} | {d["macro_f1"]:.5f} |')
    seed_summary=REPORT/'seed_summary.json'
    if seed_summary.exists():
        repeated=read(seed_summary);evidence['seed_summary']=repeated
        lines+=['','### Повторная общая оценка уже имеющихся десяти seed','',
          '| Режим | Реальное n | AP50–95 mean ± SD | Верный класс + рамка recall mean ± SD |',
          '|---|---:|---:|---:|']
        for kind,r in repeated['models'].items():
            a=r['map50_95'];d=r['correct_class_and_box_recall'];sd=lambda v:'—' if v is None else f'{v:.5f}'
            lines.append(f'| {kind} | {a["n"]} | {a["mean"]:.5f} ± {sd(a["sd"])} | {d["mean"]:.5f} ± {sd(d["sd"])} |')
        lines+=['','Строки YOLO11n/26n — повторная оценка сохранённых обучений без повторного обучения; остальные строки — '
            'фактически завершённые новые пилоты. SD отражает разные seed '
            'на одной val, не разные независимые поля. Для новых компонентов десяти законченных seed пока нет. '
            'Строка teacher_efficientnet_v2_s — дополнительная полная цепочка учителя через тот же evaluate(); '
            'он остаётся вспомогательным компонентом дистилляции и не включён отдельной кнопкой в APK.']
    lines+=['', 'В первой таблице первоначальные прототипы конкретных весов, не среднее десяти. По первой полной проверке '
      'MobileNet повысил macro-F1, но снизил AP и долю правильно обнаруженных и распознанных листьев. '
      'Этот компромисс оцениваем по всем метрикам. Улучшение на истинных вырезках нельзя выдавать '
      'за улучшение фото целиком. Сравнение повторных seed и дополнительные источники данных остаются в работе.',
      '', '## Разбор первого парного seed: где именно ошибки','',
      'Ниже seed20261003 у детектора и классификатора. Это пилот, не средний результат десяти. '
      'Порог score0.25, рамки сопоставляются по IoU≥0.5. Доля правильных листьев имеет знаменатель всех1309 '
      'размеченных листьев; точность только на найденных листьях исключает пропуски и не заменяет её.',
      '', '| Режим | Верный класс + рамка | Не найдено | Найдено, неверный класс | Лишних/ошибочных предсказаний |',
      '|---|---:|---:|---:|---:|']
    pilots={}
    for kind in ['yolo26n','mobilenet_v3_large','yolo26s','deimv2_n','efficientnet_b0',
                 'masked_mobilenet_v3_large','distilled_mobilenet_v3_large']:
        file=REPORT/'pipeline'/f'{kind}_seed20261003_val.json'
        if not file.exists():continue
        r=read(file)
        if r.get('code_sha256')!=sha(ROOT/'ml-pipeline/evaluate_accuracy_pipeline.py'):continue
        d=r['diagnosis_at_fixed_threshold'];pilots[kind]=d
        lines.append(f'| {kind} | {d["correctly_diagnosed_gt_leaves"]}/{d["gt_leaves"]} | '
                     f'{d["gt_leaves"]-d["matched_leaves"]} | {d["matched_leaves"]-d["correctly_diagnosed_gt_leaves"]} | '
                     f'{sum(c["fp"] for c in d["classes"])} |')
    lines+=['','Лишние/ошибочные предсказания включают FP неправильного класса; последний столбец частично '
      'пересекается с «найдено, неверный класс», поэтому их нельзя складывать как непересекающиеся категории.',
      '', '### Приоритетные культуры на основной val','']
    data=read(ROOT/'ml-pipeline/dataset_accuracy_v1/manifest.json');priority=list(range(11,14))+list(range(18,27))+[29]
    class_names=read(ROOT/'android-app/app/src/main/assets/models/manifest.json')['classes']
    columns=list(pilots)
    lines+=['| Класс | Фото val / листья | '+' | '.join(k+' F1' for k in columns)+' |',
            '|---|---:|'+ '|'.join('---:' for _ in columns)+'|']
    for cls in priority:
        rows=[r for r in data['rows'] if r['split']=='val' and r['class_id']==cls]
        scores=[next(c['f1'] for c in pilots[k]['classes'] if c['id']==cls) for k in columns]
        lines.append(f'| {class_names[cls]} | {len({r["parent"] for r in rows})} / {len(rows)} | '+
                     ' | '.join('—' if v is None else f'{v:.3f}' for v in scores)+' |')
    lines+=['','F1 здесь учитывает пропуски, неправильные классы и FP на целой фотографии. '
      'Один снимок с несколькими листьями не даёт нескольких независимых полевых случаев. '
      'По здоровому картофелю и клещу основная val содержит лишь по одному снимку; '
      'по огурцу здесь нет снимков и нет отдельных меток болезней. Архитектурный результат не закрывает эти пробелы.',
      '', '## Рецепты и воспроизводимость','',
      '- Данные: clean v2 сохраняется; accuracy_v1 наследует split и group_id до нарезки. SHA manifest: '+evidence['data_sha256']+'.',
      '- Вырезки: запас 10% с каждой стороны, сохранение до 512, вход 224, квадратная подкладка RGB114, /255; ImageNet mean/std внутри графа.',
      '- 7760 train, 1309 val, 452 test и 160 val_aux вырезок. Маски: 590 train / 149 val_aux из PlantVillage, без полевых масок.',
      '- Маски автоматически извлечены из исходных segmented-изображений PlantVillage: max(RGB)>25 '
      'и крупнейшая связная компонента. Это производная лабораторная разметка, не ручные маски полевых листьев/пятен.',
      '- Классификаторы: AdamW 3e-4, cosine, 30 эпох, label smoothing0.03; batch32, teacher16; FP32.',
      '- Выборка классификатора: weighted sampler с весом min(3, sqrt(N / 30 / class_count)); flip и умеренный ColorJitter.',
      '- Сегментатор: U-Net base16, BCE+Dice, 20 эпох, batch16; маска фиксируется для последующего обучения классификатора; неадекватная площадь <5% или >95% возвращает исходный crop.',
      '- DEIM: упрощённый transfer recipe, 60 эпох, AdamW backbone1e-4/head2e-4, batch8, cosine, flip/color; это не исходное расписание COCO автора.',
      '- YOLO26s: batch16, 416, AdamW lr0.001, 60 эпох, patience60; trainer/evaluator прежней серии без изменений.',
      '- Seeds20261003–20261012. Checkpoint после эпохи сохраняет optimizer/scheduler/RNG; защищены три прежних скрипта.',
      '- До запуска следующих классификаторов добавлен явный отказ при нечисловом loss/градиентах. '
      'Этот guard не меняет данные, расписание или вычисление конечных обновлений. MobileNet03 и первые8 эпох MobileNet04 '
      'обучались до его добавления; продолжение MobileNet04 использует guard с прежним checkpoint и тем же рецептом.',
      '- DEIM использует grid_sampler backward, для которого этот ROCm/PyTorch предупреждает об отсутствии '
      'детерминированной реализации. Seeds и RNG фиксируются, но битовое совпадение повторного запуска не обещается; '
      'десять seed характеризуют разброс результата, а не устраняют эту особенность.',
      '', 'DEIM CPU-проверка проверяет перенос 626 из 637 тензоров, forward, конечные loss/градиенты и ONNX. '
      'TopK может переставлять почти равные результаты: сравнение выполняет взаимно однозначное сопоставление класса/рамки/score. '
      'Проверочная сеть с необученной головой в APK не включается.',
      '', 'Дополнительно DEIM-граф с необученной головой успешно запущен на Note7: это подтверждает поддержку операций '
      'ONNX Runtime Android, но не качество диагнозов. Это исторические технические пробы, '
      'которые не заменяют проверку настоящих обученных весов. Актуальный состав и паритет реальных графов APK '
      'приведены ниже. [DEIM phone probe](../../android/accuracy_v1/deim_export_probe.json), '
      '[CPU component probes](../../experiments/accuracy_v1/component_cpu_probes.json).',
      '']
    diagnostic=REPORT/'deim_full_export_diagnostic.json'
    if diagnostic.exists():
        r=read(diagnostic);evidence['deim_export_diagnostic_metrics']=r['metrics']
        lines+=['','### Исправление проверки обученного DEIM','',
          'Первый DEIM завершил60 эпох, но прежний guard потребовал одинаковые raw300 TopK строки с очень строгим допуском '
          'и остановил очередь на слабых кандидатах. Проверка всех349 val-фото сравнила исходный checkpoint, '
          'deployed PyTorch и ONNX; тестовая выборка не использовалась. '
          'Изменения фоновых TopK строк, включая class ID, сохранены и не объявляются точным совпадением.',
          '', '| Исполнение одинаковых PIL-входов | AP50–95 | AP50 |','|---|---:|---:|']
        for name,m in r['metrics'].items():lines.append(f'| {name} | {m["map50_95"]:.11f} | {m["map50"]:.11f} |')
        lines+=['','Для каждого обученного DEIM export_validation.json теперь проверяет **всю val**: '
          'unordered набор всех кандидатов score≥0,1 с точным class ID и прежними atol0,003/rtol0,0001. '
          'Это минимальный доступный порог приложения. AP50/AP50–95 дополнительно сравниваются по всем кандидатам '
          'score≥0,001/maxDets300, допустимая разница0,0001. Сам raw граф и общий evaluator не изменены. '
          'Исходный SHA обучения сохранён отдельно от нового экспортного SHA;60 эпох первого DEIM не повторялись. '
          '[Первичная диагностика](../../experiments/accuracy_v1/deim_export_failure_diagnostic.json), '
          '[вся val](../../experiments/accuracy_v1/deim_full_export_diagnostic.json).',
          '', '| Seed | Проверка всех val | Максимальная разница AP | SHA графа |','|---|---|---:|---|']
        for run in sorted(RUNS.glob('deimv2_n_seed*')):
            path=run/'export_validation.json'
            if not path.exists():continue
            v=read(path)
            lines.append(f'| {run.name} | {v["status"]}, {v["images"]} фото | {v["maximum_ap_delta"]:.9f} | {v["graph_sha256"]} |')
    lines+=['', '## Что уже на телефоне','']
    phone=ROOT/'docs/android/accuracy_v1/verification.json'
    if phone.exists():
        r=read(phone);evidence['phone']=r
        lines+=[f'Текущий APK: [{Path(r["apk"]).name}](../../../{Path(r["apk"]).as_posix()}), {r["apk_bytes"]:,} байт, SHA {r["apk_sha256"]}. Установлен на Redmi Note7; режимы: '+', '.join(r['modes'])+'.',
          '', f'Проверка: {r["results"]} результатов на трёх снимках, {len(r["comparisons"])} сравнений выходов с desktop ORT '
          'на фактических Android-входах первого снимка. Для DEIM строго сравниваются все кандидаты score≥0,1; '
          'для остальных стадий все raw значения. Полные сырые тензоры сохранены. '
          'Это техническая проверка переносимости, не статистический тест точности.',
          '', '| Фото | Режим | Рамок | Только графы, мс | Полная цепочка, мс |','|---|---|---:|---:|---:|']
        for item in r['manual_results']:lines.append(f'| {item["photo"]} | {item["model"]} | {item["boxes"]} | {item["inference_ms"]:.1f} | {item["pipeline_ms"]:.1f} |')
    benchmark=ROOT/'docs/android/accuracy_v1/benchmark/summary.json'
    if benchmark.exists():
        b=read(benchmark);evidence['phone_benchmark']=b
        lines+=['','### Десять серий скорости полной цепочки на телефоне','',
            'Один и тот же снимок картофеля, 10 новых процессов, порядок моделей меняется между сериями. '
            'В каждой модели3 прогрева +10 измерений. Указаны среднее медиан серий и их SD; '
            'это повторы скорости, не десять обучений и не точность на десяти независимых фотографиях. '
            'Полная цепочка здесь начинается с уже декодированного bitmap: подготовка входов, графы, рамки и вырезки. '
            'Чтение и декодирование файла, камера, сохранение и отрисовка интерфейса в замер не входят.',
            '', '| Режим | Только графы mean ± SD, мс | Полная цепочка mean ± SD, мс | Максимальный RSS после, МиБ |',
            '|---|---:|---:|---:|']
        for kind,r in b['models'].items():
            a=r['inference'];d=r['pipeline']
            lines.append(f'| {kind} | {a["mean_of_series_medians_ms"]:.1f} ± {a["sd_of_series_medians_ms"]:.1f} | '
                f'{d["mean_of_series_medians_ms"]:.1f} ± {d["sd_of_series_medians_ms"]:.1f} | {r["maximum_rss_bytes_after"]/1048576:.1f} |')
        lines+=['','RSS учитывает процесс и все его текущие выделения; HWM накопительный за процесс и не является отдельной памятью графа.']
    lines+=['', 'Модельные кнопки переносятся на следующие строки, поэтому готовые варианты видны без горизонтального поиска. '
      'Одновременно в памяти один детектор и один классификатор. '
      'Классифицируются не более32 листьев; число пропущенных показывается и сохраняется. Культура фильтруется после диагноза. '
      'Score цепочки = максимум score детектора × softmax классификатора; он не откалиброван как вероятность болезни. '
      'JSON сохраняет фактические seed, хеши и метаданные обеих стадий; прежнее фиксированное seed20261003 исправлено.',
      '', '## Что ещё предстоит','',
      'Завершить 80 заданий; по всем готовым режимам выполнить общую оценку десяти seed, разбор редких классов, '
      'проверки всех экспортов и телефона; обновлять APK по готовности. Не делать вывод «все пять испытаны» до этих этапов. '
      'Затем сравнить выбранные варианты и получить новый независимый полевой контроль. Test не используется для настройки.',
      '', 'Огуречные болезни в текущей разметке отсутствуют; смена архитектуры этого не исправляет. '
      'Здоровый картофель/клещ имеют слишком мало полевых val-примеров. Дополнительные архивы из каталога ещё не включены; '
      'Mendeley D01 сейчас закрывает штатную загрузку 403.',
      '', '## Команды и файлы','',
      '```powershell','.\\ml-pipeline\\Get-AccuracyStatus.ps1',
      '# Возобновление после фактического выхода прежнего worker','.\\ml-pipeline\\Start-AccuracyStudy.ps1 -AllArchitectures',
      '# Мягко остановить после эпохи','.\\ml-pipeline\\Stop-Training.ps1','```','',
      'Очередь: [architecture_status.json](../../experiments/accuracy_v1/architecture_status.json), '
      '[протокол](../../experiments/accuracy_v1/PROTOCOL.md), '
      '[пошаговое продолжение](../../experiments/accuracy_v1/CONTINUE.md), '
      '[точка продолжения](../../session/accuracy_work_state.json), '
      '[телефон](../../android/accuracy_v1/verification.json), '
      '[Текущая сборка](../../../artifacts/apk/current_model_lab.json).',
      '', 'Резерв15% пятичасового лимита действует. При достижении остановить разработку до01:00 следующего дня Minsk; '
      'обучение может продолжаться без токенов. FanControl, вентиляторы и HDD не меняются. Ничего не публикуется.',
      '', 'Исходники/веса и зависимости: [sources](../../experiments/accuracy_v1/third_party_sources.json), '
      '[weights](../../experiments/accuracy_v1/detector_weights.json), '
      '[DEIM CPU](../../experiments/accuracy_v1/deim_cpu_probe.json). '
      'DEIMv2 текущей ревизии допускает некоммерческие учебные эксперименты; копия лицензии сопровождает соответствующий APK-граф.']
    snapshots=REPORT/'source_snapshots.json'
    if snapshots.exists():
        evidence['source_snapshots']=read(snapshots)
        lines+=['','[Точные версии исходников](../../experiments/accuracy_v1/source_snapshots.json): текущие адаптеры '
                'и восстановленная исходная accuracy_study.py, чей полный SHA совпал с manifests первых MobileNet03/04. '
                'Финальный test запускается только после полного выбора через evaluate_accuracy_final_test.py; сейчас он не выполнялся.']
    (ROOT/'docs/history/reports/06_FIVE_VARIANTS_PROGRESS_2026-10-04.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    (REPORT/'progress_evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'report':'docs/history/reports/06_FIVE_VARIANTS_PROGRESS_2026-10-04.md','protected_scripts':'passed','components':{k:len(v) for k,v in evidence['components'].items()}}))

if __name__=='__main__':main()
