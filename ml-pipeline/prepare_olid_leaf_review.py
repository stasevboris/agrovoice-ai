"""Audit existing OLID archives and propose CPU leaf boxes for review only.

No image/annotation is accepted into training here. Black-cloth foreground
segmentation proposes geometry, while diagnosis remains source-provided.
"""
from __future__ import annotations

import hashlib
import io
import json
import time
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import cv2
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
import numpy as np
from PIL import Image, ImageOps
from prepare_supplemented import signature

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'ml-pipeline/source_candidates/research_2026-10-03/olid'
OUT = ROOT/'docs/experiments/yolo26s_data_v1/olid_review'
ARCHIVES = {
    'cucumber.zip': 'ee699a9bc84dcdd9c78cba37f79bf900',
    'tomato__part_1.zip': '35607a338f08a0f2fe64406a48b5d5ef',
    'tomato__part_2.zip': '83730955bfd6b1628cd9a582e993a75b',
}
TARGETS = {
    'cucumber__healthy': 'Cucumber healthy leaf',
    'cucumber__K': 'Cucumber potassium deficiency',
    'cucumber__N': 'Cucumber nitrogen deficiency',
    'cucumber__N_K': 'Cucumber nitrogen and potassium deficiency',
    'tomato__healthy': 'Tomato leaf',
    'tomato__K': 'Tomato potassium deficiency',
    'tomato__N': 'Tomato nitrogen deficiency',
    'tomato__N_K': 'Tomato nitrogen and potassium deficiency',
    'tomato__LM': 'Tomato leaf miner damage',
    'tomato__MIT': 'Tomato mite damage (species unspecified)',
    'tomato__JAS_MIT': 'Tomato jassid and mite damage (combined)',
}


def box_proposal(rgb):
    image = np.asarray(rgb)
    lab = cv2.cvtColor(image, cv2.COLOR_RGB2LAB).astype(np.float32)
    edge = np.concatenate((lab[:8].reshape(-1,3), lab[-8:].reshape(-1,3),
                           lab[:, :8].reshape(-1,3), lab[:, -8:].reshape(-1,3)))
    background = np.median(edge, axis=0)
    distance = np.linalg.norm(lab-background, axis=2)
    # Only a proposal: cloth sheen, petioles and pale/dry leaf tissue may
    # invalidate this geometry. Every accepted box needs visual review.
    mask = ((distance > 25) & (lab[:, :, 0] > 25)).astype(np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5,5), np.uint8))
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3,3), np.uint8))
    n, component, stats, _ = cv2.connectedComponentsWithStats(mask)
    if n < 2:
        return None, {'flag': 'no_foreground'}
    largest = 1+int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    x,y,w,h,area = map(int, stats[largest])
    ih,iw = mask.shape
    coverage = area/max(1, int(mask.sum()))
    fraction = area/(iw*ih)
    touches = bool(x==0 or y==0 or x+w==iw or y+h==ih)
    flags = []
    if fraction < .05 or fraction > .9: flags.append('foreground_area_extreme')
    if coverage < .9: flags.append('multiple_foreground_components')
    if touches: flags.append('frame_edge_leaf_or_background_uncertain')
    return [(x+w/2)/iw, (y+h/2)/ih, w/iw, h/ih], {
        'foreground_fraction': fraction, 'largest_component_coverage': coverage,
        'touches_frame_edge': touches, 'flags': flags,
    }


