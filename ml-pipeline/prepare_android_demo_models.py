"""Package an already evaluated, matched-seed model pair without GPU work."""
import ast
import hashlib
import json
from pathlib import Path
import shutil
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'android-app/app/src/main/assets/models'
SEED = 20261003
RUSSIAN = [
    'Яблоня: парша', 'Яблоня: лист без указанной болезни', 'Яблоня: ржавчина',
    'Перец: лист без указанной болезни', 'Перец: пятнистость',
    'Голубика: лист', 'Вишня: лист', 'Кукуруза: серая пятнистость',
    'Кукуруза: ожог листа', 'Кукуруза: ржавчина', 'Персик: лист',
    'Картофель: лист без указанной болезни', 'Картофель: альтернариоз',
    'Картофель: фитофтороз', 'Малина: лист', 'Соя: лист',
    'Тыквенные: мучнистая роса (класс Squash)', 'Клубника: лист',
    'Томат: альтернариоз', 'Томат: септориоз', 'Томат: лист без указанной болезни',
    'Томат: бактериальная пятнистость', 'Томат: фитофтороз', 'Томат: мозаика',
    'Томат: вирус пожелтения (метка источника)', 'Томат: плесень листа',
    'Томат: повреждение паутинным клещом', 'Виноград: лист', 'Виноград: чёрная гниль',
    'Огурец: лист, состояние неизвестно'
]

def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    classes=json.loads((ROOT/'ml-pipeline/dataset_clean_v2/classes.json').read_text(encoding='utf-8'))
    assert len(classes)==len(RUSSIAN)==30
    manifest={'dataset':'clean v2', 'dataset_fingerprint':'7306a09a94cfccb1854707d86e45de654a403145dffc59d92d38cbc5b694d5bc',
              'seed':SEED,'input_side':416,'provisional':True,'classes':classes,'labels_ru':RUSSIAN,'models':[]}
    for model in ('yolo11n','yolo26n'):
        directory=ROOT/'runs/detect'/f'study10_v2_{model}_seed{SEED}'
        assert (directory/'evaluation.json').is_file()
        source=directory/'weights/best.onnx'
        options=ort.SessionOptions(); options.intra_op_num_threads=1
        session=ort.InferenceSession(str(source),options,providers=['CPUExecutionProvider'])
        names=ast.literal_eval(session.get_modelmeta().custom_metadata_map['names'])
        assert [names[i] for i in range(len(classes))]==classes
        assert session.get_inputs()[0].shape==[1,3,416,416]
        assert session.get_outputs()[0].shape==[1,34,3549]
        target=ASSETS/f'{model}.onnx'
        shutil.copy2(source,target)
        manifest['models'].append({'id':model,'name':model.replace('yolo','YOLO'), 'asset':f'models/{target.name}',
                                  'sha256':hashlib.sha256(target.read_bytes()).hexdigest(), 'bytes':target.stat().st_size,
                                  'source':str(source.relative_to(ROOT)), 'input_shape':[1,3,416,416], 'output_shape':[1,34,3549]})
    text=json.dumps(manifest,ensure_ascii=False,indent=2)+'\n'
    (ASSETS/'manifest.json').write_text(text,encoding='utf-8')
    report=ROOT/'docs/android/field_demo_models_2026-10-03.json'
    report.parent.mkdir(parents=True,exist_ok=True); report.write_text(text,encoding='utf-8')
    print('Prepared both models:',sum(m['bytes'] for m in manifest['models']), 'bytes')

if __name__=='__main__': main()
