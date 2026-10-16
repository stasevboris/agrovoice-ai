"""Require real installed APK bytes, full Android parity and ten current benchmark series."""
import argparse,os,subprocess,statistics
from datetime import datetime,timezone
from pathlib import Path
from yolo26m_validation import ROOT,FOCUS,ASSETS,read,save,sha,pilot_selection,final_selection,check_preserved
def main():
    p=argparse.ArgumentParser();p.add_argument('--serial',default='b861c4e0');p.add_argument('--final',action='store_true');p.add_argument('--evidence',default='docs/android/yolo26m_pilot_phone');args=p.parse_args()
    selected,base=final_selection() if args.final else pilot_selection()
    model=selected['model'];manifest=read(ASSETS/'models/manifest.json');check_preserved(manifest,base)
    assert next(x for x in manifest['models'] if x['id']=='yolo26m')==model
    current=read(ROOT/'artifacts/apk/current_model_lab.json')
    assert current['version_code']>11 and current['installed'] and current['status']=='verified' and current['phone_parity']=='passed' and current['serial']==args.serial
    assert sha(ROOT/current['apk'])==current['sha256']
    evidence=(ROOT/args.evidence).resolve();assert evidence.is_relative_to((ROOT/'docs/android').resolve())
    evidence_parity=read(evidence/'verification.json');assert evidence_parity['apk_sha256']==current['sha256'] and evidence_parity['status']=='passed'
    for name in ['camera_flow','gallery_flow','continuous_flow','controls_flow','inspection_tools','comparison_tools','note_speech_flow','tts_flow']:assert read(evidence/(name+'.json'))['status']=='passed'
    assert read(evidence/'comparison_tools.json')['modes']==9
    tools=read(evidence/'inspection_tools.json');assert tools['plot_and_search'] and tools['content_uri_export']
    assert read(evidence/'controls_flow.json')['models']==9
    assert 'OK (10 tests)' in (evidence/'instrumentation.txt').read_text(encoding='utf-8-sig')
    speech=read(evidence/'recognition_results.json')
    assert speech['accepted_commands']==speech['expected_commands'] and speech['false_actions_on_negative_or_unrelated']==0
    ninth=[x for x in speech['samples'] if x['sample']['id']=='model9']
    assert len(ninth)==2 and all(x['passed'] and x['sample']['value']=='yolo26m' for x in ninth)
    parity_file=ROOT/'docs/android/accuracy_v1/verification.json';parity=read(parity_file)
    assert parity['status']=='passed' and parity['apk_sha256']==current['sha256'] and set(parity['modes'])=={m['id'] for m in manifest['models']} and parity['results']==27
    assert any(x['mode']=='yolo26m' and x['graph_sha256']==model['sha256'] and x['stage']=='detector' for x in parity['comparisons'])
    folder=ROOT/'docs/android/accuracy_v1/benchmark';batch=read(folder/'latest_batch.json')
    assert batch['status']=='passed' and batch['series']==10 and batch['phone']==args.serial
    assert datetime.fromisoformat(batch['completed_utc'])>=datetime.fromisoformat(current['updated_utc']),'Timing predates this APK install'
    ids={m['id'] for m in manifest['models']};expected={m['id']:m for m in manifest['models']};seen=set();medians=[];rss=[];hwm=[]
    for filename in batch['files']:
        path=Path(filename).resolve();assert path.is_relative_to(folder.resolve())
        report=read(path);assert report['status']=='passed' and report['iterations']==10 and report['warmup']==3 and report['series'] not in seen
        seen.add(report['series']);assert {x['model'] for x in report['results']}==ids
        for row in report['results']:
            graph=expected[row['model']]
            assert row['seed']==graph['seed'] and row['detector_seed']==graph['detector_seed'] and row['detector_sha256']==graph['sha256']
            assert row.get('classifier_sha256')==graph.get('classifier',{}).get('sha256')
            assert len(row['pipeline_ms'])==10 and all(x>0 for x in row['pipeline_ms'])
            if row['model']=='yolo26m':
                medians.append(row['median_pipeline_ms']);rss.append(row['rss_bytes_after']);hwm.append(row['process_hwm_bytes'])
    assert seen==set(range(10)) and len(medians)==10
    # Confirm the actual phone holds these exact APK bytes, not merely the host pointer.
    adb=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platform-tools/adb.exe'
    def run(*parts):return subprocess.check_output([str(adb),'-s',args.serial,*parts],text=True,encoding='utf-8',stderr=subprocess.STDOUT).strip()
    assert run('get-state')=='device'
    paths=run('shell','pm','path','com.agronomy.phytomonitor').splitlines();assert len(paths)==1 and paths[0].startswith('package:') and paths[0].endswith('/base.apk')
    cache=ROOT/'ml-pipeline/.cache/phone_gate';cache.mkdir(parents=True,exist_ok=True)
    apk=cache/('installed_'+current['sha256']+'.apk')
    run('pull',paths[0].removeprefix('package:'),str(apk));assert sha(apk)==current['sha256']
    gate={'status':'passed','stage':selected['stage'],'plan_sha256':selected['plan_sha256'],'model_sha256':model['sha256'],'model_seed':model['seed'],
          'apk':current['apk'],'apk_sha256':current['sha256'],'actual_installed_apk_sha256':sha(apk),'serial':args.serial,
          'parity_evidence':str(parity_file.relative_to(ROOT)),'parity_sha256':sha(parity_file),'benchmark_batch_sha256':sha(folder/'latest_batch.json'),
          'voice_evidence':str(evidence.relative_to(ROOT)),'voice_commands_accepted':speech['accepted_commands'],'voice_model9_samples':2,
          'series':10,'pipeline_mean_series_median_ms':statistics.mean(medians),'pipeline_sd_series_median_ms':statistics.stdev(medians),
          'maximum_rss_bytes_after':max(rss),'maximum_process_hwm_bytes':max(hwm),
          'validated_utc':datetime.now(timezone.utc).isoformat(),'scope':'Actual Note7 execution/bytes/parity and10 warm bitmap series; not field diagnosis accuracy, long-term battery or A56/Redmi9T proof'}
    save(FOCUS/('final_phone_gate.json' if args.final else 'phone_gate.json'),gate);print(gate)
if __name__=='__main__':main()
