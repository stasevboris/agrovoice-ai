"""Compare actual Android tensor/ONNX output/boxes with CPU ORT and upstream NMS."""
from __future__ import annotations
import json
from pathlib import Path
import numpy as np
import onnxruntime as ort
import torch
from ultralytics.utils.nms import non_max_suppression

ROOT=Path(__file__).resolve().parents[1]
DIR=ROOT/'docs/android/phone_validation'

def main():
    torch.set_num_threads(1)
    rows=[]
    for model in ('yolo11n','yolo26n'):
        stem='0_'+model
        report=json.loads((DIR/(stem+'.json')).read_text(encoding='utf-8'))
        tensor=np.fromfile(DIR/(stem+'_input.bin'),dtype='<f4').reshape(1,3,416,416)
        android=np.fromfile(DIR/(stem+'_output.bin'),dtype='<f4').reshape(1,34,3549)
        options=ort.SessionOptions(); options.intra_op_num_threads=1; options.inter_op_num_threads=1
        session=ort.InferenceSession(str(ROOT/f'android-app/app/src/main/assets/models/{model}.onnx'),options,
                                    providers=['CPUExecutionProvider'])
        desktop=session.run(None,{session.get_inputs()[0].name:tensor})[0]
        difference=np.abs(android-desktop)
        raw_ok=bool(np.allclose(android,desktop,rtol=1e-4,atol=2e-3))
        # Feed the very same Android raw output to upstream NMS, then undo letterbox.
        prediction=torch.from_numpy(android.copy())
        boxes=non_max_suppression(prediction,conf_thres=.25,iou_thres=.7,multi_label=False,nc=30,end2end=False)[0].numpy()
        width,height=report['decoded_width'],report['decoded_height']
        scale=min(416/width,416/height)
        pad_x=(416-round(width*scale))//2; pad_y=(416-round(height*scale))//2
        boxes[:,[0,2]]=(boxes[:,[0,2]]-pad_x)/scale
        boxes[:,[1,3]]=(boxes[:,[1,3]]-pad_y)/scale
        boxes[:,[0,2]]=np.clip(boxes[:,[0,2]],0,width)
        boxes[:,[1,3]]=np.clip(boxes[:,[1,3]],0,height)
        expected=[r for r in boxes if r[2]>r[0] and r[3]>r[1]]
        actual=report['detections']; remaining=list(expected)
        errors=[]
        for box in actual:
            match=next((i for i,row in enumerate(remaining)
                        if int(row[5])==box['class_id'] and
                        np.allclose(row[:4],box['xyxy_pixels'],rtol=1e-5,atol=.002) and
                        abs(float(row[4])-box['confidence'])<1e-6),None)
            if match is None:
                raise AssertionError(('Android box differs from upstream NMS',model,box))
            errors.append(float(np.max(np.abs(remaining[match][:4]-box['xyxy_pixels']))))
            remaining.pop(match)
        assert not remaining, 'Android missed upstream boxes'
        row=dict(model=model,input_shape=list(tensor.shape),output_shape=list(android.shape),
                 cpu_ort_version=ort.__version__, raw_parity_passed=raw_ok,
                 raw_max_absolute_error=float(difference.max()),raw_mean_absolute_error=float(difference.mean()),
                 raw_tolerance={'rtol':1e-4,'atol':2e-3},boxes_matched=len(actual),
                 box_max_absolute_error_pixels=max(errors,default=0),postprocess_parity_passed=True)
        rows.append(row);print(json.dumps(row),flush=True)
        assert raw_ok
    (DIR/'desktop_parity.json').write_text(json.dumps(dict(status='passed',comparisons=rows,
        scope='Integration parity on one real photo per model, not accuracy or speed evaluation.'),indent=2),encoding='utf-8')

if __name__=='__main__':main()
