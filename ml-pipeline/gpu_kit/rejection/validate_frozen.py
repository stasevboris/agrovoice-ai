"""One frozen final test: S ONNX -> actual image crops -> DINO/MLP, without threshold tuning."""
import argparse
from collections import defaultdict
from datetime import datetime
import io
import json
import math
from pathlib import Path
import sys
import time

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps
import torch
from core import execution_window
from encoders import load_encoder, MEAN, STD
from policies import frozen_evaluation, logit_scores
from train import digest, read_rows, supervise, write_json

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'alternatives'))
from compare_mobile_detectors import preprocess, decode, crop_predictions


def worker(args):
    torch.set_num_threads(4)
    if args.device == 'cuda' and not torch.cuda.is_available():
        raise RuntimeError('GPU unavailable')
    soft, _ = execution_window(args.deadline, home=bool(torch.version.hip) or args.device == 'cpu')

    def check_stop():
        if time.time() >= soft or (args.out / 'STOP').exists():
            raise InterruptedError('Validation deadline reached; partial results retained')

    selection = json.loads((args.study / 'frozen_selection.json').read_text(encoding='utf-8'))
    if selection['selected_name'] != 'dino_norm_mlp_crop_vs_reject':
        raise ValueError('This final evaluator accepts only the previously frozen DINO/MLP finalist')
    threshold = selection['policy']['threshold']
    state = torch.load(args.study / 'dino_norm_mlp/best.pt', map_location='cpu', weights_only=True)
    config = json.loads((args.study / 'config.json').read_text(encoding='utf-8'))
    if digest(args.detector) != config['original_detector_sha256']:
        raise ValueError('Frozen S differs from study detector')
    metadata = json.loads((args.kit / 'data/report.json').read_text(encoding='utf-8'))
    crops = metadata['classes']
    taxonomy = json.loads((args.root / 'ml-pipeline/allcrops/taxonomy_v1.json').read_text(encoding='utf-8'))
    class_crops = [row['crop'] for row in taxonomy['classes']]
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    detector = ort.InferenceSession(str(args.detector), sess_options=options, providers=['CPUExecutionProvider'])
    encoder, dimensions = load_encoder('dinov2', args.kit / 'weights/dinov2-small', args.device)
    head = torch.nn.Sequential(torch.nn.Linear(dimensions, 128), torch.nn.ReLU(),
                               torch.nn.Dropout(.1), torch.nn.Linear(128, 16)).to(args.device)
    head.load_state_dict(state['head'])
    head.eval()
    known = args.root / 'ml-pipeline/dataset_vision_training_v1'
    field = json.loads((known / 'annotations/test_field.json').read_text(encoding='utf-8'))
    ground_truth = defaultdict(list)
    for row in field['annotations']:
        x, y, width, height = row['bbox']
        ground_truth[row['image_id']].append(dict(crop=class_crops[row['category_id']], box=[x, y, x + width, y + height]))
    images = [('field', str(row['id']), known / row['file_name'], ground_truth[row['id']]) for row in field['images']]
    for name in ['dataset_nonplant_openimages_v2', 'dataset_unknown_beans_screened_v1', 'dataset_unknown_deepweeds_v1']:
        source = args.root / 'ml-pipeline' / name
        manifest = source / ('manifest.jsonl' if 'nonplant' in name else 'test.jsonl')
        images.extend((name, row['image'], source / row['image'], []) for row in read_rows(manifest) if row['split'] == 'test')
    # Selection and its hash are persisted before any image inference in test.
    write_json(args.out / 'frozen_policy.json', dict(selection=selection, selection_sha256=digest(args.study / 'frozen_selection.json'),
                                                   detector_sha256=digest(args.detector), head_sha256=digest(args.study / 'dino_norm_mlp/best.pt'),
                                                   planned_images=len(images), threshold_tuned=False))
    frames = []
    scores = {}
    started = time.monotonic()
    with (args.out / 'predictions.jsonl').open('w', encoding='utf-8') as log:
        for index, (source, identity, path, gt) in enumerate(images):
            check_stop()
            batch, geometry = preprocess(path)
            raw = detector.run(None, {detector.get_inputs()[0].name: batch})[0]
            predictions = crop_predictions(decode(raw, geometry), class_crops)
            tensors = []
            with Image.open(path) as original:
                original = original.convert('RGB')
                for prediction in predictions:
                    left, top, right, bottom = prediction['box']
                    image = original.crop((max(0, math.floor(left)), max(0, math.floor(top)),
                                           min(original.width, math.ceil(right)), min(original.height, math.ceil(bottom))))
                    image = ImageOps.pad(image, (224, 224), method=Image.Resampling.BILINEAR, color=(114,114,114))
                    buffer = io.BytesIO()
                    image.save(buffer, format='JPEG', quality=95)
                    buffer.seek(0)
                    with Image.open(buffer) as encoded:
                        values = np.asarray(encoded.convert('RGB'), dtype=np.float32).transpose(2,0,1).copy() / 255
                    tensors.append((torch.from_numpy(values) - MEAN) / STD)
            with torch.inference_mode():
                for offset in range(0, len(tensors), 16):
                    check_stop()
                    inputs = torch.stack(tensors[offset:offset + 16]).to(args.device)
                    features = torch.nn.functional.normalize(encoder(inputs), dim=1) * 10
                    logits = head(features).cpu().numpy()
                    detector_classes = [crops.index(pred['crop']) for pred in predictions[offset:offset + 16]]
                    values = logit_scores(logits, detector_classes, 'crop_vs_reject')
                    for position, value in enumerate(values, start=offset):
                        sample_id = f'{source}:{identity}:{position}'
                        predictions[position]['sample_id'] = sample_id
                        scores[sample_id] = float(value)
            frame = dict(source=source, id=identity, partition='audit', ground_truth=gt, predictions=predictions)
            frames.append(frame)
            log.write(json.dumps(dict(frame, gate_scores={pred['sample_id']: scores[pred['sample_id']] for pred in predictions})) + '\n')
            if index % 100 == 0:
                print(index + 1, '/', len(images), source, flush=True)
    result = frozen_evaluation(frames, scores, crops, threshold)
    result.update(test_evaluated=True, policy_frozen_before_test=True, ended=datetime.now().astimezone().isoformat(),
                  elapsed_seconds=time.monotonic() - started, detector_sha256=digest(args.detector),
                  selection_sha256=digest(args.study / 'frozen_selection.json'),
                  predictions_sha256=digest(args.out / 'predictions.jsonl'),
                  limitations=['Historical field test has known split-overlap limitations, not independent new field data.',
                               'Beans test is a crop seen during outlier exposure; DeepWeeds test contains held-out species.',
                               'Six nonplant images cannot establish a reliable production error rate.',
                               'Only crop/localization is compared; disease diagnosis remains unvalidated.',
                               'No threshold adjustment or further training on these test results.'])
    write_json(args.out / 'report.json', result)
    print('Frozen test complete', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--kit', type=Path, required=True)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--detector', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--deadline', required=True)
    parser.add_argument('--device', choices=['cpu','cuda'], default='cuda')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--worker', action='store_true')
    args = parser.parse_args()
    args.encoder = 'frozen_final_test'
    if args.resume:
        parser.error('Final test is a single fresh run; partial outputs retained')
    if args.worker:
        worker(args)
    else:
        raise SystemExit(supervise(args, entrypoint=Path(__file__).resolve()))
