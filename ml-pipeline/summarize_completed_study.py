"""Audit and summarize a completed frozen study without touching its implementation."""
from __future__ import annotations
import csv
import hashlib
import json
import math
import statistics
from collections import Counter
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
STUDY=ROOT/'docs/experiments/study10_v2'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def sha(path):
    with path.open('rb') as handle:return hashlib.file_digest(handle,'sha256').hexdigest()

def main():
    status=read(STUDY/'status.json');result=read(STUDY/'results.json')
    selected=read(STUDY/'selected_model.json');plan=read(STUDY/'plan.json')
    assert status['status']=='completed' and result['completed']==20
    for name,digest in plan['implementation_sha256'].items():assert sha(ROOT/'ml-pipeline'/name)==digest
    data=ROOT/'ml-pipeline/dataset_clean_v2/manifest.json'
    assert hashlib.sha256(data.read_text(encoding='utf-8').encode('utf-8')).hexdigest()==plan['dataset_fingerprint']
    times={};class_stats=[]
    for model in ('yolo11n','yolo26n'):
        runs=[row for row in result['runs'] if row['model']==model]
        assert len(runs)==10 and {row['seed'] for row in runs}==set(range(20261003,20261013))
        durations=[]
        for row in runs:
            run=ROOT/'runs/detect'/row['name']
            assert row['completed_epochs']==60
            assert (run/'evaluation_aux.json').is_file()
            evaluation=read(run/'evaluation.json')
            assert evaluation['dataset_fingerprint']==plan['dataset_fingerprint'] and evaluation['seed']==row['seed']
            with (run/'results.csv').open() as handle:epochs=list(csv.DictReader(handle))
            assert len(epochs)==60
            durations.append(float(epochs[-1]['time'])/60)
        times[model]=dict(mean_minutes=statistics.mean(durations),sd_minutes=statistics.stdev(durations),
                          min_minutes=min(durations),max_minutes=max(durations),n=10)
    package=ROOT/'ml-pipeline/exports'/selected['selected_run']
    checksums=read(package/'sha256.json')
    for name,digest in checksums.items():assert sha(package/name)==digest
    evaluation=selected['evaluation'];assert evaluation['test'] is not None
    counts={split:{'images':Counter(),'boxes':Counter()} for split in ('val','test','val_aux')}
    for row in read(data):
        if row['split'] in counts:
            ids=[box[0] for box in row['boxes']]
            counts[row['split']]['boxes'].update(ids);counts[row['split']]['images'].update(set(ids))
    y26=[row for row in result['runs'] if row['model']=='yolo26n']
    test_classes={row['id']:row for row in evaluation['test']['classes']}
    for cls in (11,12,13,18,19,20,21,22,23,24,25,26,29):
        rows=[next(item for item in run['classes'] if item['id']==cls) for run in y26]
        values=[row['metrics']['map50_95'] for row in rows if row['metrics'] is not None]
        class_stats.append(dict(id=cls,name=rows[0]['name'],val_images=counts['val']['images'][cls],
            val_boxes=counts['val']['boxes'][cls],test_images=counts['test']['images'][cls],
            mean_val_map50_95=statistics.mean(values) if values else None,
            sd_val_map50_95=statistics.stdev(values) if len(values)>1 else None,
            selected_test_metrics=test_classes[cls]['metrics']))
    key='metrics/mAP50-95(B)';paired=result['paired_difference_yolo26_minus_yolo11'][key]
    # A t interval reflects variation across seeds on this fixed split only.
    half=2.2621571628540993*paired['sd']/math.sqrt(10)
    all_positive=all(row[key]>0 for row in result['paired_rows'])
    record=dict(status='verified_complete',completed=20,epochs_each=60,selected=selected['selected_run'],
        primary=result['primary'],paired_map50_95=dict(**paired,all_ten_positive=all_positive,
            seed_variation_95pct_t_interval=[paired['mean']-half,paired['mean']+half]),
        selected_test=evaluation['test']['overall'],class_priorities=class_stats,training_csv_time=times,
        package=str(package),package_sha256_verified=len(checksums),completed_utc=selected['completed_utc'],
        limitations=['Same validation split across ten seeds, not ten independent field tests',
                     'Test belongs only to the selected model, not a ten-run test comparison',
                     'Selection uses validation; selected validation score is optimistic',
                     'Cucumber diseases unsupported; laboratory auxiliary scores are not field evidence',
                     'APK 0.2 retains the verified matched pair seed 20261003; final selected weights are a separate package',
                     'Training times include validation within epochs and hardware/build interference; export and auxiliary evaluation excluded'])
    (STUDY/'completion_audit.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    lines=['# Итог серии: 10 обучений YOLO11n и 10 YOLO26n','',
        'Все 20 запусков завершили 60 эпох; отдельная test-проверка выбранной модели и пакет с SHA-256 готовы. Завершено 4 октября 2026 года в 02:32 Minsk.','',
        '[Развёрнутый отчёт: все запуски, все классы, методика и ограничения](../../history/reports/02_MODEL_STUDY_2026-10-04.md). [Все отчёты проекта](../../history/reports/README.md).','',
        'На неизменной основной val YOLO26n лучше по mAP50 и mAP50–95 во всех десяти парных seed. Это воспроизводимое преимущество на данном разбиении, а не гарантия лучшего диагноза на любом полевом фото.','',
        '| Модель | Число | val mAP50, среднее ± SD | val mAP50–95, среднее ± SD | Обучение, мин ± SD |',
        '|---|---:|---:|---:|---:|']
    for model in ('yolo11n','yolo26n'):
        a=result['primary'][model]['metrics/mAP50(B)'];b=result['primary'][model][key];t=times[model]
        lines.append(f'| {model} | 10 | {a["mean"]:.4f} ± {a["sd"]:.4f} | {b["mean"]:.4f} ± {b["sd"]:.4f} | {t["mean_minutes"]:.2f} ± {t["sd_minutes"]:.2f} |')
    lines += ['',f'Средняя парная разница mAP50–95: +{paired["mean"]:.4f} (+{paired["mean"]*100:.2f} пункта шкалы 0–100; относительный прирост {paired["mean"]/result["primary"]["yolo11n"][key]["mean"]*100:.2f}%). SD разниц {paired["sd"]:.4f}; 95% t-интервал по seed {paired["mean"]-half:.4f}…{paired["mean"]+half:.4f}. Интервал не оценивает неопределённость новых полевых данных.','',
        'Время взято из последнего `time` в results.csv каждого запуска: цикл обучения с внутривалидированием, без последующего экспорта/отдельной оценки. Паузы диагностики охлаждения и CPU-нагрузка сборки APK могли влиять на длительность; это не отдельный чистый бенчмарк GPU.','',
        '## Выбранная модель и отложенный test','',f'По зафиксированному протоколу выбран `{selected["selected_run"]}`: архитектура по среднему десяти val, checkpoint по лучшему val внутри выбранной архитектуры. Его val mAP50–95 = 0,3663. Оптимизм такой процедуры отражён в ограничениях.','',
        'На неизменном test (236 снимков, 452 рамки): precision 0,5092; recall 0,6407; mAP50 0,6280; mAP50–95 0,4884. Это одна итоговая проверка выбранной модели. Test остальных 19 вариантов не запускался ради подбора; сравнительного среднего по десяти test здесь нет.','',
        'Пакет: `ml-pipeline/exports/study10_v2_yolo26n_seed20261012`. Проверены все шесть файлов по sha256.json. ONNX FP32: 9 719 842 байта, вход [1,3,416,416], выход [1,34,3549]; 30 классов. Сверка ONNX с PyTorch прошла на трёх изображениях, максимальное абсолютное расхождение 0,001526.','',
        '## Приоритетные классы','',
        '| Класс | Снимков val | Рамок val | Средний val AP50–95 YOLO26n | Снимков test | AP50–95 выбранной модели на test |',
        '|---|---:|---:|---:|---:|---:|']
    for row in class_stats:
        a='—' if row['mean_val_map50_95'] is None else f'{row["mean_val_map50_95"]:.4f}'
        b='—' if row['selected_test_metrics'] is None else f'{row["selected_test_metrics"]["map50_95"]:.4f}'
        lines.append(f'| {row["name"]} | {row["val_images"]} | {row["val_boxes"]} | {a} | {row["test_images"]} | {b} |')
    lines += ['', 'Средний AP50–95 здорового картофеля на основной val крайне низок: 0,0147; для него есть всего один снимок с семью рамками. У повреждений клеща AP = 0 на единственном примере val. Это нерешённые проблемы и недостаточная полевая проверка. На test нет здорового картофеля, повреждений клеща и огурца: отсутствие метрики нельзя заменять нулём или объявлять подтверждением качества. Количество примеров по некоторым состояниям слишком мало для устойчивого вывода.','',
        'Огурец имеет только класс «состояние неизвестно». В отдельной val_aux выбранная модель даёт AP50–95 огурца 0,9088, но это 11 лабораторных снимков, которые не заменяют полевую проверку и не подтверждают распознавание болезней. Ошибки на внешних ручных примерах уже зафиксированы в инструкции APK; полная независимость этих фотографий не доказана, один снимок вероятно совпадает с train.','',
        '## APK и дальнейшая работа','',
        'APK 0.2 уже установлен и проверен; в нём остаётся одинаковая парная выборка seed 20261003 для обеих версий. Средние десяти seed относятся к архитектурам, не к этому одному APK. Итоговые выбранные веса seed 20261012 доступны отдельным пакетом; автоматическая пересборка готового APK в этой проверке не выполнялась.','',
        'Следующий эксперимент должен улучшать слабые полевые классы, сохраняя отдельные holdout и проверку происхождения данных. Приоритеты: здоровый картофель, повреждения клеща, томатные болезни, затем отдельная разметка болезней огурцов. Скачанные кандидаты пока отделены от обучения. Голос, справочник, сервер и публикация ещё не реализованы.','',
        'Исходные данные: [results.json](results.json), [selected_model.json](selected_model.json), [протокол](plan.json), [проверка завершения](completion_audit.json). [Инструкция APK](../../android/FIELD_DEMO_2026-10-03.md).']
    (STUDY/'FINAL_REPORT_2026-10-04.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(json.dumps({'status':record['status'],'class_priorities':class_stats,'package_files_verified':len(checksums)},ensure_ascii=False))

if __name__=='__main__':main()
