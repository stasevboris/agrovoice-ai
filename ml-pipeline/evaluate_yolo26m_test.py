"""Only the locked ten-M selection can read the historical test; no test-based retuning."""
from datetime import datetime,timezone
from yolo26m_validation import ROOT,ASSETS,FOCUS,CORE_SHA,read,save,sha,final_selection,check_preserved
def main():
    selection,base=final_selection()
    assert read(FOCUS/'validation_selection.json')['selection']==selection
    manifest=read(ASSETS/'models/manifest.json');check_preserved(manifest,base)
    model=selection['model'];assert next(x for x in manifest['models'] if x['id']=='yolo26m')==model
    assert sha(ASSETS/model['asset'])==model['sha256']
    lock=FOCUS/'final_test_selection.json'
    if lock.exists():assert read(lock)['selection']==selection
    else:save(lock,{'selection':selection,'locked_utc':datetime.now(timezone.utc).isoformat()})
    # Import and read test only after every cohort, recipe and selection guard above.
    from evaluate_accuracy_pipeline import evaluate
    path=FOCUS/'final_test/yolo26m_test.json'
    if path.exists():
        old=read(path);assert old['model']==model and old['code_sha256']==CORE_SHA;return
    report=evaluate(model,'test')
    assert report['images']==236 and not report['partial'] and report['diagnosis_at_fixed_threshold']['gt_leaves']==452
    report.update(code_sha256=CORE_SHA,plan_sha256=selection['plan_sha256'],scope='Historical PlantDoc test already seen in previous studies; not new independent fields')
    path.parent.mkdir(parents=True,exist_ok=True);save(path,report)
    print({'M_seed':model['seed'],'historical_test_AP':report['disease_detection']})
if __name__=='__main__':main()
