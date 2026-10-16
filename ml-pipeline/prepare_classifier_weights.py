"""Pre-fetch official Torchvision checkpoints on CPU so later queue jobs need no network."""
import hashlib,json
from pathlib import Path
from urllib.parse import urlparse
import torch
from torchvision import models
ROOT=Path(__file__).resolve().parents[1]
def main():
    entries=[]
    for weights in [models.MobileNet_V3_Large_Weights.DEFAULT,models.EfficientNet_B0_Weights.DEFAULT,models.EfficientNet_V2_S_Weights.DEFAULT]:
        state=weights.get_state_dict(progress=False,check_hash=True);del state
        name=Path(urlparse(weights.url).path).name;file=Path(torch.hub.get_dir())/'checkpoints'/name
        with file.open('rb') as source:digest=hashlib.file_digest(source,'sha256').hexdigest()
        assert digest.startswith(name.rsplit('-',1)[-1].split('.')[0])
        entries.append({'weights':str(weights),'url':weights.url,'file':str(file),'sha256':digest,'bytes':file.stat().st_size})
    report={'status':'downloaded_and_hash_verified','device':'CPU only','weights':entries}
    (ROOT/'docs/experiments/accuracy_v1/classifier_weights.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report))
if __name__=='__main__':main()
