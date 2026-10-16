"""Pin/index all original PlantDoc-matching PlantVillage categories, stage QA pairs.

This fetches public source candidates, never builds a training split. Existing
verified local files/caches are reused. Default downloads only eight pairs per
class to check mask geometry before acquiring all suitable originals.
"""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import time
from urllib.parse import quote
import requests

ROOT=Path(__file__).resolve().parents[1]
OLD=ROOT/'ml-pipeline/source_candidates/plantvillage_subset'
DEST=ROOT/'ml-pipeline/source_candidates/yolo26s_data_v1/plantvillage_all_crops'
REV='7f7ecc7e1eaca78107e3affe7cb5abd9427e139a'
REPO='spMohanty/PlantVillage-Dataset'


def save(path,data):
    temp=path.with_suffix(path.suffix+'.tmp')
    temp.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    temp.replace(path)


def fetch(url):
    for attempt in range(3):
        response=requests.get(url,timeout=30,headers={'User-Agent':'AgroVoice-public-data-audit/1.0'})
        if response.status_code==429 or response.status_code>=500:
            if attempt<2:
                time.sleep(3*(attempt+1));continue
        response.raise_for_status()
        return response.content
    raise RuntimeError('Unreachable response retry state')


def api(endpoint):
    key=hashlib.sha256(endpoint.encode()).hexdigest()+'.json'
    previous=OLD/'api_cache'/key
    target=DEST/'api_cache'/key
    if previous.exists():data=json.loads(previous.read_bytes())
    else:
        if not target.exists():target.write_bytes(fetch('https://api.github.com/repos/'+REPO+'/'+endpoint))
        data=json.loads(target.read_bytes())
    if data.get('sha')!=endpoint.rsplit('/',1)[-1] or data.get('truncated'):
        raise ValueError('Wrong/incomplete publisher tree: '+endpoint)
    return data


def alias(path):
    stem=Path(path).stem.removesuffix('_final_masked')
    return re.sub(r'^[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}___','',stem).casefold()


