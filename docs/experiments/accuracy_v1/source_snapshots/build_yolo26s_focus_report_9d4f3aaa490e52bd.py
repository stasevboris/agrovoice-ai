"""Refresh the user-selected YOLO focus report from saved evidence; no model execution."""
import csv
import statistics
from datetime import datetime, timezone, timedelta
from yolo26_focus_validation import ROOT, FOCUS, REPORT, ASSETS, SEEDS, CORE_SHA, read, sha, save, plan_and_examples, check_preserved_models


def csv_progress(seed):
    path = ROOT / f'runs/detect/accuracy_v1_yolo26s_seed{seed}/results.csv'
    if not path.exists():
        return None
    with path.open(encoding='utf-8-sig') as stream:
        rows = [{k.strip(): v for k, v in row.items() if k is not None} for row in csv.DictReader(stream)]
    rows = [r for r in rows if r.get('epoch') and r.get('time')]
    if not rows:
        return None
    total, previous, seconds = 0., 0., []
    for row in rows:
        current = float(row['time'])
        # Ultralytics resets its cumulative clock after a resumed process.
        delta = current - previous if current >= previous else current
        total += delta
        seconds.append(delta)
        previous = current
    return {'seed': seed, 'epoch': int(rows[-1]['epoch']), 'epochs': 60,
            'recorded_training_seconds': total, 'recent_epoch_seconds': statistics.mean(seconds[-10:]),
            'source': str(path.relative_to(ROOT)),
            'updated_utc': datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()}


