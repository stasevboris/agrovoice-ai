"""Assemble a private rejection pilot kit with data and frozen pretrained weights."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def pack(data, destination):
    report = json.loads((data / 'report.json').read_text(encoding='utf-8'))
    if report['status'] != 'ready' or report['test_images_read']:
        raise ValueError('Prepared data without test are required')
    destination.mkdir(parents=True, exist_ok=True)
    name = f'agrovoice_rejection_{datetime.now():%Y%m%d}'
    target = destination / f'{name}.zip'
    scripts = ['core.py', 'encoders.py', 'evaluate.py', 'export_model.py', 'train.py',
               'check_environment.py', 'collect.py', 'requirements.txt',
               'setup_windows.ps1', 'setup_linux.sh', 'run_rx.sh']
    entries = [(HERE / source, source) for source in scripts]
    entries += [(HERE.parent / 'alternatives' / source, source)
                for source in ['compare_mobile_detectors.py', 'evaluate_home_pilot.py']]
    entries += [(ROOT / 'docs/guides/REJECTION_STAGE.md', 'START_HERE_RU.md'),
                (ROOT / 'LICENSE', 'LICENSE'), (ROOT / 'ATTRIBUTION.md', 'ATTRIBUTION.md')]
    entries += [(path, f'data/{path.relative_to(data).as_posix()}') for path in sorted(data.rglob('*')) if path.is_file()]
    weights = ROOT / 'ml-pipeline/.cache/rejection_weights'
    entries.append((weights / 'mobilenet_v3_small-047dcff4.pth', 'weights/mobilenet_v3_small-047dcff4.pth'))
    entries.append((weights / 'dinov2_source.json', 'weights/dinov2_source.json'))
    entries += [(path, f'weights/dinov2-small/{path.name}') for path in sorted((weights / 'dinov2-small').iterdir()) if path.is_file()]
    detector = ROOT / 'runs/home_20261023_collected/runs/home_20261023_allcrops_web_v1_yolo26s_416_s20261003/weights/best.onnx'
    entries.append((detector, 'weights/S_detector.onnx'))
    for path, relative in entries:
        if not path.is_file():
            raise FileNotFoundError(path)
    assets = dict(detector_sha256=report['detector_sha256'], training_performed=False,
                  dinov2=json.loads((weights / 'dinov2_source.json').read_text(encoding='utf-8')),
                  mobilenet_source='torchvision MobileNet_V3_Small_Weights.IMAGENET1K_V1',
                  mobilenet_url='https://download.pytorch.org/models/mobilenet_v3_small-047dcff4.pth',
                  note='Private research kit. Upstream ImageNet/data rights and attribution remain applicable.')
    with zipfile.ZipFile(target, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for index, (path, relative) in enumerate(entries):
            method = zipfile.ZIP_STORED if path.suffix in ['.jpg', '.pth', '.onnx', '.safetensors'] else zipfile.ZIP_DEFLATED
            archive.write(path, f'{name}/{relative}', compress_type=method)
            if index % 2000 == 0:
                print(index, '/', len(entries), flush=True)
        archive.writestr(f'{name}/assets.json', json.dumps(assets, indent=2))
    with zipfile.ZipFile(target) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('Kit CRC failed')
        count = len(archive.namelist())
    with target.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    result = dict(archive=str(target), bytes=target.stat().st_size, sha256=digest, crc_ok=True,
                  zip_entries=count, data=report, training_performed=False)
    target.with_suffix('.report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8', newline='\n')
    (destination / 'SHA256SUMS.txt').write_text(f'{digest}  {target.name}\n', encoding='ascii')
    (destination / 'START_HERE_RU.md').write_text((ROOT / 'docs/guides/REJECTION_STAGE.md').read_text(encoding='utf-8'),
                                               encoding='utf-8', newline='\n')
    print(json.dumps({key: result[key] for key in ['archive', 'bytes', 'sha256', 'crc_ok', 'zip_entries']}, indent=2))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=ROOT / 'ml-pipeline/dataset_rejection_v1')
    parser.add_argument('--out', type=Path, default=ROOT / 'artifacts/gpu_kit/rejection_20261023')
    args = parser.parse_args()
    pack(args.data, args.out)
