"""Sequential paired 10-seed study. One GPU job at a time; primary TEST only after selection."""
from __future__ import annotations
import argparse
import csv
from datetime import datetime, timezone
import fcntl
import hashlib
import importlib.metadata
import json
from pathlib import Path
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
STUDY = ROOT/'docs/experiments/study10_v2'
LOGS = ROOT/'ml-pipeline/logs/study10_v2'
DATA = ROOT/'ml-pipeline/dataset_clean_v2'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def write(path, value):
    from train_clean import write_json
    write_json(path, value)


def sha(path):
    with Path(path).open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def stats(values):
    return dict(n=len(values), mean=statistics.mean(values),
                sd=statistics.stdev(values) if len(values) > 1 else None,
                min=min(values), max=max(values)) if values else None


def make_plan():
    report = read(DATA/'report.json')
    jobs = []
    for i, seed in enumerate(range(20261003, 20261013)):
        models = ['yolo11n', 'yolo26n'] if i % 2 == 0 else ['yolo26n', 'yolo11n']
        for model in models:
            jobs.append(dict(model=model, seed=seed, name=f'study10_v2_{model}_seed{seed}'))
    import torch
    return dict(protocol_version=1, dataset_fingerprint=report['fingerprint'],
       environment=dict(python=sys.version, torch=torch.__version__, hip=torch.version.hip,
                        ultralytics=importlib.metadata.version('ultralytics'),
                        onnxruntime=importlib.metadata.version('onnxruntime'), gpu=torch.cuda.get_device_name(0)),
       implementation_sha256={f: sha(ROOT/'ml-pipeline'/f) for f in ('train_clean.py', 'evaluate_clean.py', 'run_study.py')},
       dataset=report['dataset'], input_weights={model: sha(ROOT/f'ml-pipeline/{model}.pt') for model in ('yolo11n', 'yolo26n')},
       training=dict(epochs=60, patience=60, image_size=416, batch=16, optimizer='AdamW', lr0=.001,
                     cos_lr=True, amp=False, deterministic=True, workers=4, threads=8),
       seed_policy='Ten paired seeds 20261003..20261012; fresh COCO weights each run. Architecture order alternates by seed.',
       primary_validation='Unchanged 349 PlantDoc images, 29 represented classes; no supplemental lab images.',
       auxiliary_validation='160 grouped supplemental holdout images, reported separately; no test selection.',
       selection='Architecture with highest mean primary val mAP50-95 across 10 runs; choose its checkpoint with highest primary val mAP50-95. This selection is optimistic on val. Test once selected run, report as final selected-model test only.',
       statistics='Sample standard deviation across independent training seeds; paired differences by seed. Same data split, not ten independent field test sets.',
       jobs=jobs)


def verify_data(plan, full=False):
    if read(DATA/'report.json')['fingerprint'] != plan['dataset_fingerprint']:
        raise RuntimeError('Dataset report changed during frozen study')
    canonical = (DATA/'manifest.json').read_text(encoding='utf-8')
    if hashlib.sha256(canonical.encode('utf-8')).hexdigest() != plan['dataset_fingerprint']:
        raise RuntimeError('Frozen dataset manifest changed')
    for model, checksum in plan['input_weights'].items():
        if sha(ROOT/f'ml-pipeline/{model}.pt') != checksum:
            raise RuntimeError('Input checkpoint changed')
    if full:
        for r in read(DATA/'manifest.json'):
            paths = [(DATA/'images'/r['split']/r['filename'], r['prepared_sha256']),
                     (DATA/'labels'/r['split']/Path(r['filename']).with_suffix('.txt'), r['label_sha256'])]
            if any(sha(path) != checksum for path, checksum in paths):
                raise RuntimeError(f'Frozen file changed: {r["filename"]}')
        config = (DATA/'data.yaml').read_text(encoding='utf-8')
        import yaml
        names = yaml.safe_load(config)['names']
        if [names[k] for k in range(len(names))] != read(DATA/'classes.json'):
            raise RuntimeError('Data configuration class IDs changed')


