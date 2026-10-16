"""Aggregate actual shared end-to-end seed assessments, never infer missing runs."""
import hashlib,json,statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];REPORT=ROOT/'docs/experiments/accuracy_v1'
def main():
    grouped={};skipped=[]
    with (ROOT/'ml-pipeline/evaluate_accuracy_pipeline.py').open('rb') as f:code_sha=hashlib.file_digest(f,'sha256').hexdigest()
    for file in (REPORT/'pipeline').glob('*_seed*_val.json'):
        row=json.loads(file.read_text(encoding='utf-8'));assert not row['partial']
        if row.get('code_sha256')!=code_sha:skipped.append(file.name);continue
        grouped.setdefault(row['model']['id'],{})[row['model']['seed']]=row
    def stats(values):
        return {'n':len(values),'mean':statistics.mean(values),'sd':statistics.stdev(values) if len(values)>1 else None}
    models={};paired={}
    base=grouped.get('yolo26n',{})
    for kind,rows in grouped.items():
        models[kind]={'seeds':sorted(rows),'complete_ten':set(rows)==set(range(20261003,20261013)),
            'map50_95':stats([r['disease_detection']['map50_95'] for r in rows.values()]),
            'macro_f1':stats([r['diagnosis_at_fixed_threshold']['macro_f1'] for r in rows.values()]),
            'correct_class_and_box_recall':stats([r['diagnosis_at_fixed_threshold']['correct_class_and_box_recall'] for r in rows.values()])}
        seeds=sorted(set(rows)&set(base))
        if kind!='yolo26n' and seeds:paired[kind]={'seeds':seeds,'ap50_95_delta':stats([rows[s]['disease_detection']['map50_95']-base[s]['disease_detection']['map50_95'] for s in seeds])}
    result={'models':models,'paired_vs_yolo26n':paired,'evaluator_code_sha256':code_sha,'skipped_stale_code':skipped,
            'scope':'Seed variability on the same field val, not independent field samples or real-world confidence interval'}
    (REPORT/'seed_summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result))
if __name__=='__main__':main()