def main():
    cv2.setNumThreads(2)
    OUT.mkdir(parents=True, exist_ok=True)
    previews = OUT/'previews'
    previews.mkdir(exist_ok=True)
    rows, archive_info = [], []
    notice = time.monotonic()
    for filename, md5 in ARCHIVES.items():
        path = SOURCE/filename
        with path.open('rb') as stream:
            actual = hashlib.file_digest(stream, 'md5').hexdigest()
        if actual != md5:
            raise RuntimeError(f'Publisher MD5 differs: {filename}')
        archive_info.append({'file':filename, 'bytes':path.stat().st_size, 'md5':actual})
        with zipfile.ZipFile(path) as archive:
            for member in sorted(n for n in archive.namelist() if n.lower().endswith(('.jpg','.jpeg','.png'))):
                folder = Path(member).parent.name
                if folder not in TARGETS:
                    raise RuntimeError(f'Unmapped source folder: {folder}')
                payload = archive.read(member)
                with Image.open(io.BytesIO(payload)) as original:
                    orientation = original.getexif().get(274,1)
                    rgb = ImageOps.exif_transpose(original).convert('RGB')
                    size = rgb.size
                    sig = signature(rgb)
                    rgb.thumbnail((1024,1024), Image.Resampling.LANCZOS)
                    box, checks = box_proposal(rgb)
                    item_id = hashlib.sha256((filename+'\0'+member).encode()).hexdigest()[:20]
                    preview = previews/(item_id+'.jpg')
                    rgb.save(preview, quality=85)
                rows.append({'id':item_id, 'archive':filename, 'image_member':member,
                             'source_class':folder, 'proposed_state':TARGETS[folder],
                             'source_sha256':hashlib.sha256(payload).hexdigest(),
                             'source_signature':sig, 'bytes':len(payload),
                             'exif_orientation':orientation, 'upright_size':list(size),
                             'proposed_box_xywh':box, 'geometry_checks':checks,
                             'review_preview':str(preview.relative_to(ROOT)),
                             'annotation_acceptance':'pending_visual_review',
                             'diagnosis_basis':'Author expert/laboratory labeling described in paper; no diagnosis inferred from image',
                             'class_contract_note':'MIT is generic mite, not automatically old two-spotted class26; LM is leaf miner, not leaf mold; combinations stay combined',
                             'plant_id':None, 'session_id':None})
                if time.monotonic()-notice > 25:
                    print(f'OLID audited {len(rows)}/1047 images; training accepted0',flush=True)
                    notice=time.monotonic()
        print(f'Verified and inventoried {filename}',flush=True)
    counts = Counter(r['source_class'] for r in rows)
    if len(rows)!=1047:
        raise RuntimeError(f'Unexpected OLID image count: {len(rows)}')
    preview_rows=[]
    for folder in sorted(counts):
        selected=[r for r in rows if r['source_class']==folder]
        selected.sort(key=lambda r:r['source_sha256'])
        preview_rows.extend(selected[:12])
    for start in range(0,len(preview_rows),16):
        fig,axes=plt.subplots(4,4,figsize=(14,12))
        for axis in axes.flat: axis.axis('off')
        for axis,row in zip(axes.flat,preview_rows[start:start+16]):
            with Image.open(ROOT/row['review_preview']) as rgb:
                axis.imshow(rgb)
                if row['proposed_box_xywh']:
                    x,y,w,h=row['proposed_box_xywh']
                    axis.add_patch(Rectangle(((x-w/2)*rgb.width,(y-h/2)*rgb.height),w*rgb.width,h*rgb.height,
                                             fill=False,edgecolor='red',linewidth=1.2))
            axis.set_title(f"{row['source_class']}\n{row['id']}",fontsize=8)
        fig.suptitle('OLID: PROPOSED whole-leaf boxes, not accepted annotations')
        fig.tight_layout()
        fig.savefig(OUT/f'geometry_sample_{start//16+1:02}.jpg',dpi=125)
        plt.close(fig)
    result={'status':'geometry_proposals_only', 'updated_utc':datetime.now(timezone.utc).isoformat(),
            'source_url':'https://zenodo.org/records/8105154', 'version':'1.1.0', 'license':'CC BY4.0',
            'paper':'https://www.frontiersin.org/journals/plant-science/articles/10.3389/fpls.2023.1251888/full',
            'labeling_provenance':'Author article describes visual plus laboratory annotation, two experienced supervisors. This is source provenance, not our repeated laboratory testing.',
            'limitations':['Black-satin background; not a complex-field benchmark','Physical leaf/session IDs not supplied; dorsal/ventral views and duplicates require grouping review','Exact pest species not supplied for generic MIT','Source-label confidence does not validate automated geometry'],
            'algorithm':{'kind':'CPU Lab distance from median edge background; largest connected component',
                         'lab_distance_min':25,'lightness_min':25,'preview_max_side':1024,
                         'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},
            'archives':archive_info, 'source_class_counts':dict(counts), 'images':rows,
            'geometry_samples_seen':False,'training_images_accepted':0,
            'sampled_geometry_preview_count':len(preview_rows), 'sample_sheets':(len(preview_rows)+15)//16}
    (OUT/'inventory.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'images':len(rows),'counts':dict(counts),'sample_sheets':result['sample_sheets'],'training_accepted':0}),flush=True)


if __name__=='__main__': main()
