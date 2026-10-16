"""Serialize all five architecture directions, first completing one seed per direction."""
import argparse,os,subprocess,sys
from pathlib import Path
from accuracy_study import ROOT,REPORT,RUNS,DATA,checksum,read,save,stamp

def main():
    p=argparse.ArgumentParser();p.add_argument('--stop-file',type=Path);p.add_argument('--limit-jobs',type=int);args=p.parse_args()
    kinds=['mobilenet_v3_large','yolo26s','deimv2_n','efficientnet_b0','leaf_mask','teacher_efficientnet_v2_s',
           'masked_mobilenet_v3_large','distilled_mobilenet_v3_large']
    seeds=list(range(20261003,20261013))
    jobs=[{'id':f'{k}_seed{s}','kind':k,'seed':s} for s in seeds for k in kinds]
    plan={'jobs':jobs,'seeds':seeds,'data_sha256':checksum(DATA/'manifest.json'),
          'classifier_epochs':30,'mask_epochs':20,'detector_epochs':60,
          'order_reason':'One complete seed across families before repeat seeds, to detect export/phone issues early; recipes unchanged'}
    planfile=REPORT/'architecture_plan.json'
    if planfile.exists():assert read(planfile)==plan,'Use a new study version if the plan changes'
    else:save(planfile,plan)
    path=REPORT/'architecture_status.json';state=read(path) if path.exists() else {'jobs':{},'started_utc':stamp()}
    state.update(status='running',requested=len(jobs),pid=os.getpid());save(path,state);count=0
    for job in jobs:
        if args.stop_file and args.stop_file.exists():state['status']='stopped';break
        run=RUNS/job['id']
        if (run/'export.json').exists() and read(run/'export.json')['status']=='passed' and read(run/'manifest.json')['status']=='completed':
            state['jobs'][job['id']]={'status':'completed','updated_utc':stamp()};continue
        state['current']=job
        state['jobs'][job['id']]={'status':'running','updated_utc':stamp()}
        save(path,state)
        script='yolo_s_accuracy.py' if job['kind']=='yolo26s' else 'deim_accuracy.py' if job['kind']=='deimv2_n' else 'accuracy_component.py'
        command=[sys.executable,'-u',str(ROOT/'ml-pipeline'/script),'--seed',str(job['seed'])]
        if script=='accuracy_component.py':command+=['--kind',job['kind']]
        if args.stop_file:command+=['--stop-file',str(args.stop_file)]
        print('ARCHITECTURE_JOB '+job['id'],flush=True)
        try:
            subprocess.run(command,check=True)
            if args.stop_file and args.stop_file.exists():state['status']='stopped';break
            assert (run/'export.json').exists() and read(run/'export.json')['status']=='passed','Export missing'
            state['jobs'][job['id']]={'status':'completed','updated_utc':stamp()}
        except Exception as exc:
            state['status']='failed';state['jobs'][job['id']]={'status':'failed','error':repr(exc),'updated_utc':stamp()};save(path,state);raise
        count+=1;state['completed']=sum(v['status']=='completed' for v in state['jobs'].values());save(path,state)
        if args.limit_jobs and count>=args.limit_jobs:state['status']='batch_finished';break
    else:state['status']='completed'
    state['completed']=sum(v['status']=='completed' for v in state['jobs'].values());state['updated_utc']=stamp();save(path,state)

if __name__=='__main__':main()
