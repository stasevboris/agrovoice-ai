"""Populate APK choices from real, completed, parity-checked exports only."""
import argparse,copy,json,shutil,os
from pathlib import Path
from datetime import datetime,timezone
from prepare_detector_dependencies import ROOT

ASSETS=ROOT/'android-app/app/src/main/assets/models'
REPORT=ROOT/'docs/experiments/accuracy_v1'
RUNS=ROOT/'runs/accuracy_v1'

def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def sha(p):
    import hashlib
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def graph(source,target,digest):
    assert source.is_file() and sha(source)==digest,source
    if not target.exists() or sha(target)!=digest:
        temporary=target.with_suffix('.part');shutil.copyfile(source,temporary);temporary.replace(target)

def choose(kind):
    ready=[]
    for run in RUNS.glob(f'{kind}_seed*'):
        if not (run/'export.json').exists():continue
        export=read(run/'export.json');manifest=read(run/'manifest.json')
        if export['status']=='passed' and manifest['status']=='completed':ready.append((manifest['best_validation_score'],run,manifest,export))
    assessed=[]
    for _,run,info,export in ready:
        file=REPORT/'pipeline'/f'{kind}_seed{info["seed"]}_val.json'
        if not file.exists():continue
        pipeline=read(file)
        if pipeline.get('partial') or pipeline.get('code_sha256')!=sha(ROOT/'ml-pipeline/evaluate_accuracy_pipeline.py'):continue
        source=pipeline['model'].get('classifier',pipeline['model'])
        if source['sha256']!=export['sha256']:continue
        assert pipeline['model']['seed']==info['seed']
        assessed.append((pipeline['disease_detection']['map50_95'],run,
                         dict(info,_pipeline=pipeline,paired_evaluation_count=len(ready)),export))
    # Complete cohorts are selected on the actual photo pipeline. Early APKs remain prototypes.
    if len(ready)==10 and len(assessed)==10:return max(assessed,key=lambda item:item[0]),len(ready)
    for _,_,info,_ in ready:info['paired_evaluation_count']=len(assessed)
    return max(ready,key=lambda item:item[0]) if ready else None,len(ready)