def main():
    plan, examples = plan_and_examples()
    queue = read(FOCUS / 'status.json')
    phone = read(ROOT / 'artifacts/apk/current_model_lab.json')
    current_manifest = read(ASSETS / 'models/manifest.json')
    check_preserved_models(current_manifest, examples)
    now = datetime.now(timezone.utc)
    local = now.astimezone(timezone(timedelta(hours=3)))
    records = []
    for seed in SEEDS:
        run = ROOT / f'runs/accuracy_v1/yolo26s_seed{seed}'
        item = {'seed': seed, 'training': csv_progress(seed), 'completed_export': False}
        if (run / 'export.json').exists() and (run / 'manifest.json').exists():
            info, export = read(run / 'manifest.json'), read(run / 'export.json')
            if info['status'] == 'completed' and export['status'] == 'passed':
                assert sha(run / 'model.onnx') == export['sha256']
                item.update(completed_export=True, graph_sha256=export['sha256'], native_val_ap=info['best_validation_score'])
                file = REPORT / 'pipeline' / f'yolo26s_seed{seed}_val.json'
                if file.exists():
                    row = read(file)
                    if row.get('code_sha256') == CORE_SHA and not row['partial'] and row['images'] == 349 and row['model']['sha256'] == export['sha256']:
                        item['shared_val'] = row
                        item['shared_val_source'] = str(file.relative_to(ROOT))
        records.append(item)
    ready = [r for r in records if r['completed_export']]
    evaluated = [r for r in records if 'shared_val' in r]
    current = queue.get('current', {})
    progress = next((r['training'] for r in records if r['seed'] == current.get('seed')), None)
    completed_seconds = [r['training']['recorded_training_seconds'] for r in ready if r['training']]
    epoch_basis = statistics.mean(completed_seconds) / 60 if completed_seconds else 48.5
    unfinished = [r for r in records if not r['completed_export']]
    remaining_training = sum((60 - (r['training']['epoch'] if r['training'] else 0)) *
                             (r['training']['recent_epoch_seconds'] if r['training'] else epoch_basis) for r in unfinished)
    eta = {'generated_utc': now.isoformat(), 'generated_local': local.isoformat(), 'status': 'planning_estimate_not_guarantee',
           'completed_exports': len(ready), 'total': 10, 'current': current, 'progress': progress,
           'completed_training_seconds': completed_seconds, 'mean_completed_epoch_seconds': epoch_basis,
           'remaining_training_seconds': remaining_training,
           'clock_rule': 'Sum positive cumulative increments; after resume clock reset use current cumulative value. Off-process pause excluded.',
           'exports_and_native_checks_allowance_minutes_per_unfinished': [1, 12],
           'basis_note': 'YOLO S actual CSV clocks; only a few completed seeds. First native export/check included manual delay, not extrapolated as guaranteed cost.',
           'remaining_series_hours_low': remaining_training * .95 / 3600 + len(unfinished) / 60,
           'remaining_series_hours_high': remaining_training * 1.15 / 3600 + len(unfinished) * .2,
           'excludes': 'Final shared-val/test, APK build/installation/parity and phone timings; power-off/other pauses'}
    save(FOCUS / 'eta.json', eta)
    summary = read(REPORT / 'seed_summary.json')
    lines = ['# YOLO26s и YOLO26n: текущий результат и принятое решение', '',
             f'Срез по сохранённым файлам: {local.isoformat()} (Europe/Minsk).', '',
             '## Что берём', '',
             '**YOLO26n — быстрый проверенный вариант; YOLO26s — основной кандидат на повышение качества.** '
             'Борис отменил дальнейшее обучение DETR и выбрал эти две модели. Продолжаем только десятьS, '
             'а прежние десятьN переиспользуем. Все восемь уже установленных режимов остаются для примера.', '',
             'N в приложении уже seed20261012: лучший по native val AP50–95 из десяти study10_v2; '
             'его выбрали до итогового test. Повторное обучениеN сейчас не требуется. S в APK0.8 пока seed03; '
             'окончательный seedS выбирается после десяти полных общих val, затем устанавливается новая версияAPK.', '',
             '## Что реально исполнено', '',
             f'- YOLO26n: прежние10/10 обучений и10/10 общих val-оценок. [Прежняя серия](../experiments/study10_v2/FINAL_REPORT_2026-10-04.md).',
             f'- YOLO26s: **{len(ready)}/10** обучений с прошедшим экспортом; **{len(evaluated)}/10** полных общих val-оценок.',
             f'- Текущая очередь: {queue["status"]}, компонент {current.get("id", "—")}.',
             '- Исходный план80 остановлен, checkpointS04 сохранён на39/60 и продолжен со40/60. Числовой рецепт и данные не менялись.',
             '- DETR, отдельные классификаторы, маска и дистилляция дальше не повторяются в этой очереди. Их пилоты и результаты сохранены; это изменение задачи, не технический отказ.', '',
             'Источник исполнения: [status](../experiments/yolo26s_focus_v1/status.json), '
             '[замороженный план](../experiments/yolo26s_focus_v1/plan.json), '
             '[протокол](../experiments/yolo26s_focus_v1/PROTOCOL.md), '
             '[продолжение](../experiments/yolo26s_focus_v1/CONTINUE.md). Один GPU-worker; CPU ONNX-оценка может идти рядом.', '']
    if progress:
        lines += [f'Последняя сохранённая эпоха текущегоS: {progress["epoch"]}/60, файл обновлён {progress["updated_utc"]}. '
                  'Промежуточная эпоха не является готовым запуском.', '']
    lines += ['## Повторная общая проверка качества', '',
              '349 val-фото /1309 размеченных листьев, один неизменный CPU evaluator. '
              'AP учитывает рамки и класс; при фиксированном score0.25/IoU0.5 отдельно считаем пропущенные листья, '
              'ошибочный класс и ложные предсказания. AP нельзя читать как процент верных диагнозов.', '',
              '| Модель | Реальное n | AP50–95 mean ± SD | macro-F1 mean ± SD | Верный класс+рамка / все GT, mean ± SD |',
              '|---|---:|---:|---:|---:|']
    def metric(value):
        return f'{value["mean"]:.5f} ± ' + ('—' if value['sd'] is None else f'{value["sd"]:.5f}')
    for kind in ['yolo26n', 'yolo26s']:
        if kind not in summary['models']:
            continue
        row = summary['models'][kind]
        lines.append(f'| {kind} | {row["map50_95"]["n"]} | {metric(row["map50_95"])} | {metric(row["macro_f1"])} | {metric(row["correct_class_and_box_recall"])} |')
    lines += ['', 'SD отражает разные seed на одной val, не независимые поля. ДляS учитывается только фактически готовый набор; '
              'среднееS с неполнымn ещё не итоговое сравнение10×10. Прежний native evaluator и новая общая процедура '
              'имеют разную обработку кандидатов, их AP напрямую не смешиваем.', '',
              '| S seed | Native AP50–95 | Общий AP50–95 | N того же seed | Разность S−N | Правильный класс+рамка S /1309 |',
              '|---|---:|---:|---:|---:|---:|']
    for item in evaluated:
        row = item['shared_val']
        paired = read(REPORT / 'pipeline' / f'yolo26n_seed{item["seed"]}_val.json')
        s_ap, n_ap = row['disease_detection']['map50_95'], paired['disease_detection']['map50_95']
        lines.append(f'| {item["seed"]} | {item["native_val_ap"]:.5f} | {s_ap:.5f} | {n_ap:.5f} | {s_ap-n_ap:+.5f} | '
                     f'{row["diagnosis_at_fixed_threshold"]["correctly_diagnosed_gt_leaves"]}/1309 |')
    if len(evaluated) < 10:
        lines += ['', '**Окончательное преимуществоS пока не доказано десятью запусками.** Пилот обосновывает приоритет обучения, '
                  'а не объявление итогового победителя. Прежние варианты и разбор их ошибок — в '
                  '[историческом отчёте06](06_FIVE_VARIANTS_PROGRESS_2026-10-04.md).']
    final_folder = FOCUS / 'final_test'
    lines += ['', '## Итоговый test', '']
    if final_folder.exists() and all((final_folder / f'{k}_test.json').exists() for k in ['yolo26n', 'yolo26s']):
        lines += ['| Выбранная модель | Seed | AP50–95 | AP50 |', '|---|---:|---:|---:|']
        for kind in ['yolo26n', 'yolo26s']:
            row = read(final_folder / f'{kind}_test.json')
            lines.append(f'| {kind} | {row["model"]["seed"]} | {row["disease_detection"]["map50_95"]:.5f} | {row["disease_detection"]["map50"]:.5f} |')
        lines += ['', 'Это одна выбраннаяS и фиксированнаяN на историческом test, не среднее десяти обучений. Выбор заблокирован до чтения test.']
    else:
        lines += ['Финальный test новойS ещё не выполнен. Guard отказывает до10 завершённых экспортов и20 полных paired val. '
                  'Старый guard80 не меняется: введён отдельный guard для явно согласованного нового объёма.']
    lines += ['', 'Исторический test уже видел предыдущие модели. Новые независимые полевые фотографии необходимы. '
              'В val нет огурца; здоровый картофель — одно фото/семь листьев, клещ — одно фото/один лист. '
              'В test этих трёх классов нет. Болезни огурцов и доля поражённой ткани не заявляются.', '',
              '## Что в приложении', '',
              f'Текущий APK: **{phone["version"]}**, status={phone["status"]}, installed={phone["installed"]}, '
              f'SHA256 `{phone["sha256"]}`. [Файл](../../{phone["apk"].replace(chr(92), "/")}).', '',
              '| Кнопка | Seed | Назначение |', '|---|---:|---|']
    for model in current_manifest['models']:
        purpose = ('Проверенный быстрый выбор; десять обучений' if model['id'] == 'yolo26n' else
                   'Приоритет текущих десяти обучений; окончательные веса после общей val' if model['id'] == 'yolo26s' else
                   'Сохранённый пример; дальнейшее обучение DETR прекращено' if model['id'] == 'deimv2_n' else
                   'Сохранённый пример для сравнения')
        lines.append(f'| {model["name"]} | {model["seed"]} | {purpose} |')
    lines += ['', 'Семь прежних режимов сохраняются в точности, включая лучшийN. При финальной упаковке меняется толькоS. '
              'Снимки и пользовательские результаты сохраняются. До готовности десятиS нет причины пересобирать уже проверенныйAPK0.8.', '',
              '### Фактическая скорость Note7', '',
              '| Режим | Анализ bitmap, mean медиан ± SD, мс | Число свежих процессов |', '|---|---:|---:|']
    benchmark = read(ROOT / 'docs/android/accuracy_v1/benchmark/summary.json')
    for kind, row in benchmark['models'].items():
        value = row['pipeline']
        lines.append(f'| {kind} | {value["mean_of_series_medians_ms"]:.2f} ± {value["sd_of_series_medians_ms"]:.2f} | {row["series"]} |')
    lines += ['', 'Измерены подготовка входов, графы и postprocess одного декодированного снимка; камера, декодирование, '
              'сохранение/UI не включены. По3 прогрева и10 измерений на режим в каждом из10 свежих процессов, порядок менялся. '
              'RSS — память процесса, не индивидуальный размер модели. Это десять замеров скорости, не десять обучений. '
              '[Исходные измерения](../android/accuracy_v1/benchmark/summary.json). После выбора окончательнойS проверить заново.', '',
              '## Сколько осталось', '',
              f'По CSV завершённыхS среднее время эпохи≈{epoch_basis:.1f}с. Расчётный остаток собственно эпох≈{remaining_training/3600:.2f}ч. '
              f'Плановый диапазон серии с экспортом/native-проверками≈{eta["remaining_series_hours_low"]:.1f}–{eta["remaining_series_hours_high"]:.1f}ч от времени этого среза. '
              'Это ориентир по немногим запускам, не статистический доверительный интервал и не гарантированный срок.', '',
              'После эпох нужны общая val, заблокированный test, сборка/установка и повторные телефонные проверки. '
              'Паузы компьютера и ошибки сдвигают срок. Старый прогноз45–55 часов относится к отменённой очереди80 и больше не действителен. '
              '[Численный расчёт](../experiments/yolo26s_focus_v1/eta.json).', '',
              '## Другие YOLO и следующий эксперимент', '',
              '[Исследование доступных версий](../research/YOLO_ALTERNATIVES_2026-10-04.md):26m для возможного повышения качества, '
              '11s как контроль, v9s/v10s для проверки компактности;12/13 в исследовательском резерве,27 на момент проверки ещё unreleased. '
              'COCO-таблицы не обещают качество на растениях и скорость телефона. Новое семейство не добавляется посередине этой замороженной серии.', '',
              'Для дальнейшего существенного улучшения нужны полевые здоровые листья, клещ и размеченные огуречные болезни. '
              'Новые данные — следующий отдельный эксперимент, с происхождением/лицензией/группами и самостоятельной контрольной выборкой.', '',
              '## Воспроизводимость', '',
              f'- ПланSHA: `{sha(FOCUS / "plan.json")}`.',
              f'- Производный manifest данныхSHA: `{plan["data_sha256"]}`.',
              f'- Неизменное ядро общей оценкиSHA: `{CORE_SHA}`.',
              f'- Фиксированный ONNX N12SHA: `{plan["baseline"]["sha256"]}`.',
              '- Исходная очередь, все старые веса и APK сохранены. Новый launcher/queue меняет только список разрешённых запусков, не рецепт.',
              '- Guard-проверка до10S действительно отказала в упаковке/test до записи assets и до чтения test.',
              '- Источники/выбор/метрики/экспорты хранятся в plan, run manifests, pipeline JSON и source_snapshots. '
              'FanControl закрыт; вентиляторы/HDD не менялись; GPU-worker один.', '']
    output = ROOT / 'docs/reports/07_YOLO26_FOCUS_2026-10-04.md'
    output.write_text('\n'.join(lines), encoding='utf-8')
    save(FOCUS / 'progress_evidence.json', {'updated_utc': now.isoformat(), 'plan_sha256': sha(FOCUS / 'plan.json'),
                                          'queue': queue, 'records': records, 'seed_summary': summary, 'apk': phone, 'eta': eta})
    print({'report': str(output.relative_to(ROOT)), 's_exports': len(ready), 's_full_val': len(evaluated), 'current': current})


if __name__ == '__main__':
    main()
