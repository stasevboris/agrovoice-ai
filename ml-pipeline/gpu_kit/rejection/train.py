"""Frozen-encoder rejection pilot. Training starts only through an explicit CLI invocation."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

import numpy as np
from core import execution_window, labelled_indices, validate_partitions


def write_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8', newline='\n')
    temporary.replace(path)


def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def supervise(args, entrypoint=None):
    # Detect ROCm before starting a child; CPU on the home PC uses the same day limit.
    import torch
    home = bool(torch.version.hip) or args.device == 'cpu'
    _, hard = execution_window(args.deadline, home=home)
    if not args.resume:
        args.out.mkdir(parents=True, exist_ok=False)
    elif not args.out.is_dir():
        raise ValueError('Resume requires an existing output directory')
    lock = args.out / 'run.lock'
    with lock.open('x') as stream:
        stream.write(str(os.getpid()))
    command = [sys.executable, '-u', str(entrypoint or Path(__file__).resolve()), *sys.argv[1:], '--worker']
    status = dict(status='running', deadline=args.deadline, automatic_restart=False,
                  encoder=args.encoder, source='explicit manual CLI start')
    write_json(args.out / 'status.json', status)
    process = None
    code = 1
    try:
        with (args.out / 'training.log').open('a', encoding='utf-8') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=os.name != 'nt')
            print(f'Log: {args.out / "training.log"}', flush=True)
            code = process.wait(timeout=max(1, hard - time.time()))
    except (KeyboardInterrupt, subprocess.TimeoutExpired):
        status['deadline_or_interrupt'] = True
    finally:
        if process is not None:
            if os.name == 'nt':
                if process.poll() is None:
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True)
            else:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                    time.sleep(2)
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            code = process.wait()
        report = args.out / 'report.json'
        complete = code == 0 and report.exists()
        status.update(status='finished' if complete else 'stopped_or_failed', returncode=code)
        write_json(args.out / 'status.json', status)
        lock.unlink(missing_ok=True)
    return code


def classification_metrics(labels, predictions, classes):
    matrix = np.zeros((classes, classes), dtype=int)
    np.add.at(matrix, (labels, predictions), 1)
    support = matrix.sum(1)
    f1 = 2 * matrix.diagonal() / np.maximum(support + matrix.sum(0), 1)
    return dict(accuracy=float(matrix.diagonal().sum() / max(1, len(labels))),
                macro_f1=float(f1[support > 0].mean()), support=support.tolist())


def worker(args):
    import torch
    from encoders import extract_features, load_encoder
    from evaluate import evaluate
    torch.set_num_threads(args.threads)
    torch.manual_seed(args.seed)
    if args.device == 'cuda' and not torch.cuda.is_available():
        raise RuntimeError('Requested GPU is unavailable; no silent CPU fallback')
    soft, _ = execution_window(args.deadline, home=bool(torch.version.hip) or args.device == 'cpu')

    def check_stop():
        if time.time() >= soft or (args.out / 'STOP').exists():
            raise InterruptedError('STOP/deadline: last completed head epoch retained')

    metadata = json.loads((args.data / 'report.json').read_text(encoding='utf-8'))
    if metadata['status'] != 'ready' or metadata['test_images_read']:
        raise ValueError('Expected ready training/calibration/audit data without test')
    rows = read_rows(args.data / 'samples.jsonl')
    validate_partitions(rows)
    fingerprint = digest(args.data / 'samples.jsonl')
    frames_sha = digest(args.data / 'frames.jsonl')
    weights_file = args.weights / 'model.safetensors' if args.weights.is_dir() else args.weights
    weight_sha = digest(weights_file)
    encoder, dimensions = load_encoder(args.encoder, args.weights, args.device)
    feature_path = args.out / 'features.pt'
    cache = None
    if args.resume and feature_path.exists():
        cache = torch.load(feature_path, map_location='cpu', weights_only=True)
        if cache['data_sha256'] != fingerprint or cache['weights_sha256'] != weight_sha:
            raise ValueError('Cached features do not match data/backbone')
    if cache is None:
        features = extract_features(encoder, rows, args.data, args.device, args.batch, args.workers, check_stop)
        cache = dict(features=features, data_sha256=fingerprint, weights_sha256=weight_sha)
        torch.save(cache, args.out / 'features.tmp')
        (args.out / 'features.tmp').replace(feature_path)
    features = cache['features']
    labels = torch.tensor([row['label'] if row['label'] is not None else -100 for row in rows])
    train_indices = labelled_indices(rows, 'train')
    cal_indices = labelled_indices(rows, 'cal')
    if not train_indices or not cal_indices:
        raise ValueError('Train and labelled calibration are required')
    classes = metadata['classes']
    head = torch.nn.Linear(dimensions, len(classes)).to(args.device)
    optimizer = torch.optim.AdamW(head.parameters(), lr=args.lr)
    start = 0
    best = -1.0
    history = []
    if args.resume and (args.out / 'last.pt').exists():
        state = torch.load(args.out / 'last.pt', map_location='cpu', weights_only=True)
        if (state['data_sha256'] != fingerprint or state['frames_sha256'] != frames_sha
                or state['weights_sha256'] != weight_sha or state['encoder'] != args.encoder):
            raise ValueError('Checkpoint does not match data/backbone')
        head.load_state_dict(state['head'])
        optimizer.load_state_dict(state['optimizer'])
        start = state['epoch']
        best = state['best_macro_f1']
        history = state['history']
    counts = torch.bincount(labels[train_indices], minlength=len(classes)).float()
    if (counts == 0).any():
        raise ValueError('Every verifier label must occur in train')
    weights = counts.rsqrt().to(args.device)
    loader = torch.utils.data.DataLoader(torch.utils.data.TensorDataset(features[train_indices], labels[train_indices]),
                                        batch_size=256, shuffle=True,
                                        generator=torch.Generator().manual_seed(args.seed + start))
    config = dict(encoder=args.encoder, weights_sha256=weight_sha, data_sha256=fingerprint, frames_sha256=frames_sha,
                  classes=classes, device=args.device, torch=torch.__version__,
                  gpu=torch.cuda.get_device_name(0) if args.device == 'cuda' else None,
                  seed=args.seed, epochs=args.epochs, lr=args.lr, deadline=args.deadline,
                  training_indices=len(train_indices), calibration_indices=len(cal_indices),
                  audit_used_for_selection=False, backbone_frozen=True)
    write_json(args.out / 'config.json', config)
    for epoch in range(start, args.epochs):
        check_stop()
        head.train()
        for inputs, targets in loader:
            check_stop()
            optimizer.zero_grad()
            logits = head(inputs.to(args.device))
            loss = torch.nn.functional.cross_entropy(logits, targets.to(args.device), weight=weights)
            if not torch.isfinite(loss):
                raise RuntimeError('Non-finite head loss')
            loss.backward()
            optimizer.step()
        head.eval()
        with torch.inference_mode():
            predicted = head(features[cal_indices].to(args.device)).argmax(1).cpu().numpy()
        measured = classification_metrics(labels[cal_indices].numpy(), predicted, len(classes))
        improved = measured['macro_f1'] > best
        best = max(best, measured['macro_f1'])
        history.append(dict(epoch=epoch + 1, loss_last_batch=float(loss.detach().cpu()), calibration=measured))
        state = dict(epoch=epoch + 1, head={key: value.detach().cpu().clone() for key, value in head.state_dict().items()},
                     optimizer=optimizer.state_dict(), best_macro_f1=best,
                     data_sha256=fingerprint, frames_sha256=frames_sha, weights_sha256=weight_sha,
                     encoder=args.encoder, history=history)
        torch.save(state, args.out / 'last.tmp')
        (args.out / 'last.tmp').replace(args.out / 'last.pt')
        if improved:
            torch.save(state, args.out / 'best.tmp')
            (args.out / 'best.tmp').replace(args.out / 'best.pt')
        write_json(args.out / 'history.json', history)
        print(f'epoch {epoch + 1}: calibration macro-F1 {measured["macro_f1"]:.4f}', flush=True)
    check_stop()
    state = torch.load(args.out / 'best.pt', map_location='cpu', weights_only=True)
    head.load_state_dict(state['head'])
    head.eval()
    probabilities = []
    with torch.inference_mode():
        for offset in range(0, len(features), 256):
            check_stop()
            probabilities.append(torch.softmax(head(features[offset:offset + 256].to(args.device)), 1).cpu().numpy())
    probabilities = np.concatenate(probabilities)
    np.save(args.out / 'probabilities.npy', probabilities)
    result = evaluate(read_rows(args.data / 'frames.jsonl'), rows, probabilities, classes, args.min_retention)
    result.update(config=config, completed_epochs=len(history), best_epoch=state['epoch'], training_performed=True)
    if args.encoder == 'mobilenet':
        from export_model import export_mobile
        check_stop()
        result['export'] = export_mobile(encoder, head, args.out / 'verifier.onnx', args.threads)
    else:
        result['export'] = None
        result['limitations'].append('DINOv2 is a representation comparison; no Android export or latency is claimed.')
    write_json(args.out / 'report.json', result)
    print(result['policy_status'], flush=True)


def main():
    kit = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--encoder', choices=['mobilenet', 'dinov2'], default='mobilenet')
    parser.add_argument('--data', type=Path, default=kit / 'data')
    parser.add_argument('--weights', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--device', choices=['cpu', 'cuda'], default='cuda')
    parser.add_argument('--deadline', required=True)
    parser.add_argument('--epochs', type=int, default=20)
    parser.add_argument('--batch', type=int, default=32)
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--lr', type=float, default=.001)
    parser.add_argument('--seed', type=int, default=20261023)
    parser.add_argument('--min-retention', type=float, default=.95)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    args.weights = args.weights or kit / 'weights' / ('mobilenet_v3_small-047dcff4.pth' if args.encoder == 'mobilenet' else 'dinov2-small')
    if args.epochs < 1 or args.batch < 1 or args.workers < 0 or not 0 < args.min_retention <= 1:
        parser.error('Invalid training settings')
    if args.worker:
        worker(args)
        return 0
    return supervise(args)


if __name__ == '__main__':
    raise SystemExit(main())
