"""Locked final test only after all training cohorts and paired val selections finish."""
import argparse,json
from datetime import datetime,timezone
from evaluate_accuracy_pipeline import ROOT,REPORT,ASSETS,read,sha,evaluate

def save(path,value):
    temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8');temporary.replace(path)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--models',nargs='*');args=parser.parse_args()
    expected={'mobilenet_v3_large','efficientnet_b0','yolo26s','deimv2_n',
              'masked_mobilenet_v3_large','distilled_mobilenet_v3_large'}
    queue=read(REPORT/'architecture_status.json');plan=read(REPORT/'architecture_plan.json')
    assert queue['status']=='completed','Finish all architecture jobs before final test'
    assert len(plan['jobs'])==80
    for job in plan['jobs']:
        run=ROOT/'runs/accuracy_v1'/job['id']
        assert read(run/'manifest.json')['status']=='completed',job['id']
        export=read(run/'export.json');assert export['status']=='passed' and sha(run/'model.onnx')==export['sha256'],job['id']
    manifest=read(ASSETS/'models/manifest.json');models={m['id']:m for m in manifest['models']}
    assert expected<=set(models),'Package all final variants first'
    code_sha=sha(ROOT/'ml-pipeline/evaluate_accuracy_pipeline.py')
    for kind in expected:
        model=models[kind]
        assert not model['provisional'] and model['completed_runs']==10 and model['paired_evaluations']==10,kind
        rows=[read(REPORT/'pipeline'/f'{kind}_seed{s}_val.json') for s in range(20261003,20261013)]
        assert all(not r['partial'] and r['code_sha256']==code_sha for r in rows),kind
        picked=next(r for r in rows if r['model']['seed']==model['seed'])['model']
        assert picked['sha256']==model['sha256'] and picked.get('classifier',{}).get('sha256')==model.get('classifier',{}).get('sha256'),kind
    selected=sorted(args.models or expected)
    assert set(selected)<=set(models),'Unknown test mode'
    assert set(selected)&expected,'Select at least one new trained variant'
    selection={'models':{k:models[k] for k in selected},'validation_code_sha256':code_sha,
               'data_sha256':manifest['accuracy_dataset_sha256'],
               'rule':'Weights and modes selected on val before reading final test; same historical test, new external field control still required'}
    lock=REPORT/'final_test_selection.json'
    if lock.exists():assert read(lock)['selection']==selection,'Final test choice locked; do not retune against this test'
    else:save(lock,{'selection':selection,'locked_utc':datetime.now(timezone.utc).isoformat()})
    folder=REPORT/'final_test';folder.mkdir(exist_ok=True)
    for kind in selected:
        path=folder/f'{kind}_test.json'
        if path.exists():
            prior=read(path)
            assert prior['model']==models[kind] and prior['code_sha256']==code_sha
            print('REUSE '+path.name,flush=True);continue
        result=evaluate(models[kind],'test')
        result.update(code_sha256=code_sha,selection_lock_sha256=sha(lock),
                      note='One val-selected checkpoint per variant; not ten-training test mean. Historical test already seen in prior study; absent classes cannot be assessed.')
        save(path,result)
        print(json.dumps({'mode':kind,'seed':models[kind]['seed'],'test':result['disease_detection']}),flush=True)

if __name__=='__main__':main()
