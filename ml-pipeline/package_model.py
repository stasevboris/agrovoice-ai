"""Bundle completed weights, class order and verification for Android integration."""
import hashlib
import argparse
import json
from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run', type=Path, default=root/'runs/detect/plantdoc_clean_v1_yolo26n_416')
parser.add_argument('--nms-free', action='store_true')
parser.add_argument('--imgsz', type=int)
args = parser.parse_args()
run = args.run.resolve()
manifest = json.loads((run/'run_manifest.json').read_text(encoding='utf-8'))
resolution_suffix = f'_{args.imgsz}' if args.imgsz and args.imgsz != manifest['image_size'] else ''
suffix = resolution_suffix+('_nms_free' if args.nms_free else '')
name = run.name+suffix
evaluation_path = run/f'evaluation{suffix}.json'
evaluation = json.loads(evaluation_path.read_text(encoding='utf-8'))
if manifest['status'] != 'trained' or evaluation['completed_epochs'] < 1:
    raise SystemExit('A completed and evaluated run is required.')
if manifest['dataset_fingerprint'] != evaluation['dataset_fingerprint']:
    raise SystemExit('Training and evaluation fingerprints disagree.')
data = root/'ml-pipeline'/manifest['dataset_name']
if json.loads((data/'report.json').read_text(encoding='utf-8'))['fingerprint'] != manifest['dataset_fingerprint']:
    raise SystemExit('Class source data differs from the trained dataset.')
onnx_path = run/(evaluation.get('onnx_path') or 'weights/best.onnx')
output = root/'ml-pipeline/exports'/name
output.mkdir(parents=True, exist_ok=True)
for source, target in [(run/'weights/best.pt', 'best.pt'), (onnx_path, 'model.onnx'),
                       (evaluation_path, 'evaluation.json'), (run/'run_manifest.json', 'run_manifest.json'),
                       (data/'classes.json', 'classes.json')]:
    shutil.copyfile(source, output/target)
classes = json.loads((data/'classes.json').read_text(encoding='utf-8'))
contract = dict(input='float32 RGB, NCHW, batch 1, letterbox padding 114, values /255',
                image_size=evaluation['image_size'], annotation_target=manifest['annotation_target'],
                mode='nms_free' if args.nms_free else 'external_nms',
                output='[1,300,6]: xyxy pixels, score, class ID; confidence filter' if args.nms_free
                else '[1,4+nc,anchors]: xywh pixels, class scores; class-aware NMS required',
                cucumber_status='Crop-only detection; condition unknown, no cucumber disease diagnosis.'
                if any(n.startswith('Cucumber') for n in classes)
                else 'Cucumber is absent. Do not reinterpret Squash as Cucumber.',
                limitations=['Confidence is not disease probability or percent of damaged tissue.',
                             'Whole leaf classes; no lesion segmentation.',
                             'Rare classes and field photos need additional evaluation.'])
(output/'inference_contract.json').write_text(json.dumps(contract, ensure_ascii=False, indent=2), encoding='utf-8')
checksums = {}
for path in sorted(output.iterdir()):
    if path.is_file() and path.name != 'sha256.json' and path.suffix in ('.pt', '.onnx', '.json'):
        with path.open('rb') as stream:
            checksums[path.name] = hashlib.file_digest(stream, 'sha256').hexdigest()
(output/'sha256.json').write_text(json.dumps(checksums, indent=2), encoding='utf-8')
print(output)
