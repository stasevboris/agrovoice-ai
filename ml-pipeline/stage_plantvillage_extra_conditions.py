"""Audit nine additional classes from the already downloaded publisher snapshot.

No re-download, split creation or acceptance. Archive source is pinned by SHA;
locally computed file blob hashes are not misrepresented as API comparisons.
"""
from collections import Counter,defaultdict
from datetime import datetime,timezone
import hashlib,json,os,re,shutil,zipfile
from pathlib import Path
import cv2
from PIL import Image
from prepare_supplemented import mask_box,leaf_group,signature
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'docs/experiments/yolo26s_data_v1'
OLD=ROOT/'ml-pipeline/source_candidates/yolo26s_data_v1/plantvillage_all_crops'
OUT=ROOT/'ml-pipeline/source_candidates/yolo26s_data_v1/plantvillage_extra_conditions'
AUDIT=BASE/'plantvillage_extra_conditions_r1'
EXPECTED='08c1367853c3b4afe636755ea84d454f73920e82ffcfd3453bd26b1c5d26a070'

def alias(path):return re.sub(r'^[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}___','',Path(path).stem.removesuffix('_final_masked')).casefold()
def save(state):
    state['updated_utc']=datetime.now(timezone.utc).isoformat()
    p=AUDIT/'status.tmp';p.write_text(json.dumps(state,indent=2)+'\n',encoding='utf-8');p.replace(AUDIT/'status.json')

def main():
    cv2.setNumThreads(2)
    archive=OLD/'publisher_snapshot_7f7ecc7e1eaca78107e3affe7cb5abd9427e139a.zip'
    with archive.open('rb') as f:
        if hashlib.file_digest(f,'sha256').hexdigest()!=EXPECTED:raise ValueError('Pinned archive changed')
    known={c['source_class'] for c in json.loads((OLD/'source_index.json').read_text())['categories']}
    mapping=json.loads((ROOT/'ml-pipeline/source_candidates/plantvillage_subset/leaf-map.json').read_text())
    AUDIT.mkdir(parents=True,exist_ok=False);OUT.mkdir(parents=True,exist_ok=True)
    state=dict(status='running',pid=os.getpid(),gpu=False,archive_sha256=EXPECTED,checked=0,training_accepted=0,
        source_revision='7f7ecc7e1eaca78107e3affe7cb5abd9427e139a',license='CC-BY-SA-3.0',
        helper_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        integrity_basis='Already downloaded official pinned GitHub snapshot SHA+ZIP CRC; file Git blobs computed locally, not independently compared with API')
    counts=Counter();flags=Counter();class_counts=defaultdict(Counter);save(state)
    try:
        with zipfile.ZipFile(archive) as z,(AUDIT/'records.jsonl').open('w',encoding='utf-8') as records:
            files=defaultdict(lambda:defaultdict(list))
            for item in z.infolist():
                if item.is_dir():continue
                for variant in ('color','segmented'):
                    marker='/raw/'+variant+'/'
                    if marker in item.filename:
                        category=item.filename.split(marker)[1].split('/')[0]
                        if category not in known:files[category][variant].append(item)
            assert len(files)==9
            for category,variants in sorted(files.items()):
                exact={Path(i.filename).stem.removesuffix('_final_masked'):i for i in variants['segmented']}
                ca=defaultdict(list);sa=defaultdict(list)
                for i in variants['color']:ca[alias(i.filename)].append(i)
                for i in variants['segmented']:sa[alias(i.filename)].append(i)
                for color in sorted(variants['color'],key=lambda i:i.filename):
                    name=Path(color.filename).name;segment=exact.get(Path(name).stem)
                    if segment is None and len(ca[alias(name)])==1 and len(sa[alias(name)])==1:segment=sa[alias(name)][0]
                    item=dict(id=hashlib.sha256((category+'/'+name).encode()).hexdigest()[:16],source_class=category,
                        source_filename=name,source_revision=state['source_revision'],source_license=state['license'],
                        training_acceptance='pending_new_semantics_geometry_groups_support')
                    if segment is None:
                        item['flags']=['no_unambiguous_paired_mask'];counts['unpaired']+=1
                    else:
                        pair={};sha={}
                        for variant,member in [('color',color),('segmented',segment)]:
                            raw=z.read(member);blob=hashlib.sha1(f'blob {len(raw)}\0'.encode()+raw).hexdigest()
                            target=OUT/'images'/variant/category/(blob+Path(member.filename).suffix.lower())
                            if shutil.disk_usage(OUT).free<len(raw)+10*1024**3:raise RuntimeError('10GiB free reserve required')
                            target.parent.mkdir(parents=True,exist_ok=True)
                            if target.exists():
                                if target.read_bytes()!=raw:raise ValueError('Existing staged file differs')
                            else:target.write_bytes(raw)
                            sha[variant]=hashlib.sha256(raw).hexdigest()
                            pair[variant]=dict(candidate_path=str(target.relative_to(ROOT)),archive_member=member.filename,
                                git_blob_local=blob,bytes=len(raw))
                        with Image.open(ROOT/pair['color']['candidate_path']) as rgb,Image.open(ROOT/pair['segmented']['candidate_path']) as mask:
                            box,reason=mask_box(mask) if rgb.size==mask.size and rgb.getexif().get(274,1)==1 else (None,'mask_rgb_geometry_mismatch')
                            sig=signature(rgb)
                        group=leaf_group(name,category,mapping)
                        item.update(source_files=pair,source_sha256=sha,source_signature=sig,
                            proposed_box_xywh=box,physical_leaf_group_candidate=group,flags=[])
                        if reason:item['flags'].append(reason)
                        if group is None:item['flags'].append('physical_leaf_group_unknown_or_ambiguous')
                        counts['pairs']+=1
                    counts['originals']+=1;counts['flagged']+=bool(item['flags']);flags.update(item['flags'])
                    class_counts[category]['originals']+=1;class_counts[category]['flagged']+=bool(item['flags'])
                    records.write(json.dumps(item,ensure_ascii=False)+'\n')
                    state.update(checked=counts['originals'],counts=dict(counts),flags=dict(flags),class_counts={k:dict(v) for k,v in class_counts.items()})
                    if counts['originals']%128==0:records.flush();save(state)
        state['status']='completed_extra_source_audit_not_training_ready';save(state)
    except Exception as e:state.update(status='failed',error=str(e));save(state);raise
    print(json.dumps(state,ensure_ascii=False))

if __name__=='__main__':main()
