"""Summarize completed fresh-process real-photo benchmark series."""
import json,statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];REPORT=ROOT/'docs/android/accuracy_v1/benchmark'
def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def main():
    batch=read(REPORT/'latest_batch.json');assert batch['status']=='passed' and batch['series']==10
    grouped={};seen=set();photos=set()
    for filename in batch['files']:
        row=read(filename);assert row['status']=='passed' and row['iterations']==10
        assert row['series'] not in seen;seen.add(row['series']);photos.add(row['photo_sha256'])
        for model in row['results']:grouped.setdefault(model['model'],[]).append(model)
    result={'status':'passed','series':len(seen),'iterations_per_mode_per_series':10,'warmup_per_mode_per_series':3,
            'photo_sha256':list(photos),'models':{},'scope':'Warm decoded-bitmap analysis: preparation, detector, crops/classifier, postprocessing; excludes file decode/import, camera, saving and rendering. One potato photo, ten fresh processes, rotated mode order; not ten independent training runs'}
    for kind,rows in grouped.items():
        assert len(rows)==10
        def stats(key):
            values=[r[key] for r in rows];return {'mean_of_series_medians_ms':statistics.mean(values),'sd_of_series_medians_ms':statistics.stdev(values)}
        provenance={(r['seed'],r['detector_seed'],r['detector_sha256'],r.get('classifier_sha256')) for r in rows}
        assert len(provenance)==1
        result['models'][kind]={'series':len(rows),'inference':stats('median_inference_ms'),'pipeline':stats('median_pipeline_ms'),
             'maximum_rss_bytes_after':max(r.get('rss_bytes_after',r.get('rss_kb_after')) for r in rows),
             'maximum_process_hwm_bytes':max(r.get('process_hwm_bytes',r.get('process_hwm_kb')) for r in rows),
             'memory_unit_note':'memory() returns bytes; older helper incorrectly named the raw fields kb',
             'provenance':list(provenance)[0]}
    (REPORT/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
