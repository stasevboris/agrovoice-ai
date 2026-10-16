"""M uses the unchanged shared CPU evaluator; neither new test reads nor APK mutation."""
import argparse,json
from pathlib import Path
import evaluate_accuracy_pipeline as core
from yolo26m_validation import protocol
ROOT=core.ROOT
FOLDER=ROOT/'docs/experiments/yolo26m_v1'
EXPECTED='84d1b8cb13094aac55baaf23ae81f252e8f10828f3662415254a5dcc82a0127a'
def main():
    p=argparse.ArgumentParser();p.add_argument('--seeds',nargs='+',type=int,default=[20261003]);args=p.parse_args()
    plan,lock=protocol()
    assert core.sha(Path(core.__file__))==EXPECTED
    template=next(x for x in core.read(ROOT/'docs/experiments/yolo26s_focus_v1/pilot_apk_manifest_0.8.json')['models'] if x['id']=='yolo26s')
    for seed in args.seeds:
        assert seed in range(20261003,20261013)
        run=ROOT/f'runs/yolo26m_v1/yolo26m_seed{seed}'
        info=core.read(run/'manifest.json');export=core.read(run/'export.json');file=run/'model.onnx'
        assert info['status']=='completed' and export['status']=='passed' and info['epochs']==60 and info['seed']==seed
        assert core.sha(file)==export['sha256']
        model=dict(template);model.update(id='yolo26m',name='YOLO26m',asset=str(file),sha256=export['sha256'],bytes=export['bytes'],seed=seed,detector_seed=seed,source=str(run),provisional=True)
        output=FOLDER/'pipeline'/f'yolo26m_seed{seed}_val.json';output.parent.mkdir(parents=True,exist_ok=True)
        if output.exists():
            old=core.read(output)
            if old['model']['sha256']==export['sha256'] and old['code_sha256']==EXPECTED and old.get('plan_sha256')==lock['plan_sha256'] and old.get('wrapper_sha256')==core.sha(__file__) and old['split']=='val' and old['images']==349 and old['diagnosis_at_fixed_threshold']['gt_leaves']==1309 and not old['partial']:
                print('REUSE '+output.name);continue
        report=core.evaluate(model,'val')
        assert report['images']==349 and not report['partial'] and report['diagnosis_at_fixed_threshold']['gt_leaves']==1309
        report.update(code_sha256=EXPECTED,wrapper_sha256=core.sha(__file__),plan_sha256=core.sha(FOLDER/'plan.json'))
        output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({'seed':seed,'AP':report['disease_detection'],'diagnosis':report['diagnosis_at_fixed_threshold']['correct_class_and_box_recall']}))
if __name__=='__main__':main()
