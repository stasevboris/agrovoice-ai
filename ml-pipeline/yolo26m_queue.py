"""Sequential M continuation only after a real pilot phone gate; S/old80 stay untouched."""
import argparse,os,subprocess,sys
from datetime import datetime,timezone
from yolo26m_validation import ROOT,FOCUS,RUNS,SEEDS,read,save,sha,protocol,base_examples,validate_seed
def stamp():return datetime.now(timezone.utc).isoformat()
def main():
    p=argparse.ArgumentParser();p.add_argument('--stop-file');args=p.parse_args()
    plan,lock=protocol();base_examples()
    gate=read(FOCUS/'phone_gate.json')
    assert gate['status']=='passed' and gate['model_sha256']==validate_seed(SEEDS[0])['graph_sha256'] and gate['plan_sha256']==lock['plan_sha256'],'Real pilot phone gate required'
    path=FOCUS/'queue_status.json'
    state=read(path) if path.exists() else {'jobs':{},'started_utc':stamp()}
    state.update(status='running',requested=10,pid=os.getpid(),plan_sha256=lock['plan_sha256'])
    save(path,state)
    for job in plan['jobs']:
        if args.stop_file and os.path.exists(args.stop_file):state.update(status='stopped');break
        run=RUNS/job['id'];state['current']=job
        try:
            complete=(run/'export.json').is_file() and (run/'manifest.json').is_file() and read(run/'export.json')['status']=='passed' and read(run/'manifest.json')['status']=='completed'
            if not complete:
                state['jobs'][job['id']]={'status':'running','updated_utc':stamp()};save(path,state)
                command=[sys.executable,'-u',str(ROOT/'ml-pipeline/yolo_m_accuracy.py'),'--seed',str(job['seed'])]
                if args.stop_file:command+=['--stop-file',args.stop_file]
                subprocess.run(command,check=True)
                if args.stop_file and os.path.exists(args.stop_file):state.update(status='stopped');break
            # Same worker, CPU evaluation after the GPU child exits; no second GPU load.
            subprocess.run([sys.executable,'-u',str(ROOT/'ml-pipeline/evaluate_yolo26m.py'),'--seeds',str(job['seed'])],check=True)
            row=validate_seed(job['seed'])
            state['jobs'][job['id']]={'status':'completed','reused':complete,'graph_sha256':row['graph_sha256'],'val_sha256':row['val_sha256'],'updated_utc':stamp()}
        except Exception as exc:
            state['jobs'][job['id']]={'status':'failed','error':repr(exc),'updated_utc':stamp()}
            state.update(status='failed',updated_utc=stamp());save(path,state);raise
        state['completed']=sum(x['status']=='completed' for x in state['jobs'].values());save(path,state)
    else:state.update(status='completed')
    state.update(completed=sum(x['status']=='completed' for x in state['jobs'].values()),updated_utc=stamp());save(path,state)
if __name__=='__main__':main()
