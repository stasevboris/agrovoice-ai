"""Acquire a pinned public snapshot, stage indexed candidates; never train.

All staged payloads must match the previously verified publisher Git tree.
The archive is retained for provenance; no raw source or old dataset is edited.
"""
from __future__ import annotations
from datetime import datetime, timezone
import hashlib, json, os, shutil, time, zipfile
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT/'ml-pipeline/source_candidates/yolo26s_data_v1/plantvillage_all_crops'
REV = '7f7ecc7e1eaca78107e3affe7cb5abd9427e139a'
URL = f'https://api.github.com/repos/spMohanty/PlantVillage-Dataset/zipball/{REV}'


def save(data):
    data['updated_utc'] = datetime.now(timezone.utc).isoformat()
    temporary = DEST/'archive_stage_status.tmp'
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    temporary.replace(DEST/'archive_stage_status.json')


def git_hash(payload):
    return hashlib.sha1(f'blob {len(payload)}\0'.encode()+payload).hexdigest()


def main():
    index_path = DEST/'source_index.json'
    index = json.loads(index_path.read_text(encoding='utf-8'))
    if index['revision'] != REV or index['pairing_revision'] != 2:
        raise ValueError('Wrong source pin or pairing revision')
    lock = DEST/'archive_stage.lock'
    # Exclusive file prevents duplicate workers; after an abnormal exit inspect
    # its PID before archiving the stale task-owned lock and retrying.
    with lock.open('x', encoding='utf-8') as file:
        file.write(json.dumps({'pid':os.getpid(), 'created_utc':datetime.now(timezone.utc).isoformat()}))
    status = dict(status='starting', pid=os.getpid(), source_revision=REV, url=URL,
        index_sha256=hashlib.sha256(index_path.read_bytes()).hexdigest(),
        helper_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        gpu=False, training_accepted=0, staged_files=0, reused_files=0)
    archive = DEST/f'publisher_snapshot_{REV}.zip'
    try:
        if not archive.exists():
            part = archive.with_suffix('.zip.part')
            offset = part.stat().st_size if part.exists() else 0
            headers = {'User-Agent':'AgroVoice-public-data-audit/1.0'}
            if offset:
                headers['Range'] = f'bytes={offset}-'
            with requests.get(URL, headers=headers, stream=True, timeout=(30,60)) as response:
                response.raise_for_status()
                if offset and response.status_code == 206:
                    if not response.headers.get('Content-Range','').startswith(f'bytes {offset}-'):
                        raise ValueError('Incorrect partial response')
                    mode = 'ab'
                else:
                    offset = 0; mode = 'wb'
                if 'html' in response.headers.get('Content-Type','').lower():
                    raise ValueError('HTML returned instead of a source archive')
                remaining = int(response.headers.get('Content-Length',0))
                if shutil.disk_usage(DEST).free < remaining+10*1024**3:
                    raise RuntimeError('Insufficient free space above10GiB reserve')
                status.update(status='downloading_public_snapshot', final_url=response.url,
                    response_status=response.status_code, downloaded_bytes=offset)
                save(status); last = time.monotonic()
                with part.open(mode) as output:
                    for chunk in response.iter_content(1024*1024):
                        if not chunk:
                            continue
                        if shutil.disk_usage(DEST).free < len(chunk)+10*1024**3:
                            raise RuntimeError('Free space reserve reached')
                        output.write(chunk); status['downloaded_bytes'] += len(chunk)
                        if time.monotonic()-last >= 10:
                            save(status); last = time.monotonic()
                if remaining and status['downloaded_bytes'] != offset+remaining:
                    raise ValueError('Truncated HTTP body')
            with zipfile.ZipFile(part) as z:
                if not z.infolist():
                    raise ValueError('Empty source archive')
            part.replace(archive)
        status.update(status='verifying_and_staging', archive=str(archive.relative_to(ROOT)),
            archive_sha256=hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest(),
            archive_bytes=archive.stat().st_size)
        save(status)
        with zipfile.ZipFile(archive) as z, (DEST/'archive_staged_files.jsonl').open('w', encoding='utf-8') as output:
            names = z.namelist()
            prefixes = {n.split('/',1)[0] for n in names if '/' in n}
            if len(prefixes) != 1:
                raise ValueError('Unexpected publisher archive root')
            prefix = next(iter(prefixes))+'/'
            for number, item in enumerate(index['files'],1):
                target = (ROOT/item['candidate_path']).resolve()
                target.relative_to(DEST.resolve())
                payload = target.read_bytes() if target.exists() else None
                reused = payload is not None and git_hash(payload) == item['git_blob']
                if not reused:
                    payload = z.read(prefix+item['source_path'])
                if git_hash(payload) != item['git_blob'] or len(payload) != item['bytes_publisher']:
                    raise ValueError('Publisher tree mismatch: '+item['source_path'])
                if not reused:
                    if shutil.disk_usage(DEST).free < len(payload)+10*1024**3:
                        raise RuntimeError('Free space reserve reached')
                    target.parent.mkdir(parents=True, exist_ok=True)
                    temporary = target.with_suffix(target.suffix+'.part')
                    temporary.write_bytes(payload); temporary.replace(target)
                output.write(json.dumps({**item, 'sha256':hashlib.sha256(payload).hexdigest(),
                    'status':'verified_existing' if reused else 'staged_verified'}, ensure_ascii=False)+'\n')
                status['staged_files'] = number
                status['reused_files'] += int(reused)
                if number % 256 == 0:
                    output.flush(); save(status)
        status.update(status='all_indexed_source_candidates_staged_not_training_ready')
        save(status)
    except Exception as error:
        status.update(status='failed', error=str(error)); save(status)
        raise
    finally:
        lock.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
