"""Ten counterbalanced fresh-process series per architecture; keep original v1 weights fixed."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import time
import uuid
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
ADB = Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'
SERIAL = 'b861c4e0'
APP = 'com.agronomy.phytomonitor'
REMOTE = f'/sdcard/Android/data/{APP}/files/benchmark/'
OUTPUT = ROOT/'docs/experiments/redmi_note_7_10series'
MODELS = {m+'_416': ROOT/f'ml-pipeline/exports/plantdoc_clean_v1_{m}_416/model.onnx' for m in ('yolo11n', 'yolo26n')}


def adb(*args, check=True):
    return subprocess.run([str(ADB), '-s', SERIAL, *args], capture_output=True, timeout=30, check=check)


def shell(*args):
    return adb('shell', *args).stdout.decode('utf-8', 'replace')


def context():
    battery = shell('dumpsys', 'battery')
    thermal = shell('dumpsys', 'thermalservice')
    return dict(battery=[l.strip() for l in battery.splitlines() if any(l.strip().startswith(k) for k in
                ('level:', 'temperature:', 'status:', 'USB powered:', 'AC powered:'))],
                thermal=[l.strip() for l in thermal.splitlines() if 'Thermal Status' in l or 'IsStatusOverride' in l])


def digest(path):
    with Path(path).open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def summarize(records, protocol):
    def describe(values):
        return dict(n=len(values), mean=statistics.mean(values), sd=statistics.stdev(values) if len(values)>1 else None,
                    min=min(values), max=max(values)) if values else None
    aggregate = {}
    for label in MODELS:
        rows = [r for r in records if r['label']==label]
        aggregate[label] = dict(series=len(rows),
          inference_median_ms=describe([r['inference']['median_ms'] for r in rows]),
          pipeline_median_ms=describe([r['inference_and_postprocess']['median_ms'] for r in rows]),
          pipeline_p95_ms=describe([r['inference_and_postprocess']['p95_ms'] for r in rows]),
          peak_process_rss_bytes=describe([r['memory']['peak_process_rss_bytes'] for r in rows]))
    paired = []
    for block in range(10):
        pair = {r['label']: r for r in records if r['block']==block}
        if len(pair)==2:
            paired.append(dict(block=block, pipeline_difference_26_minus_11_ms=
              pair['yolo26n_416']['inference_and_postprocess']['median_ms']-pair['yolo11n_416']['inference_and_postprocess']['median_ms']))
    result = dict(protocol=protocol, completed_series=len(records), requested_series=20,
                  status='completed' if len(records)==20 else 'incomplete', aggregate=aggregate, paired=paired, series=records)
    (OUTPUT/'results.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
    lines = ['# Redmi Note 7 — десять серий на архитектуру', '',
       f'Завершено: {len(records)}/20 серий. Свежий процесс на каждую серию, 10 прогревов + 40 измерений. Порядок моделей чередуется, пауза между сериями 10 секунд.', '',
       '**Это прежние веса clean v1, по одному обучению архитектуры. Повторён замер телефона; серия обучений clean v2 проводится отдельно.**', '',
       '| Модель | Серий | Граф + обработка, среднее медиан ± SD, мс | Диапазон медиан, мс |',
       '|---|---:|---:|---:|']
    for label, values in aggregate.items():
        d = values['pipeline_median_ms']
        fmt = f'{d["mean"]:.2f} ± {d["sd"]:.2f}' if d and d['sd'] is not None else '—'
        span = f'{d["min"]:.2f}–{d["max"]:.2f}' if d else '—'
        lines.append(f'| {label} | {values["series"]}/10 | {fmt} | {span} |')
    lines += ['', 'CPU FP32, 4 потока, ONNX Runtime Android 1.30.0. Камера, декодирование, масштабирование и отрисовка исключены. Повторные серии на одном телефоне зависят от температуры и фоновой нагрузки; это не десять разных устройств. Условия до/после каждой серии и проверки первого выхода сохранены в JSON.']
    (OUTPUT/'REPORT.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if (OUTPUT/'results.json').exists():
        raise RuntimeError('This phone study already exists; preserve it and use a new version for another study.')
    protocol = dict(device='Redmi Note 7 6/64, Android 13', date_utc=datetime.now(timezone.utc).isoformat(),
          series_per_architecture=10, iterations_per_series=40, warmup=10, cooldown_seconds=10,
          weights='Original clean v1, not new v2 training', model_sha256={m:digest(p) for m,p in MODELS.items()},
          input_sha256={f'input_416_{i}.bin':digest(ROOT/f'ml-pipeline/.cache/phone_benchmark/input_416_{i}.bin') for i in range(5)},
          apk_sha256=digest(ROOT/'android-app/app/build/outputs/apk/debug/app-debug.apk'),
          order='AB/BA alternating by block; each launch force-stops prior process',
          memory='Absolute application RSS, not model alone', latency='Prepared tensor inference plus output copy and class-aware NMS')
    for label, checksum in protocol['model_sha256'].items():
        if shell('sha256sum', REMOTE+label+'.onnx').split()[0] != checksum:
            raise RuntimeError('Device model checksum mismatch')
    for filename, checksum in protocol['input_sha256'].items():
        if shell('sha256sum', REMOTE+filename).split()[0] != checksum:
            raise RuntimeError('Device input checksum mismatch')
    input_batch=np.fromfile(ROOT/'ml-pipeline/.cache/phone_benchmark/input_416_0.bin', dtype='<f4').reshape(1,3,416,416)
    references={}
    for label, path in MODELS.items():
        options=ort.SessionOptions(); options.intra_op_num_threads=4
        session=ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])
        references[label]=session.run(None,{session.get_inputs()[0].name:input_batch})[0]
    records=[]; summarize(records,protocol)
    try:
        for block in range(10):
            order=list(MODELS) if block%2==0 else list(reversed(MODELS))
            for label in order:
                run_id=f'block{block}_{label}_{uuid.uuid4().hex}'
                previous=shell('cat', REMOTE+label+'.json')
                before=context()
                adb('shell','am','force-stop',APP)
                adb('shell','am','start','-n',f'{APP}/.ModelBenchmarkActivity','--es','model',label,
                    '--ei','iterations','40','--es','run_id',run_id)
                deadline=time.monotonic()+90
                while time.monotonic()<deadline:
                    raw=shell('cat', REMOTE+label+'.json')
                    try:record=json.loads(raw)
                    except json.JSONDecodeError:time.sleep(.5);continue
                    if record.get('run_id')==run_id:break
                    if raw!=previous and record.get('status')=='failed':raise RuntimeError(record)
                    time.sleep(.5)
                else:raise TimeoutError(f'No fresh report for {run_id}')
                if record['status']!='passed' or record['iterations']!=40 or record['warmup']!=10:
                    raise RuntimeError(record)
                after=context()
                local=ROOT/'ml-pipeline/.cache/phone_benchmark/results'/f'{run_id}.bin'
                local.parent.mkdir(parents=True,exist_ok=True)
                adb('pull',REMOTE+label+'_output.bin',str(local))
                actual=np.fromfile(local,dtype='<f4').reshape(record['output_shape'])
                reference=references[label]
                if not np.isfinite(actual).all() or not np.allclose(actual,reference,rtol=1e-4,atol=2e-3):
                    raise RuntimeError('Fresh Android output differs from desktop')
                record.update(block=block,context_before=before,context_after=after,
                              desktop_output_check=dict(status='passed',samples=1,max_absolute_error=float(np.abs(actual-reference).max())))
                records.append(record)
                (OUTPUT/f'{block:02d}_{label}.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
                summarize(records,protocol)
                print(f'{len(records)}/20 {label}: {record["inference_and_postprocess"]["median_ms"]:.2f} ms',flush=True)
                adb('shell','am','force-stop',APP)
                time.sleep(10)
    finally:
        adb('shell','am','force-stop',APP)


if __name__=='__main__':
    main()
