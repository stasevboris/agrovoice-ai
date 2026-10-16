"""Public, licensed real photos for manual phone trials; never alter study data."""
from __future__ import annotations
import hashlib
import html
import io
import json
import re
import urllib.parse
import urllib.request
import urllib.error
import time
from pathlib import Path
from datetime import datetime, timezone
from PIL import Image, ImageOps, ImageDraw
from prepare_supplemented import signature, near

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/field_photos'
HEADERS = {'User-Agent': 'AgroVoiceResearch/0.2 (public educational photo trial; attribution preserved)'}
SOURCES = [
    ('01_potato_late_blight.jpg', 'File:Potato Late Blight.JPG', 'Картофель: фитофтороз'),
    ('02_potato_late_blight_garden.jpg', 'File:Картопля фітофтороз IMG20220820102315.jpg', 'Картофель: фитофтороз, садовый снимок'),
    ('03_tomato_early_blight.jpg', 'File:Early blight on tomato leaves (7871930010).jpg', 'Томат: альтернариоз'),
    ('04_tomato_late_blight.jpg', 'File:Tomato late blight leaf sporulating lesions 1 (5816170621).jpg', 'Томат: фитофтороз'),
    ('05_tomato_septoria_2026.jpg', 'File:Tomato septoria leaf spot 3026.jpg', 'Томат: септориоз'),
    ('06_tomato_septoria_garden.jpg', 'File:Septoria leaf spot symptoms on tomato leaf (Septoria lycopersici on Solanum lycopersicum leaf).jpg', 'Томат: септориоз, садовый снимок'),
    ('07_tomato_leaf_mold.jpg', 'File:Tomato Leaf Mold.jpg', 'Томат: листовая плесень'),
    ('08_tomato_spider_mite.jpg', 'File:Solanum lycopersicum - spider mite -Clemson University - USDA.jpg', 'Томат: повреждение паутинным клещом'),
    ('09_cucumber_downy_mildew_2024.jpg', 'File:Downy mildew on leaves of Cucumis sativus.jpg', 'Огурец: пероноспороз (диагноз не поддерживается моделью)'),
    ('10_cucumber_powdery_mildew.jpg', 'File:Sphaerotheca fusca on Cucumber leaves.jpg', 'Огурец: мучнистая роса (диагноз не поддерживается моделью)'),
]

def get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code not in (429, 502, 503, 504) or attempt == 3:
                raise
            delay = max(30, int(error.headers.get('Retry-After', '30')))
            print('Server requested backoff:', error.code, delay, 'seconds', flush=True)
            # Short increments keep this worker easy to stop; respect Retry-After.
            for _ in range(delay):
                time.sleep(1)

def plain(value):
    return html.unescape(re.sub('<[^>]+>', '', value or '')).strip()

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    query = dict(action='query', format='json', prop='imageinfo', iiprop='url|size|sha1|extmetadata',
                 titles='|'.join(row[1] for row in SOURCES))
    cache = OUT/'commons_metadata.json'
    if cache.exists():
        metadata = json.loads(cache.read_text(encoding='utf-8'))
    else:
        metadata = json.loads(get('https://commons.wikimedia.org/w/api.php?' + urllib.parse.urlencode(query)))
        cache.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
    pages = {page['title']: page for page in metadata['query']['pages'].values()}
    frozen = json.loads((ROOT/'ml-pipeline/dataset_clean_v2/manifest.json').read_text(encoding='utf-8'))
    rows = []
    for filename, title, expected in SOURCES:
        page = pages[title]
        info = page['imageinfo'][0]
        meta = info['extmetadata']
        field = lambda key: plain(meta.get(key, {}).get('value', ''))
        license_name = field('LicenseShortName')
        assert any(mark in license_name for mark in ('CC BY', 'CC0', 'Public domain')), (title, license_name)
        path = OUT/filename
        if not path.exists():
            payload = get(info['url'])
            assert hashlib.sha1(payload).hexdigest() == info['sha1'], 'Published checksum mismatch'
            path.write_bytes(payload)
            time.sleep(4)
        payload = path.read_bytes()
        assert hashlib.sha1(payload).hexdigest() == info['sha1']
        with Image.open(io.BytesIO(payload)) as source:
            image = ImageOps.exif_transpose(source).convert('RGB')
            sig = signature(image)
        matches = [{'file': r['filename'], 'split': r['split'],
                    'exact_decoded': sig['decoded_hash'] == r['source_signature']['decoded_hash']}
                   for r in frozen if near(sig, r['source_signature'])]
        row = dict(filename=filename, title=title, source_url=info['descriptionurl'], image_url=info['url'],
                   author=field('Artist'), license=license_name, license_url=field('LicenseUrl'),
                   source=field('Credit'), date=field('DateTimeOriginal'),
                   source_claim_ru=expected, width=image.width, height=image.height, bytes=len(payload),
                   sha256=hashlib.sha256(payload).hexdigest(), changes='None: original bytes downloaded',
                   possible_study_overlap=matches,
                   interpretation='Source-provided condition, not independently confirmed; manual example, not a test metric')
        rows.append(row)
        print(filename, license_name, len(payload), 'possible_overlap', len(matches), flush=True)
    manifest = dict(created_utc=datetime.now(timezone.utc).isoformat(), images=rows,
                    scope='Real photographs by people, licensed originals. Not training data or an accuracy benchmark.',
                    overlap_check='Exact decoded SHA-256 plus matching aspect within 3%, pHash<=4 and dHash<=4 against all clean-v2 source signatures; absence does not prove independence.')
    (OUT/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['AgroVoice: фотографии для ручной проверки', '',
             'Состояние указано по подписи автора; это не экспертная разметка.',
             'Огуречные болезни текущие модели не различают. Фотографии не добавлены в обучение.',
             'Проверка похожих снимков не доказывает независимость от PlantDoc; по ним не считаем точность.', '']
    for row in rows:
        lines.extend([row['filename']+' — '+row['source_claim_ru'],
                      'Автор: '+row['author'], 'Источник: '+row['source_url'],
                      'Лицензия: '+row['license']+' '+row['license_url'],
                      'Изменения: нет, оригинальный файл.',
                      'Возможные совпадения с clean v2: '+str(row['possible_study_overlap']), ''])
    (OUT/'ATTRIBUTION.txt').write_text('\n'.join(lines), encoding='utf-8')
    contact = Image.new('RGB', (1000, ((len(rows)+3)//4)*230), 'white')
    draw = ImageDraw.Draw(contact)
    for index, row in enumerate(rows):
        with Image.open(OUT/row['filename']) as source:
            thumb = ImageOps.exif_transpose(source).convert('RGB'); thumb.thumbnail((240,200))
        x=(index%4)*250; y=(index//4)*230
        contact.paste(thumb, (x,y)); draw.text((x,y+203),row['filename'][:32],fill='black')
    contact.save(OUT/'contact_sheet.jpg')
    print('Complete:', len(rows), 'photos')

if __name__ == '__main__':
    main()
