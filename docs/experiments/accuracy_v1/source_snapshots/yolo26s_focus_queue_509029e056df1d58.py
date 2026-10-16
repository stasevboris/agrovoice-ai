"""User-selected ten-seed YOLO26s continuation; original 80-job plan stays intact."""
import argparse, os, subprocess, sys
from pathlib import Path
from accuracy_study import ROOT, RUNS, DATA, checksum, read, save, stamp

FOCUS = ROOT / 'docs/experiments/yolo26s_focus_v1'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--stop-file', type=Path)
    args = parser.parse_args()
    plan = read(FOCUS / 'plan.json')
    seeds = list(range(20261003, 20261013))
    expected = [{'id': f'yolo26s_seed{s}', 'kind': 'yolo26s', 'seed': s} for s in seeds]
    assert plan['jobs'] == expected and plan['data_sha256'] == checksum(DATA / 'manifest.json')
    statefile = FOCUS / 'status.json'
    state = read(statefile) if statefile.exists() else {'jobs': {}, 'started_utc': stamp()}
    state.update(status='running', requested=10, pid=os.getpid(), plan_sha256=checksum(FOCUS / 'plan.json'))
    save(statefile, state)
    for job in expected:
        if args.stop_file and args.stop_file.exists():
            state['status'] = 'stopped'
            break
        run = RUNS / job['id']
        if (run / 'export.json').exists() and (run / 'manifest.json').exists():
            info, export = read(run / 'manifest.json'), read(run / 'export.json')
            if info['status'] == 'completed' and export['status'] == 'passed':
                assert checksum(run / 'model.onnx') == export['sha256']
                state['jobs'][job['id']] = {'status': 'completed', 'updated_utc': stamp(), 'reused': True}
                state['completed'] = sum(v['status'] == 'completed' for v in state['jobs'].values())
                save(statefile, state)
                continue
        state['current'] = job
        state['jobs'][job['id']] = {'status': 'running', 'updated_utc': stamp()}
        save(statefile, state)
        command = [sys.executable, '-u', str(ROOT / 'ml-pipeline/yolo_s_accuracy.py'), '--seed', str(job['seed'])]
        if args.stop_file:
            command += ['--stop-file', str(args.stop_file)]
        print('YOLO26S_FOCUS_JOB ' + job['id'], flush=True)
        try:
            subprocess.run(command, check=True)
            if args.stop_file and args.stop_file.exists():
                state['status'] = 'stopped'
                break
            export, info = read(run / 'export.json'), read(run / 'manifest.json')
            assert info['status'] == 'completed' and export['status'] == 'passed'
            assert checksum(run / 'model.onnx') == export['sha256']
            state['jobs'][job['id']] = {'status': 'completed', 'updated_utc': stamp()}
        except Exception as exc:
            state['status'] = 'failed'
            state['jobs'][job['id']] = {'status': 'failed', 'error': repr(exc), 'updated_utc': stamp()}
            save(statefile, state)
            raise
        state['completed'] = sum(v['status'] == 'completed' for v in state['jobs'].values())
        save(statefile, state)
    else:
        state['status'] = 'completed'
    state['completed'] = sum(v['status'] == 'completed' for v in state['jobs'].values())
    state['updated_utc'] = stamp()
    save(statefile, state)

if __name__ == '__main__':
    main()