def local_path(value):
    if os.name=='nt' and value.startswith('/mnt/'):
        value=value[5].upper()+':/'+value[7:]
    path=Path(value).resolve();path.relative_to(ROOT.resolve())
    return path

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--require',nargs='*',default=[]);args=parser.parse_args()
    manifest=read(ASSETS/'manifest.json');baseline=copy.deepcopy(next(m for m in manifest['models'] if m['id']=='yolo11n'))
    baseline.update(family='yolo',seed=20261003,detector_seed=20261003,dataset='clean v2',provisional=False,side=416)
    assert sha(ASSETS/'yolo11n.onnx')==baseline['sha256']
    package=ROOT/'ml-pipeline/exports/study10_v2_yolo26n_seed20261012'
    checks=read(package/'sha256.json')
    # Different historical checksum formats are normalized without ignoring verification.
    expected=checks.get('model.onnx') if isinstance(checks,dict) else None
    if isinstance(expected,dict):expected=expected.get('sha256')
    digest=sha(package/'model.onnx')
    assert digest=='0ccf00a13dabcfd86980440cddbf4acc465ef25fed71d1c49751cdab9afbbe0e'
    if expected:assert expected==digest
    graph(package/'model.onnx',ASSETS/'yolo26n.onnx',digest)
    base={'id':'yolo26n','name':'YOLO26n','asset':'models/yolo26n.onnx','sha256':digest,'bytes':(package/'model.onnx').stat().st_size,
          'family':'yolo','side':416,'seed':20261012,'detector_seed':20261012,'dataset':'clean v2','provisional':False,
          'source':str((package/'model.onnx').relative_to(ROOT)),'input_shape':[1,3,416,416],'output_shape':[1,34,3549]}
    models=[baseline,base];counts={}
    kinds=[('mobilenet_v3_large','Лист → MobileNet'),('efficientnet_b0','Лист → EfficientNet'),
           ('masked_mobilenet_v3_large','Маска → диагноз'),('distilled_mobilenet_v3_large','Ученик MobileNet')]
    for kind,title in kinds:
        selected,count=choose(kind);counts[kind]=count
        if selected is None:continue
        _,run,info,export=selected
        assert info['data_sha256']==sha(ROOT/'ml-pipeline/dataset_accuracy_v1/manifest.json')
        graph(run/'model.onnx',ASSETS/f'{kind}.onnx',export['sha256'])
        model=copy.deepcopy(base)
        if '_pipeline' in info:
            paired=info['_pipeline']['model'];source=local_path(paired['asset']);name=f'yolo26n_leaf_seed{info["seed"]}.onnx'
            graph(source,ASSETS/name,paired['sha256'])
            model.update(asset='models/'+name,sha256=paired['sha256'],bytes=source.stat().st_size,
                         detector_seed=info['seed'],source=str(source.relative_to(ROOT)))
        model.update(id=kind,name=title,family='two_stage',seed=info['seed'],
            provisional=count<10 or info['paired_evaluation_count']<10,dataset='clean v2 detector + accuracy_v1 inherited crops',
            completed_runs=count,paired_evaluations=info['paired_evaluation_count'],
            selection='shared end-to-end val AP50-95' if '_pipeline' in info else 'provisional GT-crop macro-F1')
        model['classifier']={'id':kind+'_classifier','kind':kind,'asset':f'models/{kind}.onnx','sha256':export['sha256'],
            'bytes':export['bytes'],'side':224,'seed':info['seed'],'source':str(run.relative_to(ROOT)),
            'data_sha256':info['data_sha256'],'dependency':export.get('dependency'),
            'input_shape':[1,3,224,224],'output_shape':[1,30],'normalization':'ImageNet normalization embedded in graph'}
        models.append(model)
    for kind,title,family in [('yolo26s','YOLO26s','yolo'),('deimv2_n','DEIMv2-N','detr')]:
        selected,count=choose(kind);counts[kind]=count
        if selected is None:continue
        _,run,info,export=selected
        graph(run/'model.onnx',ASSETS/f'{kind}.onnx',export['sha256'])
        model={'id':kind,'name':title,'family':family,'side':416,'seed':info['seed'],'detector_seed':info['seed'],
               'asset':f'models/{kind}.onnx','sha256':export['sha256'],'bytes':export['bytes'],
               'provisional':count<10 or info['paired_evaluation_count']<10,
               'dataset':'clean v2','completed_runs':count,'paired_evaluations':info['paired_evaluation_count'],
               'selection':'shared end-to-end val AP50-95' if '_pipeline' in info else 'provisional native detector val',
               'source':str(run.relative_to(ROOT)),
               'input_shape':[1,3,416,416],'output_shape':[1,300,6] if family=='detr' else [1,34,3549]}
        if family=='detr':
            licenses=ROOT/'android-app/app/src/main/assets/licenses';licenses.mkdir(exist_ok=True)
            shutil.copyfile(ROOT/'ml-pipeline/third_party/deimv2/LICENSE.md',licenses/'DEIMv2-LICENSE.md')
            model['license']='DEIMv2 non-commercial; academic prototype'
        models.append(model)
    missing=set(args.require)-{m['id'] for m in models}
    assert not missing,('Required trained modes unavailable',missing)
    manifest.update(schema=2,models=models,updated_utc=datetime.now(timezone.utc).isoformat(),
                    accuracy_dataset_sha256=sha(ROOT/'ml-pipeline/dataset_accuracy_v1/manifest.json'),
                    two_stage_policy={'proposal_threshold':.1,'nms_iou':.7,'class_agnostic':True,'max_classified':32,
                                      'margin_each_side':.1,'score':'detector maximum class score times classifier softmax'})
    text=json.dumps(manifest,ensure_ascii=False,indent=2)
    (ASSETS/'manifest.json').write_text(text,encoding='utf-8');(REPORT/'apk_model_manifest.json').write_text(text,encoding='utf-8')
    print(json.dumps({'packaged_modes':[m['id'] for m in models],'completed_runs':counts},ensure_ascii=False))

if __name__=='__main__':main()
