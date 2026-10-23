"""Check data, pretrained features and runtime without fitting any weights."""
import argparse
import json
from pathlib import Path
import torch
from core import validate_partitions
from encoders import Crops, load_encoder


def check(args):
    torch.set_num_threads(2)
    if args.device == 'cuda' and not torch.cuda.is_available():
        raise RuntimeError('Requested GPU unavailable')
    rows = [json.loads(row) for row in (args.data / 'samples.jsonl').read_text(encoding='utf-8').splitlines()]
    validate_partitions(rows)
    report = json.loads((args.data / 'report.json').read_text(encoding='utf-8'))
    train_labels = {row['label'] for row in rows if row['partition'] == 'train'}
    if report['status'] != 'ready' or report['test_images_read'] or train_labels != set(range(len(report['classes']))):
        raise ValueError('Training data incomplete or contains test')
    weights = args.weights_dir / ('mobilenet_v3_small-047dcff4.pth' if args.encoder == 'mobilenet' else 'dinov2-small')
    encoder, dimensions = load_encoder(args.encoder, weights, args.device)
    dataset = Crops(rows[:2], args.data)
    images = torch.stack([dataset[index] for index in range(len(dataset))])
    with torch.inference_mode():
        features = encoder(images.to(args.device))
    if features.shape != (len(dataset), dimensions) or not torch.isfinite(features).all():
        raise RuntimeError('Feature inference failed')
    result = dict(encoder=args.encoder, device=args.device, torch=torch.__version__,
                  hip=torch.version.hip, dimensions=dimensions, checked_crop_images=len(dataset),
                  data_rows=len(rows), classes=len(report['classes']), data_partitions_valid=True,
                  feature_inference='passed', training_performed=False, optimizer_steps=0)
    print(json.dumps(result, indent=2))
    if args.report:
        args.report.write_text(json.dumps(result, indent=2), encoding='utf-8', newline='\n')
    return result


if __name__ == '__main__':
    kit = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--encoder', choices=['mobilenet', 'dinov2'], default='mobilenet')
    parser.add_argument('--data', type=Path, default=kit / 'data')
    parser.add_argument('--weights-dir', type=Path, default=kit / 'weights')
    parser.add_argument('--device', choices=['cpu', 'cuda'], default='cpu')
    parser.add_argument('--report', type=Path)
    check(parser.parse_args())
