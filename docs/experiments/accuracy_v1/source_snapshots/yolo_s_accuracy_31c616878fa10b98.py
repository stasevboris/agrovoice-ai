"""Use protected YOLO training/evaluation scripts unchanged for the larger control model."""
import argparse,subprocess,sys
from pathlib import Path
from accuracy_study import ROOT,REPORT,RUNS,checksum,read,save,stamp

def main():
    p=argparse.ArgumentParser();p.add_argument('--seed',type=int,required=True);p.add_argument('--stop-file',type=Path)
    args=p.parse_args();name=f'accuracy_v1_yolo26s_seed{args.seed}'
    source=ROOT/'runs/detect'/name;run=RUNS/f'yolo26s_seed{args.seed}';run.mkdir(parents=True,exist_ok=True)
    if (run/'export.json').exists() and read(run/'export.json')['status']=='passed':return
    protected={'train_clean.py':'b7bd5296722c991deab36ac2b2481113cdccc6d96333b3fcb7731efbc16e59a5',
               'evaluate_clean.py':'38706b2a05f9e83f9cbb75bc65f61a1893289208318d05ee77afbb6b949c36a7'}
    for file,digest in protected.items():assert checksum(ROOT/'ml-pipeline'/file)==digest,(file,'Protected source changed')
    old=read(source/'run_manifest.json') if (source/'run_manifest.json').exists() else {}
    if old.get('status')!='trained':
        command=[sys.executable,'-u',str(ROOT/'ml-pipeline/train_clean.py'),'--data',str(ROOT/'ml-pipeline/dataset_clean_v2/data.yaml'),
            '--project',str(ROOT/'runs/detect'),'--name',name,'--model',str(ROOT/'ml-pipeline/yolo26s.pt'),
            '--epochs','60','--patience','60','--imgsz','416','--batch','16','--seed',str(args.seed),'--no-export']
        checkpoint=source/'weights/last_complete.pt'
        if checkpoint.exists():command+=['--resume',str(checkpoint)]
        if args.stop_file:command+=['--stop-file',str(args.stop_file)]
        subprocess.run(command,check=True)
    if args.stop_file and args.stop_file.exists():return
    assert read(source/'run_manifest.json')['status']=='trained'
    # --no-export keeps training separate; the protected evaluator expects this file.
    # nms=None selects the ordinary multi-class head in this pinned exporter;
    # nms=False would select the YOLO26 one-to-one / TopK head instead.
    export_path=source/'weights/best.onnx'
    if not export_path.exists():
        from ultralytics import YOLO,settings
        settings.update({'sync':False})
        model=YOLO(str(source/'weights/best.pt'))
        model.model.end2end=False
        exported=Path(model.export(format='onnx',imgsz=416,dynamic=False,opset=17,device='cpu',nms=None))
        assert exported.resolve()==export_path.resolve()
    import onnxruntime as ort
    graph=ort.InferenceSession(str(export_path),providers=['CPUExecutionProvider'])
    assert graph.get_inputs()[0].shape==[1,3,416,416]
    assert graph.get_outputs()[0].shape==[1,34,3549],'Unexpected YOLO detection head'
    del graph
    subprocess.run([sys.executable,'-u',str(ROOT/'ml-pipeline/evaluate_clean.py'),'--run',str(source),'--device','0'],check=True)
    evaluation=read(source/'evaluation.json');export=source/(evaluation.get('onnx_path') or 'weights/best.onnx')
    # The protected evaluator writes its result only after three allclose checks pass.
    assert len(evaluation['onnx_comparison'])==3 and evaluation['completed_epochs']==60
    import shutil
    shutil.copyfile(export,run/'model.onnx')
    save(run/'manifest.json',{'status':'completed','kind':'yolo26s','seed':args.seed,'epochs':60,'batch':16,'input':416,
         'source_run':str(source),'ended_utc':stamp(),'best_validation_score':evaluation['validation']['overall']['metrics/mAP50-95(B)'],
         'source_manifest':read(source/'run_manifest.json'),'code_sha256':checksum(__file__)})
    save(run/'export.json',{'status':'passed','kind':'yolo26s','sha256':checksum(run/'model.onnx'),
         'bytes':(run/'model.onnx').stat().st_size,'input_shape':[1,3,416,416],'output_shape':[1,34,3549],
         'source_evaluation':str(source/'evaluation.json'),'scope':'YOLO val and desktop graph parity; shared end-to-end and Android pending'})

if __name__=='__main__':main()
