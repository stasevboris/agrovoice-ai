"""Review exact/near candidate overlaps with historical splits; no data changes."""
from datetime import datetime, timezone
import hashlib, json
from pathlib import Path
from prepare_supplemented import near

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/experiments/yolo26s_data_v1/candidate_overlap_r1.json'


def main():
    if OUT.exists():
        raise FileExistsError('Preserve prior audit; use a new revision')
    inputs=[('CucumberZenodo','docs/experiments/yolo26s_data_v1/cucumber_review/inventory.json'),
        ('OLID','docs/experiments/yolo26s_data_v1/olid_review_r2_chroma/inventory.json'),
        ('PlantVillageQA','docs/experiments/yolo26s_data_v1/plantvillage_qa_r1/inventory.json')]
    candidates=[]; input_shas={}
    for source,path in inputs:
        payload=(ROOT/path).read_bytes();input_shas[path]=hashlib.sha256(payload).hexdigest()
        for row in json.loads(payload)['images']:
            candidates.append(dict(source=source,id=row['id'],source_class=row['source_class'],
                signature=row.get('source_signature',row.get('signature'))))
    payload=(ROOT/'ml-pipeline/dataset_clean_v2/manifest.json').read_bytes()
    input_shas['ml-pipeline/dataset_clean_v2/manifest.json']=hashlib.sha256(payload).hexdigest()
    previous=[dict(source='HistoricalV2',id=r['filename'],split=r['split'],
        group=r['group_id'],signature=r['source_signature']) for r in json.loads(payload)]
    parent=list(range(len(candidates)))
    def root(n):
        while parent[n]!=n:
            parent[n]=parent[parent[n]];n=parent[n]
        return n
    links=[]; history_links=[]
    for i,a in enumerate(candidates):
        for j in range(i):
            b=candidates[j]
            if near(a['signature'],b['signature']):
                parent[root(i)]=root(j)
                links.append(dict(a=[a['source'],a['id']],b=[b['source'],b['id']],
                    exact_decoded=a['signature']['decoded_hash']==b['signature']['decoded_hash']))
        for b in previous:
            if near(a['signature'],b['signature']):
                history_links.append(dict(candidate=[a['source'],a['id']],historical=b['id'],
                    historical_split=b['split'],historical_group=b['group'],
                    exact_decoded=a['signature']['decoded_hash']==b['signature']['decoded_hash']))
    groups={}
    for i,a in enumerate(candidates):
        groups.setdefault(root(i),[]).append([a['source'],a['id']])
    result=dict(status='candidate_overlap_review_required_not_split_authorization',
        updated_utc=datetime.now(timezone.utc).isoformat(),input_sha256=input_shas,
        algorithm='Exact decoded RGB or aspect ratio within3%, pHash and dHash Hamming<=4; existing source signatures',
        limitations=['This screen can miss rotations/opposite leaf surfaces and crops.',
            'Near matches may be different leaves; review before grouping.',
            'Missing physical plant/session IDs remain unknown; connected groups are not proven plant IDs.',
            'Historical matches must not become a new independent test; old holdouts never enter training.'],
        counts=dict(candidates=len(candidates),historical=len(previous),candidate_links=len(links),
            historical_links=len(history_links),suggested_connected_groups=len(groups)),
        candidate_links=links,historical_links=history_links,
        suggested_groups=[g for g in groups.values() if len(g)>1],training_accepted=0)
    OUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result['counts']))


if __name__=='__main__':main()
