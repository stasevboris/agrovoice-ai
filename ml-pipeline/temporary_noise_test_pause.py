"""Briefly suspend this project's WSL study for the CPU fan noise check.

SIGCONT resumes the same processes, weights and random generator state.
An independent four-minute watchdog also resumes them after interruptions.
Only exact PID/start-time matches from this study are signalled.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone

STATE = Path('/mnt/f/agrovoice-ai/docs/environment/study_cpu_noise_pause_2026-10-03.json')

def process(pid):
    try:
        raw = Path(f'/proc/{pid}/stat').read_text().split(') ', 1)[1].split()
        cmd = Path(f'/proc/{pid}/cmdline').read_bytes().replace(b'\0', b' ').decode()
        return {'pid': int(pid), 'ppid': int(raw[1]), 'start_ticks': raw[19], 'command': cmd}
    except (OSError, ValueError):
        return None

def save(data):
    temporary = STATE.with_suffix('.tmp')
    temporary.write_text(json.dumps(data, indent=2), encoding='utf-8')
    temporary.replace(STATE)

def resume():
    data = json.loads(STATE.read_text(encoding='utf-8'))
    if data['status'] == 'resumed':
        return
    resumed = []
    for old in reversed(data['processes']):
        live = process(old['pid'])
        if live and live['start_ticks'] == old['start_ticks']:
            os.kill(old['pid'], signal.SIGCONT)
            resumed.append(old['pid'])
    data.update(status='resumed', resumed_utc=datetime.now(timezone.utc).isoformat(),
                resumed_pids=resumed, pause_seconds=round(time.time()-data['started_unix'], 1))
    save(data)

def pause():
    entries = [p for f in Path('/proc').iterdir() if f.name.isdigit() if (p := process(f.name))]
    queues = [p for p in entries if '/mnt/f/agrovoice-ai/ml-pipeline/run_study.py ' in p['command']]
    if len(queues) != 1:
        raise RuntimeError('Expected exactly one active AgroVoice study queue.')
    queue = queues[0]
    data = {'status': 'watchdog_started', 'started_unix': time.time(),
            'started_utc': datetime.now(timezone.utc).isoformat(), 'processes': [queue],
            'watchdog_seconds': 240, 'reason': 'User-authorized timed CPU fan noise test'}
    save(data)
    subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--watchdog'],
                     start_new_session=True, stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        os.kill(queue['pid'], signal.SIGSTOP)
        entries = [p for f in Path('/proc').iterdir() if f.name.isdigit() if (p := process(f.name))]
        selected = {queue['pid']}
        while True:
            more = {p['pid'] for p in entries if p['ppid'] in selected}
            if more <= selected:
                break
            selected |= more
        children = [p for p in entries if p['pid'] in selected and p['pid'] != queue['pid']]
        data['processes'] += children
        save(data)
        for p in children:
            os.kill(p['pid'], signal.SIGSTOP)
        data['status'] = 'paused'
        save(data)
    except BaseException:
        resume()
        raise

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--watchdog', action='store_true')
    args = parser.parse_args()
    if args.watchdog:
        time.sleep(240)
        resume()
    elif args.resume:
        resume()
    else:
        pause()
