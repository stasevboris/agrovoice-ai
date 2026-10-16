"""Vendor the official Russian Vosk mobile model with per-file provenance."""
import hashlib, json, shutil, zipfile
from datetime import datetime, timezone
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parents[1]
NAME = 'vosk-model-small-ru-0.22'
URL = f'https://alphacephei.com/vosk/models/{NAME}.zip'

def sha(path):
    with Path(path).open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()

def main():
    cache = ROOT/'ml-pipeline/downloads/voice'; cache.mkdir(parents=True, exist_ok=True)
    archive = cache/f'{NAME}.zip'
    if not archive.exists():
        temporary = archive.with_suffix('.part')
        with requests.get(URL, stream=True, timeout=(25, 60)) as response:
            response.raise_for_status()
            with temporary.open('wb') as out:
                for chunk in response.iter_content(1024*1024): out.write(chunk)
        assert zipfile.is_zipfile(temporary), 'Downloaded file is not a model ZIP'
        temporary.replace(archive)
    assets = ROOT/'android-app/app/src/main/assets/voice'
    folder = assets/'ru'; folder.mkdir(parents=True, exist_ok=True)
    records = []
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            if member.is_dir(): continue
            path = Path(member.filename)
            assert path.parts[0] == NAME and '..' not in path.parts and len(path.parts)>1
            relative = Path(*path.parts[1:]); target = folder/relative
            assert target.resolve().is_relative_to(folder.resolve())
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(member) as source, target.open('wb') as out: shutil.copyfileobj(source, out)
            records.append({'path': relative.as_posix(), 'sha256': sha(target), 'bytes': target.stat().st_size})
    assert (folder/'am/final.mdl').is_file() and (folder/'conf/model.conf').is_file()
    manifest = {'model': NAME, 'language':'ru', 'source': URL,
                'license':'Apache-2.0', 'license_source':'https://alphacephei.com/vosk/models',
                'archive_sha256':sha(archive), 'archive_bytes':archive.stat().st_size,
                'unpacked_bytes':sum(r['bytes'] for r in records), 'files':records,
                'prepared_utc':datetime.now(timezone.utc).isoformat()}
    text = json.dumps(manifest,ensure_ascii=False,indent=2)
    (assets/'manifest.json').write_text(text,encoding='utf-8')
    reports = ROOT/'docs/android/voice_v1'; reports.mkdir(parents=True,exist_ok=True)
    (reports/'model_provenance.json').write_text(text,encoding='utf-8')
    licenses = ROOT/'android-app/app/src/main/assets/licenses'
    license_response = requests.get('https://raw.githubusercontent.com/alphacep/vosk-api/master/COPYING',timeout=25)
    license_response.raise_for_status()
    assert 'Apache License' in license_response.text
    (licenses/'VOSK-APACHE-2.0.txt').write_text(license_response.text,encoding='utf-8')
    print(json.dumps({k:manifest[k] for k in ['model','archive_sha256','archive_bytes','unpacked_bytes']}))

if __name__=='__main__':main()
