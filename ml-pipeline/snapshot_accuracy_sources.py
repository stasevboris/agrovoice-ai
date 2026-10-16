"""Preserve executed/current source versions alongside the reproducibility evidence."""
import hashlib,json
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1];REPORT=ROOT/'docs/experiments/accuracy_v1'
def sha(value):return hashlib.sha256(value).hexdigest()
def main():
    folder=REPORT/'source_snapshots';folder.mkdir(exist_ok=True);records=[]
    index=REPORT/'source_snapshots.json'
    if index.exists():
        for previous in json.loads(index.read_text(encoding='utf-8'))['sources']:
            target=ROOT/previous['snapshot']
            assert target.exists() and sha(target.read_bytes())==previous['sha256']
            if not any(r['sha256']==previous['sha256'] and r['script']==previous['script'] for r in records):records.append(previous)
    def keep(name,value,scope):
        digest=sha(value);target=folder/f'{name}_{digest[:16]}.py'
        if target.exists():assert sha(target.read_bytes())==digest
        else:target.write_bytes(value)
        if not any(r['sha256']==digest and r['script']==name+'.py' for r in records):
            records.append({'script':name+'.py','sha256':digest,'snapshot':str(target.relative_to(ROOT)),'scope':scope})
    names=['accuracy_study','accuracy_component','architecture_queue','yolo_s_accuracy','deim_accuracy',
           'prepare_accuracy_data','evaluate_accuracy_pipeline','evaluate_accuracy_final_test',
           'package_accuracy_models','verify_accuracy_phone','summarize_accuracy_seeds',
           'deim_export_validation','diagnose_deim_parity','validate_deim_export_full',
           'evaluate_accuracy_teacher','yolo26s_focus_queue','yolo26_focus_validation',
           'package_yolo26s_focus','evaluate_yolo26s_focus_test','build_yolo26s_focus_report']
    for name in names:keep(name,(ROOT/'ml-pipeline'/f'{name}.py').read_bytes(),'Current source snapshot; manifests identify the version actually executed')
    current=(ROOT/'ml-pipeline/accuracy_study.py').read_text(encoding='utf-8')
    old=current.replace("            if not torch.isfinite(loss).item():raise RuntimeError(f'Non-finite {kind} loss at epoch {epoch+1}')\n"
                        '            loss.backward();nn.utils.clip_grad_norm_(net.parameters(),5,error_if_nonfinite=True);optimizer.step()',
                        '            loss.backward();nn.utils.clip_grad_norm_(net.parameters(),5);optimizer.step()')
    recovered=[]
    for seed in [20261003,20261004]:
        file=ROOT/'runs/accuracy_v1'/f'mobilenet_v3_large_seed{seed}'/'manifest.json'
        expected=json.loads(file.read_text(encoding='utf-8'))['code_sha256']
        matches=[value for value in [old.encode('utf-8'),old.replace('\n','\r\n').encode('utf-8')] if sha(value)==expected]
        if matches:
            keep('accuracy_study',matches[0],f'Exact checksum matches the source recorded by MobileNet seed{seed}, before finite-value guard')
            recovered.append(seed)
    result={'updated_utc':datetime.now(timezone.utc).isoformat(),'sources':records,
            'earlier_mobilenet_source_verified_seeds':recovered,
            'note':'Historical reconstruction is included only when its full SHA matches the run manifest; snapshots do not alter training or data'}
    (REPORT/'source_snapshots.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'current_sources':len(names),'historical_verified':recovered}))
if __name__=='__main__':main()
