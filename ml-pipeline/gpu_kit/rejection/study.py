"""Bounded calibration-only study; audit is evaluated once for the selected finalist."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import time

import numpy as np
import torch
from core import execution_window, labelled_indices, validate_partitions
from evaluate import score
from policies import calibrate, logit_scores, neighbor_scores, select_point, unknown_macro
from train import classification_metrics, digest, read_rows, supervise, write_json

RECIPES = [
    dict(name='dino_norm_linear', encoder='dinov2', hidden=0, binary=False, lr=.01),
    dict(name='dino_norm_mlp', encoder='dinov2', hidden=128, binary=False, lr=.003),
    dict(name='mobile_norm_mlp', encoder='mobilenet', hidden=128, binary=False, lr=.003),
    dict(name='dino_binary_mlp', encoder='dinov2', hidden=128, binary=True, lr=.003),
]


def train_head(recipe, features, rows, data_sha, frames_sha, source, out, args, check_stop):
    torch.manual_seed(20261024)
    features = torch.nn.functional.normalize(features.float(), dim=1) * 10
    targets = torch.tensor([row['label'] if row['label'] is not None else -100 for row in rows])
    if recipe['binary']:
        targets = torch.where(targets >= 0, (targets != 15).long(), targets)
    training = labelled_indices(rows, 'train')
    calibration = labelled_indices(rows, 'cal')
    classes = 2 if recipe['binary'] else 16
    head = torch.nn.Linear(features.shape[1], classes)
    if recipe['hidden']:
        head = torch.nn.Sequential(torch.nn.Linear(features.shape[1], recipe['hidden']), torch.nn.ReLU(),
                                   torch.nn.Dropout(.1), torch.nn.Linear(recipe['hidden'], classes))
    head = head.to(args.device)
    optimizer = torch.optim.AdamW(head.parameters(), lr=recipe['lr'])
    counts = torch.bincount(targets[training], minlength=classes).float()
    if (counts == 0).any():
        raise ValueError('Training label missing')
    class_weights = counts.rsqrt().to(args.device)
    example_weights = torch.tensor([2. if row['domain'] == 'field' and row['label'] not in [None, 15] else 1.
                                    for row in rows])
    loader = torch.utils.data.DataLoader(torch.utils.data.TensorDataset(features[training], targets[training], example_weights[training]),
                                        batch_size=256, shuffle=True,
                                        generator=torch.Generator().manual_seed(20261024))
    history = []
    best = -1.
    for epoch in range(40):
        check_stop()
        head.train()
        for inputs, labels, weights in loader:
            check_stop()
            optimizer.zero_grad()
            losses = torch.nn.functional.cross_entropy(head(inputs.to(args.device)), labels.to(args.device),
                                                       weight=class_weights, reduction='none')
            loss = (losses * weights.to(args.device)).mean()
            if not torch.isfinite(loss):
                raise RuntimeError('Non-finite head training loss')
            loss.backward()
            optimizer.step()
        head.eval()
        with torch.inference_mode():
            predicted = head(features[calibration].to(args.device)).argmax(1).cpu().numpy()
        measured = classification_metrics(targets[calibration].numpy(), predicted, classes)
        improved = measured['macro_f1'] > best
        best = max(best, measured['macro_f1'])
        history.append(dict(epoch=epoch + 1, calibration=measured, last_loss=float(loss.detach().cpu())))
        checkpoint = dict(recipe=recipe, epoch=epoch + 1, history=history, data_sha256=data_sha,
                          frames_sha256=frames_sha, features_sha256=digest(source / 'features.pt'),
                          head={name: value.detach().cpu().clone() for name, value in head.state_dict().items()},
                          optimizer=optimizer.state_dict(), normalized_feature_scale=10., best_macro_f1=best)
        torch.save(checkpoint, out / 'last.tmp')
        (out / 'last.tmp').replace(out / 'last.pt')
        if improved:
            torch.save(checkpoint, out / 'best.pt')
        if (epoch + 1) % 10 == 0:
            print(recipe['name'], epoch + 1, measured['macro_f1'], flush=True)
    write_json(out / 'history.json', history)
    state = torch.load(out / 'best.pt', map_location='cpu', weights_only=True)
    head.load_state_dict(state['head'])
    head.eval()
    outputs = []
    with torch.inference_mode():
        for offset in range(0, len(features), 256):
            check_stop()
            outputs.append(head(features[offset:offset + 256].to(args.device)).cpu().numpy())
    logits = np.concatenate(outputs)
    np.save(out / 'logits.npy', logits)
    write_json(out / 'config.json', dict(recipe=recipe, best_epoch=state['epoch'], completed_epochs=40,
                                        data_sha256=data_sha, frames_sha256=frames_sha, seed=20261024,
                                        audit_used_for_selection=False))
    return logits


def worker(args):
    torch.set_num_threads(4)
    if args.device == 'cuda' and not torch.cuda.is_available():
        raise RuntimeError('No working GPU; no silent fallback')
    soft, _ = execution_window(args.deadline, home=bool(torch.version.hip) or args.device == 'cpu')

    def check_stop():
        if time.time() >= soft or (args.out / 'STOP').exists():
            raise InterruptedError('STOP/deadline reached; completed checkpoints retained')

    rows = read_rows(args.data / 'samples.jsonl')
    frames = read_rows(args.data / 'frames.jsonl')
    metadata = json.loads((args.data / 'report.json').read_text(encoding='utf-8'))
    validate_partitions(rows)
    data_sha, frames_sha = digest(args.data / 'samples.jsonl'), digest(args.data / 'frames.jsonl')
    crops = metadata['classes']
    lookup = {row['id']: index for index, row in enumerate(rows)}
    proposals = {prediction['sample_id']: crops.index(prediction['crop'])
                 for frame in frames for prediction in frame['predictions']}
    identities = sorted(proposals)
    indices = [lookup[identity] for identity in identities]
    detector_classes = [proposals[identity] for identity in identities]
    candidates = []
    scores_by_candidate = {}
    config = dict(deadline=args.deadline, device=args.device, torch=str(torch.__version__),
                  gpu=torch.cuda.get_device_name(0) if args.device == 'cuda' else None,
                  data_sha256=data_sha, frames_sha256=frames_sha, seed=20261024,
                  recipes=RECIPES, audit_used_for_selection=False,
                  original_detector_sha256=metadata['detector_sha256'])
    write_json(args.out / 'config.json', config)

    def register(name, values):
        check_stop()
        scores = dict(zip(identities, map(float, values)))
        calibration = calibrate(frames, scores, crops)
        candidate = dict(name=name, **calibration)
        candidates.append(candidate)
        scores_by_candidate[name] = scores
        write_json(args.out / 'calibration_results.json', candidates)
        print(name, 'feasible' if calibration['policy'] else 'no_feasible', flush=True)

    all_features = {}
    for encoder in ['dinov2', 'mobilenet']:
        source = args.previous / f'{encoder}_01'
        features = torch.load(source / 'features.pt', map_location='cpu', weights_only=True)
        state = torch.load(source / 'best.pt', map_location='cpu', weights_only=True)
        if features['data_sha256'] != data_sha or state['frames_sha256'] != frames_sha:
            raise ValueError('Previous feature cache does not match current data')
        values = features['features']
        all_features[encoder] = values
        logits = values[indices].numpy() @ state['head']['weight'].numpy().T + state['head']['bias'].numpy()
        for mode in ['knownness', 'crop_vs_reject']:
            register(f'previous_{encoder}_{mode}', logit_scores(logits, detector_classes, mode))
        if encoder == 'dinov2':
            training = labelled_indices(rows, 'train')
            known = [index for index in training if rows[index]['label'] != 15]
            outlier = [index for index in training if rows[index]['label'] == 15]
            register('dino_knn_known_k10', neighbor_scores(values[indices], values[known], k=10,
                                                          device=args.device, check_stop=check_stop))
            register('dino_knn_margin_k5', neighbor_scores(values[indices], values[known], values[outlier], k=5,
                                                          device=args.device, check_stop=check_stop))
    for recipe in RECIPES:
        check_stop()
        out = args.out / recipe['name']
        out.mkdir(exist_ok=False)
        source = args.previous / f"{recipe['encoder']}_01"
        logits = train_head(recipe, all_features[recipe['encoder']], rows, data_sha, frames_sha, source, out, args, check_stop)
        if recipe['binary']:
            binary_logits = logits[indices]
            shifted = binary_logits - binary_logits.max(1, keepdims=True)
            exponent = np.exp(shifted)
            register(recipe['name'] + '_knownness', exponent[:, 1] / exponent.sum(1))
        else:
            for mode in ['knownness', 'crop_vs_reject']:
                register(recipe['name'] + '_' + mode, logit_scores(logits[indices], detector_classes, mode))
    feasible = [candidate for candidate in candidates if candidate['policy'] is not None]
    selected = min(feasible, key=lambda candidate: (unknown_macro(candidate['policy']['metrics']),
                                                    candidate['policy']['metrics']['field']['fp'],
                                                    -candidate['policy']['metrics']['field']['tp'], candidate['name'])) if feasible else None
    selection = dict(selected_name=selected['name'] if selected else None,
                     policy=selected['policy'] if selected else None,
                     selection_partition='cal', audit_read_for_selection=False)
    write_json(args.out / 'frozen_selection.json', selection)
    # One audit evaluation after the chosen name and threshold are recorded.
    baseline_audit = score(frames, {}, None, 'audit', crops)
    audit = None
    status = 'no_feasible_policy'
    if selected:
        policy = selected['policy']
        audit = score(frames, scores_by_candidate[selected['name']], policy['threshold'], 'audit', crops)
        verdict = select_point([dict(threshold=policy['threshold'], metrics=audit)], baseline_audit)
        status = 'audit_candidate' if verdict else 'audit_not_improved'
        write_json(args.out / 'selected_scores.json', scores_by_candidate[selected['name']])
    report = dict(status=status, selected=selection, calibration_candidates=candidates,
                  audit_baseline=baseline_audit, audit_gated=audit,
                  completed_recipes=len(RECIPES), test_evaluated=False, apk_changed=False,
                  config=config, ended=datetime.now().astimezone().isoformat(),
                  limitations=['Historical field validation and frozen S selection bias remain.',
                               'Scene-level unknown labels and tiny nonplant set are not expert diagnoses.',
                               'Only crop/localization refusal, not disease accuracy.',
                               'Multiple calibration comparisons can overfit; audit used once for the selected finalist.',
                               'No Android integration/export of DINO; no practical phone-performance claim.'])
    write_json(args.out / 'report.json', report)
    print(status, selection['selected_name'], flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--previous', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--device', choices=['cpu', 'cuda'], default='cuda')
    parser.add_argument('--deadline', required=True)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--worker', action='store_true')
    args = parser.parse_args()
    args.encoder = 'limited_rejection_study'
    if args.resume:
        parser.error('Choose a fresh study output; previous completed candidates are not overwritten')
    if args.worker:
        worker(args)
    else:
        raise SystemExit(supervise(args, entrypoint=Path(__file__).resolve()))
