"""Verify Android outputs against desktop ORT using the actual Android input tensors."""
import argparse,hashlib,json,zipfile
from pathlib import Path
import numpy as np,onnxruntime as ort

ROOT=Path(__file__).resolve().parents[1]
FOLDER=ROOT/'docs/android/accuracy_v1/field_validation'
ASSETS=ROOT/'android-app/app/src/main/assets'

def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def sha(p):
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--apk',type=Path);args=parser.parse_args()
    manifest=read(ASSETS/'models/manifest.json');summary=read(FOLDER/'summary.json')
    assert summary['status']=='complete'
    expected_ids={m['id'] for m in manifest['models']};results=summary['results']
    photos={r['validation_photo'] for r in results}
    assert len(results)==len(expected_ids)*len(photos)
    comparisons=[]
    for m in manifest['models']:
        choices=[('detector',m,416)]+([('classifier',m['classifier'],224)] if 'classifier' in m else [])
        for stage,info,side in choices:
            stem=f'0_{m["id"]}_{stage}';file=ASSETS/info['asset'];assert sha(file)==info['sha256']
            shape=read(FOLDER/f'{stem}_shape.json')
            x=np.fromfile(FOLDER/f'{stem}_input.bin',dtype='<f4').reshape(1,3,side,side)
            expected=np.fromfile(FOLDER/f'{stem}_output.bin',dtype='<f4').reshape(shape)
            assert np.isfinite(x).all() and x.min()>=0 and x.max()<=1
            options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
            session=ort.InferenceSession(str(file),sess_options=options,providers=['CPUExecutionProvider'])
            actual=session.run(None,{session.get_inputs()[0].name:x})[0]
            if stage=='detector' and m.get('family')=='detr':
                # DETR returns an unordered detection set; TopK ties can reorder rows.
                unused=set(range(actual.shape[1]));aligned=[]
                for row in expected[0]:
                    matches=[i for i in unused if actual[0,i,5]==row[5] and np.allclose(actual[0,i,:5],row[:5],rtol=1e-4,atol=.003)]
                    assert matches,('Android DETR detection missing',row.tolist())
                    closest=min(matches,key=lambda i:float(np.abs(actual[0,i,:5]-row[:5]).max()))
                    aligned.append(actual[0,closest]);unused.remove(closest)
                actual=np.asarray(aligned)[None]
            assert np.allclose(expected,actual,rtol=1e-4,atol=.003 if m.get('family')=='detr' else .002),(m['id'],stage,float(np.abs(expected-actual).max()))
            comparisons.append({'mode':m['id'],'stage':stage,'graph_sha256':info['sha256'],'output_shape':shape,
                                'input_sha256':sha(FOLDER/f'{stem}_input.bin'),'max_absolute_error':float(np.abs(expected-actual).max())})
    for r in results:
        model=next(m for m in manifest['models'] if m['id']==r['model'])
        assert r['model_sha256']==model['sha256'] and r['seed']==model['seed'] and r['detector_seed']==model['detector_seed']
        assert all(0<=b['class_id']<len(manifest['classes']) for b in r['detections'])
        assert r['skipped_leaves']==max(0,r['proposed_leaves']-r['classified_leaves'])
    pointer=ROOT/'artifacts/apk/current_model_lab.json'
    apk=args.apk or (ROOT/read(pointer)['apk'] if pointer.exists() else ROOT/'artifacts/apk/AgroVoice-model-lab-0.3.apk')
    apk=apk.resolve();apk.relative_to(ROOT)
    with zipfile.ZipFile(apk) as z:
        packaged=json.loads(z.read('assets/models/manifest.json'))
        assert packaged==manifest
        for m in manifest['models']:
            for item in [m]+([m['classifier']] if 'classifier' in m else []):
                assert hashlib.sha256(z.read('assets/'+item['asset'])).hexdigest()==item['sha256']
    report={'status':'passed','apk':str(apk.relative_to(ROOT)),'apk_sha256':sha(apk),'apk_bytes':apk.stat().st_size,
            'modes':sorted(expected_ids),'photos':sorted(photos),'results':len(results),'comparisons':comparisons,
            'android_ort':'1.30.0','desktop_ort':ort.__version__,'scope':'Actual Android tensors and graphs, three manual examples; not an accuracy sample',
            'manual_results':[{'model':r['model'],'photo':r['validation_photo'],'boxes':len(r['detections']),
                              'inference_ms':r['inference_ms'],'pipeline_ms':r['pipeline_ms'],'skipped':r['skipped_leaves']}
                              for r in results]}
    (ROOT/'docs/android/accuracy_v1/verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    if pointer.exists():
        current=read(pointer)
        if current['sha256']==report['apk_sha256']:
            current.update(status='verified',phone_parity='passed',verification='docs/android/accuracy_v1/verification.json')
            pointer.write_text(json.dumps(current,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'status':'passed','comparisons':comparisons,'results':len(results)},ensure_ascii=False))

if __name__=='__main__':main()