def build_index():
    mappings=json.loads((ROOT/'docs/experiments/yolo26s_data_v1/source_class_mapping.draft.json').read_text())['PlantVillage_mapping']
    tree=api('git/trees/'+REV)['tree']
    raw=next(x['sha'] for x in tree if x['path']=='raw')
    variants=api('git/trees/'+raw)['tree']
    folders={v:{x['path']:x['sha'] for x in api('git/trees/'+next(x['sha'] for x in variants if x['path']==v))['tree']}
             for v in ('color','segmented')}
    result={'status':'indexing_candidates','source':'https://github.com/'+REPO,'revision':REV,'pairing_revision':2,
            'license':'CC-BY-SA-3.0','license_evidence':str((OLD/'README_HF.md').relative_to(ROOT)),
            'purpose':'QA candidates across all29 original classes; not accepted training annotations',
            'categories':[],'files':[], 'training_accepted':0}
    for mapping in mappings:
        category=mapping['source_class']
        color=api('git/trees/'+folders['color'][category])['tree']
        masked=[x for x in api('git/trees/'+folders['segmented'][category])['tree'] if x['type']=='blob']
        segment={Path(x['path']).stem.removesuffix('_final_masked'):x for x in masked}
        aliases={}
        color_aliases={}
        for x in masked:aliases.setdefault(alias(x['path']),[]).append(x)
        for x in color:
            if x['type']=='blob':color_aliases.setdefault(alias(x['path']),[]).append(x)
        available=[];paired={};rules={}
        for x in color:
            if x['type']!='blob':continue
            if Path(x['path']).stem in segment:
                paired[x['path']]=segment[Path(x['path']).stem];rules[x['path']]='exact source stem'
            elif len(aliases.get(alias(x['path']),[]))==1 and len(color_aliases[alias(x['path'])])==1:
                paired[x['path']]=aliases[alias(x['path'])][0]
                rules[x['path']]='unique author filename after removal of generated UUID prefix'
            else:continue
            available.append(x)
        available.sort(key=lambda x:hashlib.sha256(x['path'].encode()).hexdigest())
        result['categories'].append({**mapping,'available_original_pairs':len(available),
                                     'unpaired_color_files':sum(x['type']=='blob' for x in color)-len(available)})
        for x in available:
            for variant,item in [('color',x),('segmented',paired[x['path']])]:
                relative=f"raw/{variant}/{category}/{item['path']}"
                result['files'].append({'category':category,'variant':variant,'source_path':relative,
                                        'color_filename':x['path'],'git_blob':item['sha'],'pairing_rule':rules[x['path']],
                                        'bytes_publisher':item.get('size'),
                                        'candidate_path':str((DEST/'images'/variant/category/(item['sha']+Path(item['path']).suffix.lower())).relative_to(ROOT))})
        result['updated_utc']=datetime.now(timezone.utc).isoformat()
        save(DEST/'source_index.json',result)
        print(f'Indexed {category}: {len(available)} original pairs',flush=True)
    result['status']='publisher_index_complete_not_training_ready'
    save(DEST/'source_index.json',result)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pairs-per-class',type=int,default=8)
    parser.add_argument('--index-only',action='store_true')
    args=parser.parse_args()
    if args.pairs_per_class<1:parser.error('Positive QA sample required; no automatic all download')
    (DEST/'api_cache').mkdir(parents=True,exist_ok=True)
    index_path=DEST/'source_index.json'
    if index_path.exists():
        index=json.loads(index_path.read_text())
        if index.get('status')!='publisher_index_complete_not_training_ready' or index.get('pairing_revision')!=2:
            if not (DEST/'source_index_r1_exact_stems.json').exists():save(DEST/'source_index_r1_exact_stems.json',index)
            if (DEST/'qa_downloads.json').exists() and not (DEST/'qa_downloads_r1_exact_stems.json').exists():
                save(DEST/'qa_downloads_r1_exact_stems.json',json.loads((DEST/'qa_downloads.json').read_text()))
            index=build_index()
    else:index=build_index()
    if args.index_only:return
    names={c['source_class']:[x['color_filename'] for x in index['files'] if x['category']==c['source_class'] and x['variant']=='color'][:args.pairs_per_class]
           for c in index['categories']}
    selected=[x for x in index['files'] if x['color_filename'] in names[x['category']]]
    report={'status':'staging_qa_pairs','revision':REV,'selected_pairs_per_class':args.pairs_per_class,
            'total_pairs_available':sum(c['available_original_pairs'] for c in index['categories']),
            'classes':len(index['categories']),'files':[],'training_accepted':0}
    save(DEST/'qa_downloads.json',report)
    def download(item):
        target=ROOT/item['candidate_path'];old=OLD/item['source_path']
        payload=None; reused=False; target_verified=False
        for candidate in (target,old):
            if candidate.exists():
                existing=candidate.read_bytes()
                if hashlib.sha1(f'blob {len(existing)}\0'.encode()+existing).hexdigest()==item['git_blob']:
                    payload=existing;reused=True;target_verified=candidate==target;break
        if payload is None:payload=fetch(f'https://raw.githubusercontent.com/{REPO}/{REV}/'+quote(item['source_path'],safe='/'))
        if hashlib.sha1(f'blob {len(payload)}\0'.encode()+payload).hexdigest()!=item['git_blob']:
            raise ValueError('Publisher Git blob mismatch: '+item['source_path'])
        if not target_verified:
            if shutil.disk_usage(DEST).free<len(payload)+10*1024**3:raise RuntimeError('Less than10GiB free reserve')
            target.parent.mkdir(parents=True,exist_ok=True)
            part=target.with_suffix(target.suffix+'.part')
            part.write_bytes(payload)
            part.replace(target)
        return {**item,'status':'verified_existing' if reused else 'downloaded_verified','sha256':hashlib.sha256(payload).hexdigest(),'bytes':len(payload)}
    with ThreadPoolExecutor(max_workers=4) as pool:
        pending={pool.submit(download,item):item for item in selected}
        for i,future in enumerate(as_completed(pending),1):
            try:report['files'].append(future.result())
            except Exception as error:report['files'].append({**pending[future],'status':'failed','error':str(error)})
            if i%32==0 or i==len(selected):
                save(DEST/'qa_downloads.json',report)
                print(f'QA files checked {i}/{len(selected)}; accepted train0',flush=True)
    report['status']='qa_sources_staged_not_training_ready' if all(x['status']!='failed' for x in report['files']) else 'qa_staging_partial_failure'
    report['updated_utc']=datetime.now(timezone.utc).isoformat()
    save(DEST/'qa_downloads.json',report)
    print(json.dumps({k:report[k] for k in ('status','classes','total_pairs_available','training_accepted')}),flush=True)


if __name__=='__main__':main()
