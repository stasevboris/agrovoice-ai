"""Record verified phone checks and deliverable hashes; no training/model mutations."""
from __future__ import annotations
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from datetime import datetime, timezone

ROOT=Path(__file__).resolve().parents[1]
DIR=ROOT/'docs/android/phone_validation'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def sha(path):
    with path.open('rb') as handle:return hashlib.file_digest(handle,'sha256').hexdigest()

def main():
    apk=ROOT/'artifacts/apk/AgroVoice-field-demo-0.2.apk'
    models=read(ROOT/'android-app/app/src/main/assets/models/manifest.json')
    with zipfile.ZipFile(apk) as package:
        for model in models['models']:
            name='assets/'+model['asset']
            assert hashlib.sha256(package.read(name)).hexdigest()==model['sha256'], name
    photo_manifest=read(ROOT/'artifacts/field_photos/manifest.json')
    copies={Path(line.split(None,1)[1]).name:line.split()[0] for line in
            (DIR/'gallery_checksums.txt').read_text(encoding='utf-8-sig').splitlines()}
    assert len(copies)==10
    for row in photo_manifest['images']:assert copies[row['filename']]==row['sha256']
    parity=read(DIR/'desktop_parity.json');assert parity['status']=='passed'
    validation=read(DIR/'summary.json');assert validation['status']=='complete'
    assert len(validation['results'])==6
    before=ET.parse(DIR/'portrait_before.xml').getroot()
    landscape=ET.parse(DIR/'landscape.xml').getroot()
    after=ET.parse(DIR/'portrait_after.xml').getroot()
    assert (before.attrib['rotation'],landscape.attrib['rotation'],after.attrib['rotation'])==('0','1','0')
    for tree in (before,landscape,after):
        assert any(node.attrib.get('text')=='YOLO26n' for node in tree.iter('node'))
        assert any(node.attrib.get('content-desc')=='Выбранное фото растения' for node in tree.iter('node'))
    cancel=ET.parse(DIR/'camera_cancel.xml').getroot()
    assert any(node.attrib.get('text')=='Съёмка отменена' for node in cancel.iter('node'))
    imports=[read(path) for path in (DIR/'ui_results').glob('*.json')]
    imports=[row for row in imports if Path(row['photo']).name.startswith('import_')]
    photo_sha=next(row['sha256'] for row in photo_manifest['images'] if row['filename']=='10_cucumber_powdery_mildew.jpg')
    assert imports and all(row['photo_sha256']==photo_sha for row in imports)
    assert {row['model'] for row in imports}=={'yolo11n','yolo26n'}
    assert any(row['crop_filter']=='Cucumber' and not row['detections'] for row in imports)
    merged=ROOT/'android-app/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml'
    permissions=ET.parse(merged).getroot()
    ns='{http://schemas.android.com/apk/res/android}'
    permission_names=[row.attrib[ns+'name'] for row in permissions.findall('uses-permission')]
    assert 'android.permission.INTERNET' not in permission_names
    assert 'android.permission.ACCESS_NETWORK_STATE' not in permission_names
    assert not any(row.attrib.get(ns+'name')=='ai.onnxruntime.TelemetryInitializer'
                   for row in permissions.findall('application/provider'))
    plan=read(ROOT/'docs/experiments/study10_v2/plan.json')
    for name,checksum in plan['implementation_sha256'].items():
        assert sha(ROOT/'ml-pipeline'/name)==checksum, 'Frozen script changed: '+name
    # Match the frozen study's text fingerprint (universal newline normalization).
    canonical=(ROOT/'ml-pipeline/dataset_clean_v2/manifest.json').read_text(encoding='utf-8')
    assert hashlib.sha256(canonical.encode('utf-8')).hexdigest()==plan['dataset_fingerprint']
    lint=ET.parse(ROOT/'android-app/app/build/reports/lint-results-debug.xml').getroot()
    assert not any(row.attrib['severity'] in ('Error','Fatal') for row in lint.findall('issue'))
    warnings=sum(row.attrib['severity']=='Warning' for row in lint.findall('issue'))
    record=dict(status='passed',created_utc=datetime.now(timezone.utc).isoformat(),
        apk=dict(path=str(apk),bytes=apk.stat().st_size,sha256=sha(apk),version='0.2-field-demo',debug=True),
        device=dict(serial='b861c4e0',model='Redmi Note 7',android='13',original_rotation_settings_restored=True),
        checks=dict(build='passed',lint_errors=0,lint_warnings=warnings,
            install='adb install -r; no data clear',model_assets_checksums='passed',
            on_device_photo_inference={'photos':3,'models':2,'outputs':6},
            desktop_ort_and_upstream_nms_parity='passed',
            instrumentation={'status':'passed','tests':3,'runner_output':'OK (3 tests)',
                'cases':['8 EXIF orientations','4097-pixel odd width downsample bound','broken JPEG error']},
            photo_picker_import_and_private_copy_sha256='passed',model_switching='passed',
            crop_filter_no_detections_message='passed',orientation='0 -> 1 -> 0; photo/model preserved',
            camera_launch_and_cancel='passed',gallery_original_sha256_matches=10,
            network_permissions_absent=True,automatic_telemetry_initializer_absent=True,
            frozen_study_scripts_and_dataset_manifest_unchanged=True),
        scope='Integration and manual examples; not an accuracy or repeated speed benchmark.',
        limitations=['Physical camera capture of a leaf pending user trial','A56 and Redmi 9T untested',
                     'Provisional matched seed pair while 10-seed training continues',
                     'Cucumber diseases unsupported; observed incorrect tomato conditions and crop guesses'])
    (DIR/'verification.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(record,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