def aggregate(plan):
    rows = []
    for job in plan['jobs']:
        run = ROOT/'runs/detect'/job['name']
        if (run/'evaluation.json').exists() and (run/'evaluation_aux.json').exists():
            evaluation = read(run/'evaluation.json')
            if evaluation['dataset_fingerprint'] != plan['dataset_fingerprint'] or evaluation.get('seed') != job['seed']:
                raise RuntimeError('Evaluation provenance mismatch')
            primary = evaluation['validation']['overall']
            aux = read(run/'evaluation_aux.json')['validation']['overall']
            rows.append(dict(**job, primary=primary, auxiliary=aux,
                             completed_epochs=evaluation['completed_epochs'],
                             onnx_bytes=evaluation['onnx_bytes'], classes=evaluation['validation']['classes']))
    keys = ['metrics/precision(B)', 'metrics/recall(B)', 'metrics/mAP50(B)', 'metrics/mAP50-95(B)']
    models = {m: {k: stats([r['primary'][k] for r in rows if r['model'] == m]) for k in keys}
              for m in ('yolo11n', 'yolo26n')}
    paired = []
    for seed in range(20261003, 20261013):
        pair = {r['model']: r for r in rows if r['seed'] == seed}
        if len(pair) == 2:
            paired.append(dict(seed=seed, **{k: pair['yolo26n']['primary'][k]-pair['yolo11n']['primary'][k] for k in keys}))
    result = dict(updated_utc=now(), dataset_fingerprint=plan['dataset_fingerprint'],
                  completed=len(rows), requested=20, status='complete_training' if len(rows) == 20 else 'incomplete',
                  primary=models, paired_difference_yolo26_minus_yolo11={k: stats([r[k] for r in paired]) for k in keys},
                  paired_rows=paired, runs=rows, limitations=plan['statistics'])
    write(STUDY/'results.json', result)
    lines = ['# YOLO11n и YOLO26n — серия по 10 обучений', '',
             f'Готовы обучение и обе проверки: **{len(rows)}/20**. Обновлено UTC: {result["updated_utc"]}.', '',
             'Каждый запуск использует свой seed; выборка одна и та же. Основная оценка — прежние полевые снимки PlantDoc. Дополнительная лабораторная проверка хранится отдельно.', '',
             '| Модель | Завершено | mAP50, среднее ± SD | mAP50–95, среднее ± SD |',
             '|---|---:|---:|---:|']
    for model in models:
        a, b = models[model][keys[2]], models[model][keys[3]]
        fmt = lambda v: f'{v["mean"]:.4f} ± {v["sd"]:.4f}' if v and v['sd'] is not None else (f'{v["mean"]:.4f}; SD пока нет' if v else '—')
        lines.append(f'| {model} | {a["n"] if a else 0}/10 | {fmt(a)} | {fmt(b)} |')
    lines += ['', 'До завершения всех 20 запусков вывод о превосходстве архитектуры предварительный. Даже десять seed не заменяют новые независимые полевые данные.', '',
              'Подробные показатели каждого seed, парные разницы и метрики классов: results.json. Протокол: plan.json. Статус очереди: status.json.']
    (STUDY/'REPORT.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    return rows, models


def invoke(command, name):
    with (LOGS/(name+'.stdout.log')).open('a', encoding='utf-8') as out, (LOGS/(name+'.stderr.log')).open('a', encoding='utf-8') as err:
        subprocess.run([sys.executable, '-u', *map(str, command)], cwd=ROOT, stdout=out, stderr=err, check=True)


def update_active(name):
    active = ROOT/'ml-pipeline/logs/active_training.json'
    if active.exists():
        current = read(active)
        if current.get('study') == 'study10_v2':
            current['name'] = name
            write(active, current)


def execute(args):
    STUDY.mkdir(parents=True, exist_ok=True); LOGS.mkdir(parents=True, exist_ok=True)
    plan_path = STUDY/'plan.json'
    desired = make_plan()
    if plan_path.exists():
        plan = read(plan_path)
        if plan != desired:
            raise RuntimeError('Study protocol changed. Start a new study version.')
        if not args.resume:
            raise RuntimeError('Existing study requires explicit --resume')
    else:
        plan = desired; write(plan_path, plan)
    verify_data(plan, full=True)
    state = dict(status='running', started_utc=now(), completed=0, requested=20, current=None, phases={}, dataset_fingerprint=plan['dataset_fingerprint'])
    if args.resume and (STUDY/'status.json').exists():
        old = read(STUDY/'status.json'); state['phases'] = old.get('phases', {})
    aggregate(plan)
    write(STUDY/'status.json', state)
    try:
        for job in plan['jobs']:
            if args.stop_file.exists():
                state.update(status='stopped', ended_utc=now()); break
            verify_data(plan)
            name = job['name']; state.update(current=job, phase='training'); write(STUDY/'status.json', state)
            update_active(name)
            run = ROOT/'runs/detect'/name
            manifest_path = run/'run_manifest.json'
            manifest = read(manifest_path) if manifest_path.exists() else None
            if manifest and (manifest['dataset_fingerprint'] != plan['dataset_fingerprint'] or manifest.get('seed') != job['seed']):
                raise RuntimeError('Existing run fingerprint or seed differs')
            if not manifest or manifest['status'] != 'trained':
                command = [ROOT/'ml-pipeline/train_clean.py', '--data', DATA/'data.yaml', '--model', ROOT/f'ml-pipeline/{job["model"]}.pt',
                           '--name', name, '--seed', job['seed'], '--epochs', '60', '--patience', '60', '--imgsz', '416',
                           '--batch', '16', '--device', '0', '--workers', '4', '--threads', '8', '--optimizer', 'AdamW',
                           '--lr0', '.001', '--stop-file', args.stop_file]
                if run.exists():
                    checkpoint = run/'weights/last_complete.pt'
                    if not args.resume or not checkpoint.exists():
                        raise RuntimeError(f'Partial run needs explicit recovery; no usable resume: {name}')
                    command += ['--resume', checkpoint]
                print(f'Training {name}', flush=True); invoke(command, name+'_train')
                manifest = read(manifest_path)
                if manifest['status'] == 'stopped':
                    state.update(status='stopped', ended_utc=now()); break
            if manifest['status'] != 'trained':
                raise RuntimeError(f'Training did not complete: {name}')
            with (run/'results.csv').open(encoding='utf-8') as csvfile:
                if len(list(csv.DictReader(csvfile))) != 60:
                    raise RuntimeError('Study expects 60 completed epochs per fresh run')
            state['phase'] = 'evaluation'; write(STUDY/'status.json', state)
            for suffix, flags in [('', []), ('_aux', ['--aux'])]:
                if not (run/f'evaluation{suffix}.json').exists():
                    invoke([ROOT/'ml-pipeline/evaluate_clean.py', '--run', run, '--device', '0', *flags], name+'_eval'+suffix)
            state['phases'][name] = dict(status='evaluated', ended_utc=now())
            rows, _ = aggregate(plan); state['completed'] = len(rows); write(STUDY/'status.json', state)
        else:
            rows, models = aggregate(plan)
            if len(rows) != 20:
                raise RuntimeError('Cannot select model before 20 evaluations')
            winner = max(models, key=lambda m: models[m]['metrics/mAP50-95(B)']['mean'])
            selected = max((r for r in rows if r['model'] == winner), key=lambda r: r['primary']['metrics/mAP50-95(B)'])
            state.update(phase='final_selected_test', selected=selected['name']); write(STUDY/'status.json', state)
            run = ROOT/'runs/detect'/selected['name']
            invoke([ROOT/'ml-pipeline/evaluate_clean.py', '--run', run, '--device', '0', '--test'], selected['name']+'_final_test')
            invoke([ROOT/'ml-pipeline/package_model.py', '--run', run], selected['name']+'_package')
            write(STUDY/'selected_model.json', dict(selected_run=selected['name'], architecture=winner,
                  selected_by='10-run mean primary validation, then highest primary validation checkpoint within winning architecture',
                  evaluation=read(run/'evaluation.json'), package=str(ROOT/'ml-pipeline/exports'/selected['name']), completed_utc=now()))
            state.update(status='completed', phase='done', ended_utc=now()); aggregate(plan)
            with (STUDY/'REPORT.md').open('a', encoding='utf-8') as handle:
                handle.write(f'\nИтоговый выбранный запуск: {selected["name"]}. Отложенная test-проверка и пакет модели: selected_model.json.\n')
        write(STUDY/'status.json', state)
    except BaseException as exc:
        state.update(status='failed', error=repr(exc), ended_utc=now()); write(STUDY/'status.json', state)
        aggregate(plan); raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--stop-file', type=Path, required=True)
    args = parser.parse_args()
    if args.stop_file.exists():
        parser.error('Stop file exists; use a new path for explicit resume')
    LOGS.mkdir(parents=True, exist_ok=True)
    with (LOGS/'queue.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.error('Study queue already runs')
        execute(args)


if __name__ == '__main__':
    main()
