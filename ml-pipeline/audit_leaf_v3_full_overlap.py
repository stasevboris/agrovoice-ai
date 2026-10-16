"""Efficient conservative signature screen across full sources and old splits.

Five disjoint bands guarantee a candidate for pHash Hamming<=4. Every candidate
is then verified with the unchanged source near() rule. Links are review clues,
not plant identities or accepted split assignments.
"""
from collections import defaultdict
from datetime import datetime, timezone
import hashlib, json, os
from pathlib import Path
from prepare_supplemented import near

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'docs/experiments/yolo26s_data_v1'
OUT=BASE/'candidate_overlap_full_r2'
WIDTHS=(13,13,13,13,12)


def bands(value):
    offset=0
    for number,width in enumerate(WIDTHS):
        yield (number,(int(value)>>offset)&((1<<width)-1))
        offset+=width


def main():
    OUT.mkdir(parents=True,exist_ok=False)
    sources=[('PlantVillage',BASE/'plantvillage_full_geometry_r1/records.jsonl','jsonl'),
        ('CucumberZenodo',BASE/'cucumber_review/inventory.json','inventory'),
        ('OLID',BASE/'olid_review_r2_chroma/inventory.json','inventory'),
        ('HistoricalV2',ROOT/'ml-pipeline/dataset_clean_v2/manifest.json','manifest')]
    rows=[];hashes={}
    for source,path,kind in sources:
        raw=path.read_bytes();hashes[str(path.relative_to(ROOT))]=hashlib.sha256(raw).hexdigest()
        data=[json.loads(x) for x in raw.splitlines()] if kind=='jsonl' else json.loads(raw)
        if kind=='inventory':data=data['images']
        for row in data:
            item=dict(source=source,id=row.get('id',row.get('filename')),
                signature=row.get('source_signature',row.get('signature')),
                split=row.get('split'),group=row.get('group_id'),source_class=row.get('source_class'))
            if item['signature'] is None:raise ValueError('Missing signature')
            rows.append(item)
    index=defaultdict(list);exact=defaultdict(list);parent=list(range(len(rows)))
    counts=dict(images=len(rows),checked=0,candidate_links=0,historical_links=0,historical_internal_links=0)
    state=dict(status='running',pid=os.getpid(),gpu=False,training_accepted=0,
        input_sha256=hashes,counts=counts,
        algorithm='Five exhaustive pHash bands then exact existing near() verification; decoded exact also indexed',
        limitations=['Links do not prove physical plant identity; no split acceptance.',
            'Rotations, opposite leaf sides, crops or large lighting changes may be missed.',
            'Old holdout images never move to training; historical matches are not new independent test.'])
    def save():
        state['updated_utc']=datetime.now(timezone.utc).isoformat()
        p=OUT/'status.tmp';p.write_text(json.dumps(state,indent=2)+'\n',encoding='utf-8');p.replace(OUT/'status.json')
    def root(n):
        while parent[n]!=n:parent[n]=parent[parent[n]];n=parent[n]
        return n
    save()
    try:
        with (OUT/'links.jsonl').open('w',encoding='utf-8') as output:
            for i,a in enumerate(rows):
                sig=a['signature'];keys=list(bands(sig['phash']))
                candidates=set(exact[sig['decoded_hash']])
                for key in keys:candidates.update(index[key])
                for j in sorted(candidates):
                    b=rows[j]
                    if not near(sig,b['signature']):continue
                    historical=(a['source']=='HistoricalV2')+(b['source']=='HistoricalV2')
                    key='historical_internal_links' if historical==2 else 'historical_links' if historical==1 else 'candidate_links'
                    counts[key]+=1;parent[root(i)]=root(j)
                    link=dict(a={k:v for k,v in a.items() if k!='signature'},
                        b={k:v for k,v in b.items() if k!='signature'},
                        exact_decoded=sig['decoded_hash']==b['signature']['decoded_hash'],
                        phash_distance=(int(sig['phash'])^int(b['signature']['phash'])).bit_count(),
                        dhash_distance=(int(sig['dhash'])^int(b['signature']['dhash'])).bit_count())
                    output.write(json.dumps(link,ensure_ascii=False)+'\n')
                for key in keys:index[key].append(i)
                exact[sig['decoded_hash']].append(i);counts['checked']=i+1
                if (i+1)%512==0:output.flush();save()
        groups=defaultdict(list)
        for i,row in enumerate(rows):groups[root(i)].append({k:v for k,v in row.items() if k!='signature'})
        with (OUT/'suggested_groups.jsonl').open('w',encoding='utf-8') as output:
            for group in groups.values():
                if len(group)>1:output.write(json.dumps(group,ensure_ascii=False)+'\n')
        counts['suggested_components']=len(groups)
        state.update(status='completed_overlap_screen_requires_review',links_sha256=hashlib.sha256((OUT/'links.jsonl').read_bytes()).hexdigest())
        save()
    except Exception as e:state.update(status='failed',error=str(e));save();raise
    print(json.dumps(state,ensure_ascii=False))


if __name__=='__main__':main()
