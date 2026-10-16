"""Fetch public, revision-pinned detector weights; never change the ROCm environment."""
import hashlib,json,urllib.request
from datetime import datetime,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'ml-pipeline/runtime/detector_weights'
REPORT=ROOT/'docs/experiments/accuracy_v1'

def api(url):
    request=urllib.request.Request(url,headers={'User-Agent':'AgroVoice-Research/1.0'})
    with urllib.request.urlopen(request,timeout=60) as response:return json.load(response)

def fetch(url,path):
    if not path.exists():
        temporary=path.with_suffix(path.suffix+'.part')
        with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'AgroVoice-Research/1.0'}),timeout=120) as source,temporary.open('wb') as target:
            while data:=source.read(1024*1024):target.write(data)
        temporary.replace(path)
    with path.open('rb') as source:digest=hashlib.file_digest(source,'sha256').hexdigest()
    return {'file':str(path.relative_to(ROOT)),'bytes':path.stat().st_size,'sha256':digest,'url':url}

def main():
    OUT.mkdir(parents=True,exist_ok=True);REPORT.mkdir(parents=True,exist_ok=True)
    meta=api('https://huggingface.co/api/models/Intellindust/DEIMv2_HGNetv2_N_COCO')
    revision=meta['sha'];base=f'https://huggingface.co/Intellindust/DEIMv2_HGNetv2_N_COCO/resolve/{revision}/'
    records=[fetch(base+name,OUT/('deimv2_n_'+name)) for name in ('config.json','model.safetensors')]
    # The published Ultralytics release is fixed, rather than a moving latest URL.
    release=api('https://api.github.com/repos/ultralytics/assets/releases/tags/v8.4.0')
    asset=next(item for item in release['assets'] if item['name']=='yolo26s.pt')
    weights=fetch(asset['browser_download_url'],ROOT/'ml-pipeline/yolo26s.pt')
    if asset.get('digest'):assert asset['digest']=='sha256:'+weights['sha256']
    (REPORT/'detector_weights.json').write_text(json.dumps({'created_utc':datetime.now(timezone.utc).isoformat(),
        'deimv2':{'repository':meta['id'],'revision':revision,'files':records,'license':'DEIMv2 non-commercial; source license retained'},
        'yolo26s':weights},ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'status':'downloaded','deim_revision':revision,'files':records+[weights]}))

if __name__=='__main__':main()
