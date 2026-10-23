"""Collect stopped verifier runs, retaining head optimizer and feature caches."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import zipfile


def collect(root, destination):
    if list((root / 'runs').rglob('run.lock')):
        raise RuntimeError('A run lock exists; finish training before collecting checkpoints')
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / f'rejection_results_{datetime.now():%Y%m%d_%H%M%S_%f}.zip'
    files = [path for path in (root / 'runs').rglob('*') if path.is_file() and path.suffix != '.tmp']
    files += [root / 'data/report.json']
    for name in ['START_HERE_RU.md', 'assets.json', 'environment.json']:
        if (root / name).exists():
            files.append(root / name)
    with zipfile.ZipFile(target, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for path in files:
            archive.write(path, path.relative_to(root).as_posix())
    with zipfile.ZipFile(target) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('Result archive CRC failed')
    with target.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    report = dict(archive=str(target), bytes=target.stat().st_size, sha256=digest, crc_ok=True, files=len(files))
    target.with_suffix('.report.json').write_text(json.dumps(report, indent=2), encoding='utf-8', newline='\n')
    print(json.dumps(report, indent=2))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    collect(args.root, args.out or args.root)
