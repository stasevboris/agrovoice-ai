"""Read-only guards for the separately authorized M cohort and its retained S/N examples."""
import copy,math
from pathlib import Path
from yolo26_focus_validation import ROOT,ASSETS,CORE_SHA,FOCUS as S_FOCUS,SEEDS,read,sha,save,cohort_selection as s_selection
FOCUS=ROOT/'docs/experiments/yolo26m_v1'
RUNS=ROOT/'runs/yolo26m_v1'

def protocol():
    lock=read(FOCUS/'protocol_lock.json')
    assert sha(FOCUS/'plan.json')==lock['plan_sha256'],'Frozen M plan changed'
    plan=read(FOCUS/'plan.json')
    assert plan['seeds']==SEEDS and plan['jobs']==[{'id':f'yolo26m_seed{s}','kind':'yolo26m','seed':s} for s in SEEDS]
    assert sha(ROOT/'ml-pipeline/dataset_accuracy_v1/manifest.json')==plan['derived_manifest_sha256']
    assert read(ROOT/'ml-pipeline/dataset_accuracy_v1/manifest.json')['source_fingerprint']==plan['data_fingerprint']
    assert sha(ROOT/'ml-pipeline/yolo26m.pt')==plan['pretrained_sha256']
    assert (plan['epochs'],plan['patience'],plan['imgsz'],plan['batch'],plan['amp'],plan['optimizer'],plan['lr0'])==(60,60,416,16,False,'AdamW',.001)
    for name,digest in lock['protected_sources'].items():assert sha(ROOT/'ml-pipeline'/name)==digest,'Training/evaluation source changed: '+name
    assert sha(ROOT/'ml-pipeline/evaluate_accuracy_pipeline.py')==CORE_SHA
    return plan,lock

def base_examples():
    # S must be chosen from its real ten-seed cohort; a filename alone is insufficient.
    selected=s_selection()
    assert read(S_FOCUS/'validation_selection.json')['selection']==selected
    base=read(S_FOCUS/'apk_model_manifest.json')
    assert len(base['models'])==8
    for kind in ['yolo26n','yolo26s']:
        assert next(x for x in base['models'] if x['id']==kind)==selected['models'][kind]
    from yolo26_focus_validation import plan_and_examples,check_preserved_models
    _,old=plan_and_examples();check_preserved_models(base,old,selected['models']['yolo26s'])
    base_lock=FOCUS/'base_manifest_lock.json'
    if base_lock.exists():assert read(base_lock)['sha256']==sha(S_FOCUS/'apk_model_manifest.json'),'The retained eight-mode base changed'
    return base

def validate_seed(seed):
    plan,lock=protocol();assert seed in SEEDS
    run=RUNS/f'yolo26m_seed{seed}';info=read(run/'manifest.json');export=read(run/'export.json')
    assert info['status']=='completed' and info['kind']=='yolo26m' and info['seed']==seed
    assert (info['epochs'],info['input'],info['batch'])==(60,416,16)
    assert info['code_sha256']==lock['protected_sources']['yolo_m_accuracy.py']
    source=info['source_manifest']
    assert source['status']=='trained' and source['seed']==seed and source['dataset_fingerprint']==plan['data_fingerprint']
    assert (source['requested_epochs'],source['image_size'],source['batch'],source['patience'])==(60,416,16,60)
    assert source['amp_effective'] is False and source['optimizer']=='AdamW' and source['lr0']==.001
    assert export['status']=='passed' and sha(run/'model.onnx')==export['sha256']
    assert export['input_shape']==[1,3,416,416] and export['output_shape']==[1,34,3549]
    native=read(ROOT/f'runs/detect/accuracy_v1_yolo26m_seed{seed}/evaluation.json')
    assert native['completed_epochs']==60 and len(native['onnx_comparison'])==3
    val_file=FOCUS/'pipeline'/f'yolo26m_seed{seed}_val.json';val=read(val_file)
    assert not val['partial'] and val['split']=='val' and val['images']==349 and val['diagnosis_at_fixed_threshold']['gt_leaves']==1309
    assert val['code_sha256']==CORE_SHA and val['plan_sha256']==lock['plan_sha256']
    assert val['wrapper_sha256']==lock['protected_sources']['evaluate_yolo26m.py']
    assert val['model']['id']=='yolo26m' and val['model']['seed']==seed and val['model']['detector_seed']==seed and val['model']['sha256']==export['sha256']
    assert math.isfinite(val['disease_detection']['map50_95'])
    return {'seed':seed,'ap':val['disease_detection']['map50_95'],'graph_sha256':export['sha256'],
            'bytes':export['bytes'],'val_sha256':sha(val_file),'source':str(run.relative_to(ROOT))}

def model_for(row,base,final):
    model=copy.deepcopy(next(m for m in base['models'] if m['id']=='yolo26s'))
    model.update(id='yolo26m',name='YOLO26m',asset='models/model_lab/yolo26m.onnx',sha256=row['graph_sha256'],bytes=row['bytes'],
                 seed=row['seed'],detector_seed=row['seed'],source=row['source'],provisional=not final,
                 completed_runs=10 if final else 1,paired_evaluations=10 if final else 1,
                 selection='Maximum shared full-val AP50-95 across tenM; lower seed breaks ties' if final else 'Technical seed03; not a ten-run architectural result')
    return model

def check_preserved(manifest,base):
    assert len(manifest['models']) in (8,9)
    retained=[x for x in manifest['models'] if x['id']!='yolo26m']
    assert retained==base['models'],'A retained N/S/example was changed'
    if len(manifest['models'])==9:assert manifest['models'][-1]['id']=='yolo26m'
    for key in ['classes','labels_ru','dataset_fingerprint','two_stage_policy']:assert manifest[key]==base[key]
    for model in retained:
        for stage in [model]+([model['classifier']] if model.get('classifier') else []):assert sha(ASSETS/stage['asset'])==stage['sha256']

def pilot_selection():
    base=base_examples();row=validate_seed(SEEDS[0]);model=model_for(row,base,False)
    return {'study':'yolo26m_v1','stage':'technical_pilot','plan_sha256':sha(FOCUS/'plan.json'),
            'base_manifest_sha256':sha(S_FOCUS/'apk_model_manifest.json'),'model':model,'cohort':[row]},base

def final_selection():
    base=base_examples();plan,lock=protocol()
    queue=read(FOCUS/'queue_status.json')
    assert queue['status']=='completed' and queue['completed']==10 and queue['plan_sha256']==lock['plan_sha256']
    assert set(queue['jobs'])=={x['id'] for x in plan['jobs']} and all(x['status']=='completed' for x in queue['jobs'].values())
    gate=read(FOCUS/'phone_gate.json');assert gate['status']=='passed' and gate['model_sha256']==read(RUNS/f'yolo26m_seed{SEEDS[0]}/export.json')['sha256'] and gate['plan_sha256']==lock['plan_sha256']
    rows=[validate_seed(seed) for seed in SEEDS]
    best=max(rows,key=lambda x:(x['ap'],-x['seed']))
    return {'study':'yolo26m_v1','stage':'final_ten_seed','plan_sha256':lock['plan_sha256'],
            'base_manifest_sha256':sha(S_FOCUS/'apk_model_manifest.json'),'model':model_for(best,base,True),'cohort':rows},base
