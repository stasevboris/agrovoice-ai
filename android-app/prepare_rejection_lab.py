"""Build a private lab bundle from the fixed evaluated S + DINO experiment; no publication."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read(path: Path):
    return json.loads(path.read_text(encoding='utf-8'))


def prepare(detector_run: Path, study: Path, data: Path, out: Path) -> dict:
    detector = detector_run / 'weights/best.onnx'
    verifier = study / 'dino_verifier.onnx'
    entry = read(detector_run / 'android_manifest_entry.json')
    metadata = read(data / 'report.json')
    selection = read(study / 'frozen_selection.json')
    export = read(study / 'export_report.json')
    digest = sha256(detector)
    if digest != entry['sha256'] or digest != metadata['detector_sha256']:
        raise ValueError('Different detector: verifier belongs to the evaluated S only')
    if sha256(verifier) != export['sha256']:
        raise ValueError('Verifier SHA-256 mismatch')
    if (selection['selected_name'] != 'dino_norm_mlp_crop_vs_reject'
            or selection['selection_partition'] != 'cal' or selection['audit_read_for_selection']):
        raise ValueError('Requires the frozen calibration-selected DINO policy')
    threshold = selection['policy']['threshold']
    if not 0 <= threshold <= 1:
        raise ValueError('Invalid frozen threshold')
    entry.update(id='web_s_dino_reject_experimental', name='S + DINO: эксперимент отказа',
                 asset='models/experimental_web_s.onnx', taxonomy_version='taxonomy_v1', provisional=True)
    entry['verifier'] = dict(id='experimental_dino_reject', asset='models/experimental_dino.onnx',
        sha256=export['sha256'], side=224, crops=metadata['classes'], policy='crop_vs_reject_v1',
        threshold=threshold, detector_threshold=0.25, max_leaves=8, detector_sha256=digest,
        scope='Crop rejection only; disease diagnosis not validated')
    out.mkdir(parents=True, exist_ok=False)
    for source, relative in [(detector, entry['asset']), (verifier, entry['verifier']['asset'])]:
        target = out / relative
        target.parent.mkdir(exist_ok=True)
        shutil.copy2(source, target)
    (out / 'entry.json').write_text(json.dumps(entry, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    return entry


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--detector-run', type=Path, required=True)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    entry = prepare(args.detector_run, args.study, args.data, args.out)
    print(json.dumps(dict(bundle=str(args.out), model=entry['id']), ensure_ascii=False))


if __name__ == '__main__':
    main()
