"""Evidence-only ten-seed S/N card and class limits. Never changes weights or evaluation."""
import statistics
from yolo26_focus_validation import ROOT, FOCUS, REPORT, SEEDS, read, sha, cohort_selection


def main():
    choice = cohort_selection()
    models = read(ROOT / 'android-app/app/src/main/assets/models/manifest.json')
    rows = {kind: [read(REPORT / 'pipeline' / f'{kind}_seed{s}_val.json') for s in SEEDS]
            for kind in ['yolo26n', 'yolo26s']}
    fmt = lambda xs: f'{statistics.mean(xs):.6f} ± {statistics.stdev(xs):.6f}'
    lines = ['# Карточка YOLO26s: десять обучений и ограничения', '',
             '5 октября 2026. Только сохранённые результаты полной общей val. '
             'Каждый запуск: 60 эпох, 416×416, batch16, FP32, AdamW/lr0=0.001, '
             'те же clean v2 и 30 классов. Настройки и исходники не менялись.', '',
             '## Сравнение всех повторов', '',
             '| Показатель | YOLO26n, n=10 | YOLO26s, n=10 |', '|---|---:|---:|']
    for label, key in [('AP50–95', 'map50_95'), ('AP50', 'map50')]:
        lines.append(f'| {label} | {fmt([r["disease_detection"][key] for r in rows["yolo26n"]])} | '
                     f'{fmt([r["disease_detection"][key] for r in rows["yolo26s"]])} |')
    for label, key in [('Macro F1', 'macro_f1'), ('Верная рамка + класс / все GT', 'correct_class_and_box_recall'),
                       ('Верный класс / найденные рамки', 'classification_accuracy_on_matched_boxes')]:
        lines.append(f'| {label} | {fmt([r["diagnosis_at_fixed_threshold"][key] for r in rows["yolo26n"]])} | '
                     f'{fmt([r["diagnosis_at_fixed_threshold"][key] for r in rows["yolo26s"]])} |')
    deltas = [s['disease_detection']['map50_95'] - n['disease_detection']['map50_95']
              for s, n in zip(rows['yolo26s'], rows['yolo26n'])]
    lines += ['', f'Парные S−N по AP50–95: {fmt(deltas)}. Положительных пар: {sum(d > 0 for d in deltas)}/10.',
              'SD отражает seed на одной val, а не независимые поля. Val содержит 349 фото / 1309 размеченных листьев. '
              'Fixed-threshold показатели: score0.25, class-agnostic matching IoU0.5; '
              'учитываются неверные классы, ложные и пропущенные листья. AP не является процентом правильных диагнозов.', '',
              '## Все десять пар', '', '| Seed | N AP50–95 | S AP50–95 | S−N |', '|---|---:|---:|---:|']
    for seed, n, s, delta in zip(SEEDS, rows['yolo26n'], rows['yolo26s'], deltas):
        lines.append(f'| {seed} | {n["disease_detection"]["map50_95"]:.6f} | {s["disease_detection"]["map50_95"]:.6f} | {delta:+.6f} |')
    lines += ['', '## Выбранная пара', '']
    for kind, model in choice['models'].items():
        val = read(REPORT / 'pipeline' / f'{kind}_seed{model["seed"]}_val.json')
        fixed = val['diagnosis_at_fixed_threshold']
        lines.append(f'- {kind}: seed{model["seed"]}; ONNX {model["bytes"]} байт; SHA `{model["sha256"]}`; '
                     f'найдено рамок GT {fixed["matched_leaves"]}/1309; верный класс+рамка '
                     f'{fixed["correctly_diagnosed_gt_leaves"]}/1309. Это val выбранного checkpoint.')
    lines += ['', 'S выбран по максимуму общей AP50–95 после всех десяти val, при равенстве меньший seed. '
              'N12 оставлен по прежнему заблокированному выбору, не заменён другим лучшим seed новой процедуры. '
              'validation_selection и final_test_selection зафиксированы до исторического test.', '',
              '| Исторический test выбранной пары | AP50–95 | AP50 |', '|---|---:|---:|']
    for kind, model in choice['models'].items():
        path = FOCUS / 'final_test' / f'{kind}_test.json'
        if path.exists():
            test = read(path); assert test['model']['sha256'] == model['sha256']
            lines.append(f'| {kind} seed{model["seed"]} | {test["disease_detection"]["map50_95"]:.6f} | {test["disease_detection"]["map50"]:.6f} |')
    lines += ['', '236 фото / 452 GT. Test уже встречался ранее: это подтверждение выбранной пары на историческом наборе, '
              'не новый независимый полевой контроль, не десять средних test. Здоровый картофель, томатный клещ и огурец в test отсутствуют.', '',
              '## Все классы: GT val и средний F1 десяти seed', '',
              '| Класс | GT | N F1 | S F1 |', '|---|---:|---:|---:|']
    for index, name in enumerate(models['labels_ru']):
        n = [r['diagnosis_at_fixed_threshold']['classes'][index] for r in rows['yolo26n']]
        s = [r['diagnosis_at_fixed_threshold']['classes'][index] for r in rows['yolo26s']]
        counts = {c['tp'] + c['fn'] for c in n + s}; assert len(counts) == 1
        count = counts.pop()
        avg = lambda cs: f'{statistics.mean(c["f1"] for c in cs):.4f}' if count else 'нет GT'
        lines.append(f'| {name} | {count} | {avg(n)} | {avg(s)} |')
    lines += ['', 'Один–несколько листьев не дают устойчивой оценки класса. Плохой F1 отдельных болезней '
              'остаётся ограничением даже при общем выигрыше S. Для огурца нет GT основной val: '
              'болезни не заявляются, показывается только лист с неизвестным состоянием. '
              'Датасет размечает целый лист; процент поражённой ткани не оценивается.', '',
              '## Телефон и воспроизводимость', '',
              'APK с итоговой S04 сохраняет N12 и все прежние восемь примеров, голос/камеру/галерею/журнал. '
              'Свежие evidence: docs/android/yolo26s_final_v15_phone. '
              'Текущий статус установленного APK: artifacts/apk/current_model_lab.json. '
              'Скорость — полные bitmap-серии Note7, не камера/чтение JPEG и не обещание скорости A56/Redmi9T.', '',
              f'- SHA выбора val: `{sha(FOCUS / "validation_selection.json")}`.',
              f'- SHA плана: `{choice["plan_sha256"]}`.',
              f'- SHA ядра оценивания: `{choice["validation_code_sha256"]}`.',
              f'- SHA данных: `{choice["data_sha256"]}`.',
              '- Источники данных/лицензии: PROJECT_GUIDE.md, docs/data/dataset_clean_v2.md и docs/data/cucumber_sources.md. '
              'Новых датасетов в середине серии не добавляли. Следующая M использует отдельный замороженный протокол.']
    path = FOCUS / 'MODEL_CARD.md'
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(path)


if __name__ == '__main__':
    main()
