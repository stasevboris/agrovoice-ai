"""Save a truthful continuation point. Budget inputs must come from get_usage_limits."""
import argparse,json,os,csv
from pathlib import Path
from datetime import datetime,timezone,timedelta
import psutil
ROOT=Path(__file__).resolve().parents[1]
def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def save(p,value):
    p=Path(p);temporary=p.with_suffix('.tmp');temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8');temporary.replace(p)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--remaining',type=int,required=True);parser.add_argument('--weekly',type=int,required=True)
    args=parser.parse_args();now=datetime.now(timezone.utc);file=ROOT/'docs/session/accuracy_work_state.json';state=read(file)
    active=read(ROOT/'ml-pipeline/logs/active_training.json');running=False
    try:
        p=psutil.Process(active['pid']);running=p.is_running() and p.name().lower()=='wsl.exe' and abs(p.create_time()-datetime.fromisoformat(active['started_utc']).timestamp())<10
    except psutil.Error:pass
    focus=active.get('study')=='yolo26s_focus_v1'
    medium=active.get('study')=='yolo26m_v1'
    yolo_scope=focus or medium or bool(state.get('yolo26m_followup'))
    status_path=active.get('status_path','docs/experiments/accuracy_v1/architecture_status.json')
    plan_path='docs/experiments/yolo26m_v1/plan.json' if medium else 'docs/experiments/yolo26s_focus_v1/plan.json' if focus else 'docs/experiments/accuracy_v1/architecture_plan.json'
    queue=read(ROOT/status_path)
    current=queue.get('current',{})
    observed_progress=None
    for name in ['detector_progress','progress']:
        progress_file=ROOT/'docs/experiments/accuracy_v1'/f'{name}.json'
        if progress_file.exists():
            progress=read(progress_file)
            if progress.get('job')==current.get('id'):observed_progress=progress
    if current.get('kind') in ('yolo26s','yolo26m'):
        job_id=current.get('id') or f"{current['kind']}_seed{current['seed']}"
        csv_file=ROOT/'runs/detect'/('accuracy_v1_'+job_id)/'results.csv'
        if csv_file.exists():
            with csv_file.open(encoding='utf-8-sig') as f:
                rows=[{k.strip():v for k,v in row.items() if k is not None} for row in csv.DictReader(f)]
            rows=[r for r in rows if r.get('epoch') and r.get('time') and r.get('metrics/mAP50-95(B)')]
            if rows:observed_progress={'job':job_id,'epoch':int(rows[-1]['epoch']),'epochs':60,
                'best_score':max(float(r['metrics/mAP50-95(B)']) for r in rows),
                'updated_utc':datetime.fromtimestamp(csv_file.stat().st_mtime,timezone.utc).isoformat(),
                'source':str(csv_file.relative_to(ROOT)),'scope':'Recorded native per-epoch val; shared full-val final assessment separate'}
    components={}
    for kind in ['mobilenet_v3_large','efficientnet_b0','yolo26s','deimv2_n','leaf_mask',
                 'teacher_efficientnet_v2_s','masked_mobilenet_v3_large','distilled_mobilenet_v3_large']:
        ready=[]
        for run in (ROOT/'runs/accuracy_v1').glob(kind+'_seed*'):
            if not (run/'export.json').exists() or not (run/'manifest.json').exists():continue
            if read(run/'manifest.json')['status']=='completed' and read(run/'export.json')['status']=='passed':ready.append(run.name)
        components[kind]={'completed_exports':len(ready),'required':10,'runs':ready}
        if yolo_scope and kind!='yolo26s':components[kind].update(required=None,status='discontinued_by_user' if kind=='deimv2_n' else 'example_only')
    phonefile=ROOT/'docs/android/accuracy_v1/verification.json'
    phone=read(phonefile) if phonefile.exists() else {}
    wait=state.get('resume_not_before_local')
    if wait and datetime.fromisoformat(wait)<=now and args.remaining>15:wait=None
    if args.remaining<=15 and not wait:
        local=now.astimezone(timezone(timedelta(hours=3)))
        wait=(local+timedelta(days=1)).replace(hour=1,minute=0,second=0,microsecond=0).isoformat()
    state.update(status='waiting_budget' if wait else 'active',updated_utc=now.isoformat(),resume_not_before_local=wait,
        last_known_remaining_percent=args.remaining,last_known_weekly_remaining_percent=args.weekly,
        budget_check_source='Actual get_usage_limits tool; supplied at time of continuation write',
        active_worker=active,worker_running=running,authoritative_status=status_path,
        queue_plan=plan_path,queue_jobs=len(read(ROOT/plan_path)['jobs']),
        branches={'two_stage':{k:components[k] for k in ['mobilenet_v3_large','efficientnet_b0']},
                  'detr':components['deimv2_n'],'larger_yolo':components['yolo26s'],
                  'segmentation':{k:components[k] for k in ['leaf_mask','masked_mobilenet_v3_large']},
                  'distillation':{k:components[k] for k in ['teacher_efficientnet_v2_s','distilled_mobilenet_v3_large']}},
        queue_status=queue['status'],current_job=current,observed_progress=observed_progress,
        apk={'verification_status':phone.get('status'),'verified_modes':phone.get('modes',[]),
             'verified_apk_sha256':phone.get('apk_sha256'),'evidence':str(phonefile.relative_to(ROOT)),
             'all_five_final':False},
        report='docs/reports/06_FIVE_VARIANTS_PROGRESS_2026-10-04.md',
        next=['Watch architecture_status and active worker; do not start another GPU load',
              'Assess newly completed exports on shared val; all five pilot directions are already in APK0.8, avoid rebuilding after every new seed',
              'Run evaluate_accuracy_pipeline.py --seeds all ten; summarize_accuracy_seeds.py; final selection uses shared end-to-end AP',
              'Rebuild and repeat phone parity/timing for a needed fix or final ten-seed selection; preserve APK0.8 pilots, refresh report and journal',
              'Complete all 80 jobs and final APK; only then pause automation'])
    if focus:
        state.update(objective='Complete ten YOLO26s seeds, compare to fixed best YOLO26n from completed ten-run study, retain all eight APK examples; research other YOLO versions',
            active_scope='yolo26s_focus_v1',report='docs/reports/07_YOLO26_FOCUS_2026-10-04.md',
            eta_evidence='docs/experiments/yolo26s_focus_v1/eta.json',
            pending_scope_question={'status':'resolved','resolution':'Boris fully discontinued DETR and chose YOLO26s/YOLO26n; all current APK examples remain','plan_changed':True},
            next=['Watch one focus worker; resume only via Start-Yolo26sFocus.ps1 after its actual exit',
                  'Assess completed YOLO26s seeds with unchanged shared evaluate_accuracy_pipeline.py and summarize_accuracy_seeds.py',
                  'Select after ten full-val reports via package_yolo26s_focus.py; keep best YOLO26n12 and all APK0.8 examples',
                  'Use evaluate_yolo26s_focus_test.py for explicit new ten-S protocol; do not bypass original 80-job guard',
                  'Build a new APK version, install and repeat phone parity/ten timing series, finish focus report and then pause automation'])
    if state.get('yolo26m_followup'):
        state['objective']='Complete tenS and separate authorized tenM with technical pilot/mobile gate, compareN/S/M, retain eight examples and verified voice controls'
        state['next']=[
            'Keep one active GPU worker. Finish10S/fullval/locked selection/final phone checks before M',
            'Start-Yolo26mPilot.ps1 after actual S worker exit; fullval with evaluate_yolo26m.py, package_yolo26m.py --pilot',
            'Add real ninthM/voice model9, newAPK, actualNote7 parity/ten timing series; mark_yolo26m_phone_gate.py',
            'Start-Yolo26mStudy.ps1 only after true mobilegate; ten exports/fullval including reused technical03',
            'package_yolo26m.py requires10M for final selection, evaluate_yolo26m_test.py locks selection before test',
            'FinalAPK install/parity/ten timings/reports; preserve8examples and voice. Pause automation only after bothS andM finished or proven technical refusal']
    if medium:
        ready=[]
        for run in (ROOT/'runs/yolo26m_v1').glob('yolo26m_seed*'):
            if (run/'export.json').is_file() and (run/'manifest.json').is_file() and read(run/'export.json')['status']=='passed' and read(run/'manifest.json')['status']=='completed':ready.append(run.name)
        state['branches']['medium_yolo']={'completed_exports':len(ready),'required':10,'runs':sorted(ready)}
        state.update(active_scope='yolo26m_v1',report='docs/experiments/yolo26m_v1/REPORT.md',eta_evidence=None)
    save(file,state);print(json.dumps({'status':state['status'],'worker_running':running,'wait_until':wait,'remaining':args.remaining}))
if __name__=='__main__':main()
