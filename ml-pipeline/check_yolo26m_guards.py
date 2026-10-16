"""Record genuine negative preflight checks while the S worker is still running."""
import subprocess,sys,os
from datetime import datetime,timezone
import psutil
from yolo26m_validation import ROOT,FOCUS,ASSETS,read,save,sha,protocol

def main():
    _,lock=protocol();active=read(ROOT/'ml-pipeline/logs/active_training.json')
    worker=psutil.Process(active['pid'])
    assert worker.is_running() and worker.name().lower()=='wsl.exe' and active['study']=='yolo26s_focus_v1'
    assert read(ROOT/'docs/experiments/yolo26s_focus_v1/status.json')['completed']<10
    protected=[ROOT/'ml-pipeline'/x for x in lock['protected_sources']]+[
        ROOT/'docs/experiments/yolo26s_focus_v1/plan.json',ROOT/'docs/experiments/accuracy_v1/architecture_plan.json',
        ASSETS/'models/manifest.json',ROOT/'ml-pipeline/logs/active_training.json',
        ROOT/'ml-pipeline/dataset_accuracy_v1/manifest.json',ROOT/'ml-pipeline/dataset_clean_v2/manifest.json',
        ROOT/'artifacts/apk/current_model_lab.json',FOCUS/'status.json']
    before={str(p.relative_to(ROOT)):sha(p) for p in protected}
    forbidden=[FOCUS/'phone_gate.json',FOCUS/'queue_status.json',FOCUS/'validation_selection.json',FOCUS/'pilot_selection.json',FOCUS/'final_test_selection.json']
    assert all(not p.exists() for p in forbidden),'Run this only before M has started'
    tasks=[([sys.executable,str(ROOT/'ml-pipeline'/name),*args],expected) for name,args,expected in [
        ('yolo_m_accuracy.py',['--seed','20261003'],'Finish all ten YOLO26s'),
        ('package_yolo26m.py',['--pilot'],'Finish all ten YOLO26s'),
        ('package_yolo26m.py',[],'Finish all ten YOLO26s'),
        ('evaluate_yolo26m_test.py',[],'Finish all ten YOLO26s'),
        ('mark_yolo26m_phone_gate.py',[],'Finish all ten YOLO26s'),
        ('yolo26m_queue.py',[],'Finish all ten YOLO26s')]]
    for name in ['Start-Yolo26mPilot.ps1','Start-Yolo26mStudy.ps1']:
        tasks.append((['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'ml-pipeline'/name)],'A GPU worker is already active'))
    rows=[]
    for command,expected in tasks:
        r=subprocess.run(command,cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
        output=r.stdout+'\n'+r.stderr
        assert r.returncode!=0 and expected in output,output
        rows.append({'entrypoint':Path(command[1]).name if command[0]==sys.executable else Path(command[-1]).name,
                     'status':'expected_refusal','exit_code':r.returncode,'expected_reason':expected,'output':output[-2500:]})
    assert before=={str(p.relative_to(ROOT)):sha(p) for p in protected},'A refusal changed protected state'
    assert all(not p.exists() for p in forbidden)
    assert worker.is_running() and worker.create_time()==psutil.Process(active['pid']).create_time()
    report={'status':'passed','checked_utc':datetime.now(timezone.utc).isoformat(),'scope':'Eight actual negative entrypoint calls before S completion. No completed M or phone quality is implied.',
            'active_s_pid':active['pid'],'m_gpu_started':False,'plan_sha256':lock['plan_sha256'],'checks':rows,'protected_sha_before_and_after':before,'new_M_selection_or_queue_files':False}
    save(FOCUS/'guard_checks.json',report);print({'negative_checks':len(rows),'status':'passed','S_worker_unchanged':True})

from pathlib import Path
if __name__=='__main__':main()
