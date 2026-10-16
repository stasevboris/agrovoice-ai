"""Run one predeclared classifier component without modifying its training recipe."""
import argparse
from pathlib import Path
from accuracy_study import DATA,REPORT,read,train

def main():
    p=argparse.ArgumentParser();p.add_argument('--kind',required=True);p.add_argument('--seed',type=int,required=True)
    p.add_argument('--stop-file',type=Path);args=p.parse_args()
    job=next(j for j in read(REPORT/'classification_plan.json')['jobs'] if j['kind']==args.kind and j['seed']==args.seed)
    result=train(job,read(DATA/'manifest.json'),args)
    print('COMPONENT_STATUS='+result,flush=True)
    if result=='waiting_dependency':raise RuntimeError('Required teacher/mask checkpoint unavailable')

if __name__=='__main__':main()
