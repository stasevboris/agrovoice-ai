"""Run the packaged leaf detector offline on a photograph and save boxes plus JSON."""
from pathlib import Path
import argparse
import ast
import json

ROOT = Path(__file__).resolve().parents[1]


def main():
    import cv2
    import numpy as np
    import onnxruntime as ort
    from benchmark_onnx import postprocess
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('photo', type=Path)
    parser.add_argument('--package', type=Path, default=ROOT/'ml-pipeline/exports/plantdoc_clean_v1_yolo26n_416')
    parser.add_argument('--output', type=Path, default=ROOT/'ml-pipeline/.cache/predictions')
    parser.add_argument('--crop', choices=['all', 'potato', 'tomato', 'cucumber'], default='all')
    parser.add_argument('--confidence', type=float, default=0.25)
    args = parser.parse_args()
    if not 0 <= args.confidence <= 1:
        parser.error('--confidence must be between 0 and 1')
    photo = args.photo.resolve()
    image = cv2.imdecode(np.fromfile(photo, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise SystemExit(f'Cannot decode photograph: {photo}')
    args.output.mkdir(parents=True, exist_ok=True)
    result = dict(photo=str(photo), model=args.package.name, crop=args.crop,
                  confidence_threshold=args.confidence, detections=[],
                  annotation_target='whole_leaf_with_crop_and_condition',
                  note='Model suggestions require checking. Confidence is not disease probability or damage percentage.')
    package_classes = json.loads((args.package/'classes.json').read_text(encoding='utf-8'))
    if args.crop != 'all' and not any(n.lower().startswith(args.crop) for n in package_classes):
        result.update(status='unsupported_crop', reason='Cucumber classes are absent in this model.')
    else:
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        session = ort.InferenceSession(str(args.package/'model.onnx'), options, providers=['CPUExecutionProvider'])
        names = ast.literal_eval(session.get_modelmeta().custom_metadata_map['names'])
        classes = package_classes
        if list(names.values()) != classes:
            raise RuntimeError('Packaged class order differs from ONNX metadata.')
        input_info = session.get_inputs()[0]
        height, width = input_info.shape[-2:]
        scale = min(width/image.shape[1], height/image.shape[0])
        resized = cv2.resize(image, (round(image.shape[1]*scale), round(image.shape[0]*scale)))
        frame = np.full((height, width, 3), 114, dtype=np.uint8)
        y, x = (height-resized.shape[0])//2, (width-resized.shape[1])//2
        frame[y:y+resized.shape[0], x:x+resized.shape[1]] = resized
        batch = np.ascontiguousarray(frame.transpose(2, 0, 1)[::-1][None], dtype=np.float32)/255
        output = session.run(None, {input_info.name: batch})[0]
        detections = postprocess(output, confidence=args.confidence)
        for row in detections:
            class_id = int(row[5]); name = names[class_id]
            if args.crop != 'all' and not name.lower().startswith(args.crop):
                continue
            box = row[:4].copy()
            box[[0, 2]] = np.clip((box[[0, 2]]-x)/scale, 0, image.shape[1])
            box[[1, 3]] = np.clip((box[[1, 3]]-y)/scale, 0, image.shape[0])
            result['detections'].append(dict(class_id=class_id, name=name,
                                             confidence=float(row[4]), xyxy_pixels=box.tolist()))
            left, top, right, bottom = np.rint(box).astype(int)
            cv2.rectangle(image, (left, top), (right, bottom), (30, 190, 240), 2)
            cv2.putText(image, f'{name}: {row[4]:.2f}', (left, max(top-8, 16)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.45, (30, 190, 240), 1, cv2.LINE_AA)
        result['status'] = 'suggestions' if result['detections'] else 'no_detection_above_threshold'
        target_image = args.output/(photo.stem+'_prediction.jpg')
        cv2.imencode('.jpg', image)[1].tofile(target_image)
        result['annotated_image'] = str(target_image.resolve())
    target = args.output/(photo.stem+'_prediction.json')
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(report=str(target.resolve()), status=result['status'],
                          detections=len(result['detections'])), ensure_ascii=False))


if __name__ == '__main__':
    main()
