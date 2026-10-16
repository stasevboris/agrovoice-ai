"""Evidence-only report for M, including partial/final paired N/S/M results."""
import statistics
from datetime import datetime,timezone
from yolo26m_validation import ROOT,ASSETS,FOCUS,RUNS,SEEDS,CORE_SHA,read,save,sha,protocol,validate_seed
from build_yolo26s_focus_report import csv_progress

def stats(values):
    return {'n':len(values),'mean':statistics.mean(values) if values else None,
            'sd':statistics.stdev(values) if len(values)>1 else None}

def fmt(value):
    return '—' if not value['n'] else f"{value['mean']:.5f} ± "+('—' if value['sd'] is None else f"{value['sd']:.5f}")

def main():
    plan,lock=protocol();now=datetime.now(timezone.utc).isoformat()
    result={'generated_utc':now,'plan_sha256':lock['plan_sha256'],'models':{},'paired':{},'m_seeds':[]}
    reports={kind:{} for kind in ['yolo26n','yolo26s','yolo26m']}
    for kind in reports:
        folder=FOCUS/'pipeline' if kind=='yolo26m' else ROOT/'docs/experiments/accuracy_v1/pipeline'
        for seed in SEEDS:
            file=folder/f'{kind}_seed{seed}_val.json'
            if not file.exists():continue
            row=read(file)
            assert row['code_sha256']==CORE_SHA and not row['partial'] and row['split']=='val' and row['images']==349 and row['diagnosis_at_fixed_threshold']['gt_leaves']==1309
            if kind=='yolo26m':validate_seed(seed)
            reports[kind][seed]=row
        rows=list(reports[kind].values())
        result['models'][kind]={
            'map50_95':stats([r['disease_detection']['map50_95'] for r in rows]),
            'map50':stats([r['disease_detection']['map50'] for r in rows]),
            'macro_f1':stats([r['diagnosis_at_fixed_threshold']['macro_f1'] for r in rows]),
            'correct_class_and_box_recall':stats([r['diagnosis_at_fixed_threshold']['correct_class_and_box_recall'] for r in rows]),
            'classification_accuracy_on_matched_boxes':stats([r['diagnosis_at_fixed_threshold']['classification_accuracy_on_matched_boxes'] for r in rows])}
    for first,second in [('yolo26s','yolo26n'),('yolo26m','yolo26s'),('yolo26m','yolo26n')]:
        paired=sorted(set(reports[first]) & set(reports[second]))
        result['paired'][first+'-'+second]={'seeds':paired,'map50_95':stats([reports[first][s]['disease_detection']['map50_95']-reports[second][s]['disease_detection']['map50_95'] for s in paired])}
    for seed in SEEDS:
        run=RUNS/f'yolo26m_seed{seed}';row={'seed':seed,'completed_export':False}
        if (run/'export.json').exists() and (run/'manifest.json').exists():
            export=read(run/'export.json');info=read(run/'manifest.json')
            row.update(completed_export=export['status']=='passed' and info['status']=='completed',graph_sha256=export['sha256'])
        progress_file=ROOT/f'runs/detect/accuracy_v1_yolo26m_seed{seed}/results.csv'
        if progress_file.exists():
            import csv
            with progress_file.open(encoding='utf-8-sig') as stream:records=list(csv.DictReader(stream))
            if records:row['last_recorded_epoch']=int(records[-1]['epoch'])
        if seed in reports['yolo26m']:
            actual=reports['yolo26m'][seed];row.update(shared_val_ap=actual['disease_detection']['map50_95'],
                correct_gt=actual['diagnosis_at_fixed_threshold']['correctly_diagnosed_gt_leaves'],
                val_sha256=sha(FOCUS/'pipeline'/f'yolo26m_seed{seed}_val.json'))
        result['m_seeds'].append(row)
    result['completed_m_exports']=sum(r['completed_export'] for r in result['m_seeds'])
    result['completed_m_full_val']=len(reports['yolo26m'])
    for name in ['status','queue_status','phone_gate','final_phone_gate','validation_selection','final_test_selection']:
        if (FOCUS/(name+'.json')).exists():result[name]=read(FOCUS/(name+'.json'))
    save(FOCUS/'summary.json',result)
    apk=read(ROOT/'artifacts/apk/current_model_lab.json')
    s_status=read(ROOT/'docs/experiments/yolo26s_focus_v1/status.json')
    s_seed=s_status.get('current',{}).get('seed');s_progress=csv_progress(s_seed) if s_seed else None
    lines=['# YOLO26m: подготовка, испытания и сравнение с N/S','',f'Срез фактических файлов: {now}.','',
        '## Результат простыми словами','',
        '**N остаётся быстрым проверенным вариантом, S выбрана по качеству после десяти запусков. M проверяется отдельно.** '
        'Более крупная модель может выиграть в качестве, но это нужно измерить на наших данных и Redmi Note 7. '
        'Сейчас количество реально готовых M: '+str(result['completed_m_exports'])+'/10 экспортов и '+str(result['completed_m_full_val'])+'/10 полных val.','',
        f'Установленный указатель APK: версия {apk["version"]}, статус {apk["status"]}, SHA `{apk["sha256"]}`. '
        'Наличие исходного COCO-checkpoint или подготовленных скриптов не означает готовую модель болезней.','',
        f'S-очередь: {s_status["status"]}, готовых экспортов {s_status.get("completed",0)}/10. '+
        (f'Последняя записанная эпоха текущего seed {s_seed}: {s_progress["epoch"]}/60.' if s_progress else ''),'',
        '## Что зафиксировано до обучения M','',
        f'- План: SHA `{lock["plan_sha256"]}`, десять seed 20261003–20261012.',
        '- Те же clean v2: train 2609 фото, val 349 фото / 1309 листьев; классы и группы сохранены. Новые данные в эту серию не добавлены.',
        '- 60 эпох, patience 60, 416×416, batch 16, FP32, AMP выключен, AdamW, lr0=0.001. При OOM этот план останавливается; другой batch требует отдельной серии.',
        f'- Официальные начальные COCO-веса: 44 255 705 байт, SHA `{plan["pretrained_sha256"]}`; лицензия Ultralytics AGPL-3.0. [Происхождение](pretrained_provenance.json).',
        '- ONNX: обычная голова и внешний NMS, 1×3×416×416 → 1×34×3549. Три сравнения ONNX/PyTorch, затем общая CPU val.',
        '- Защищённые источники и данные проверяются по SHA. Прежние S/N и отменённая очередь 80 не изменяются. Один GPU-worker.','',
        '## Общая оценка качества','',
        'Одна процедура для N/S/M, вся val: 349 фото и 1309 GT. Score=0.25, IoU=0.5. '
        'Учитываются пропуски, ложные листья и неверный класс. mAP не равен проценту правильных диагнозов. '
        'Условная точность только среди найденных листьев отдельно показана, поскольку исключает пропуски.','',
        '| Модель | n | AP50–95 mean ± SD | macro-F1 | Верный класс и рамка / все GT | Класс среди найденных |',
        '|---|---:|---:|---:|---:|---:|']
    for kind in reports:
        item=result['models'][kind]
        lines.append(f'| {kind} | {item["map50_95"]["n"]} | {fmt(item["map50_95"])} | {fmt(item["macro_f1"])} | {fmt(item["correct_class_and_box_recall"])} | {fmt(item["classification_accuracy_on_matched_boxes"])} |')
    lines+=['','SD отражает разные seed на одной val, а не разные независимые поля. Неполное n не является итогом десяти прогонов.','',
        '| Парная разность AP | Совпавших seed | Mean ± SD |','|---|---:|---:|']
    for pair,item in result['paired'].items():lines.append(f'| {pair} | {item["map50_95"]["n"]} | {fmt(item["map50_95"])} |')
    lines+=['','| M seed | Экспорт завершён | Общий AP50–95 | Верный класс + рамка /1309 |', '|---|---|---:|---:|']
    for item in result['m_seeds']:lines.append(f'| {item["seed"]} | {"да" if item["completed_export"] else "нет"} | '+(f'{item["shared_val_ap"]:.5f}' if 'shared_val_ap' in item else '—')+f' | {item.get("correct_gt","—")} |')
    lines+=['','## Телефон, выбор и дальнейшие действия','',
        'Первый seed03 считается техническим пилотом. Его реально обученный граф добавляется девятой кнопкой к восьми сохранённым режимам, '
        'с голосом «модель девять», камерой и галереей. До продолжения оставшихся девяти нужны: новая установленная APK, '
        'Android/Desktop-паритет всех девяти режимов, десять независимых процессов полного bitmap-анализа, память без OOM. '
        'Строгий phone_gate сверяет SHA графа, плана, всех режимов и APK, извлечённого с телефона.','']
    for key in ['phone_gate','final_phone_gate']:
        gate=result.get(key)
        if gate:
            lines.append(f'{key}: **{gate["status"]}**, M seed {gate["model_seed"]}; средняя медиана полного bitmap-пути '
                f'{gate["pipeline_mean_series_median_ms"]:.1f} ± {gate["pipeline_sd_series_median_ms"]:.1f} мс; '
                f'RSS до {gate["maximum_rss_bytes_after"]/1024**2:.1f} МиБ, HWM процесса до {gate["maximum_process_hwm_bytes"]/1024**2:.1f} МиБ. '
                'Камера и JPEG-декодирование не входят в bitmap-таймер; HWM зависит от порядка моделей в процессе.')
        else:lines.append(f'{key}: **ещё не выполнен**, результатов скорости/памяти M нет.')
    lines+=['','## Классы на общей val','',
        'GT — число размеченных листьев класса. F1 учитывает ложные ответы и пропуски; '
        'в каждой колонке указаны mean ± SD по доступным seed. Для M при n=1 SD отсутствует. '
        'Нулевая поддержка не доказывает способность распознавать класс.','',
        '| ID / класс | GT | N F1 | S F1 | M F1 |','|---|---:|---:|---:|---:|']
    classes=read(ASSETS/'models/manifest.json')['classes']
    for cid,name in enumerate(classes):
        supports=[];columns=[]
        for kind in reports:
            class_rows=[next(c for c in row['diagnosis_at_fixed_threshold']['classes'] if c['id']==cid) for row in reports[kind].values()]
            supports += [c['tp']+c['fn'] for c in class_rows]
            columns.append(fmt(stats([c['f1'] for c in class_rows if c['f1'] is not None])))
        assert len(set(supports))<=1,'Class support changed between shared evaluations'
        lines.append(f'| {cid}: {name} | {supports[0] if supports else "—"} | '+ ' | '.join(columns)+' |')
    lines+=['','## Время нативных M-запусков','',
        'Последний CSV time — накопленное время обучения с native val и запуском среды. '
        'Это не телефонная задержка и не время всей серии с экспортом/общей оценкой.','',
        '| Seed | Завершено эпох | CSV time, секунд |','|---|---:|---:|']
    import csv
    for seed in SEEDS:
        path=ROOT/f'runs/detect/accuracy_v1_yolo26m_seed{seed}/results.csv'
        if not path.exists():continue
        with path.open(encoding='utf-8-sig') as stream:records=list(csv.DictReader(stream,skipinitialspace=True))
        if records:lines.append(f'| {seed} | {int(records[-1]["epoch"])} | {float(records[-1]["time"]):.2f} |')
    lines+=['','После phone_gate — последовательная очередь с переиспользованием03, остальные девять по тому же рецепту. '
        'Финальную M выбираем только после десяти полных val по максимальному AP50–95, при равенстве меньший seed. '
        'Сначала фиксируется выбор, затем исторический test. Test уже использовался в прежних исследованиях и не является новым независимым полевым набором.','',
        'В основной val нет огурцов; здорового картофеля и клеща очень мало. Болезни огурцов и процент поражения не подтверждены подходящей разметкой. '
        'Большая модель не устраняет нехватку данных. Итоговое решение о M зависит одновременно от качества, задержки и памяти на телефоне.','',
        '[Замороженный протокол](PROTOCOL.md), [точка продолжения и команды](CONTINUE.md), [машинные результаты](summary.json).']
    (FOCUS/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print({'M_exports':result['completed_m_exports'],'M_full_val':result['completed_m_full_val'],'report':str(FOCUS/'REPORT.md')})

if __name__=='__main__':main()
