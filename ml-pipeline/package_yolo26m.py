"""Add real M only after its pilot or final guards, preserving the locked eight-mode base."""
import argparse,copy
from datetime import datetime,timezone
from yolo26m_validation import ROOT,ASSETS,FOCUS,S_FOCUS,read,save,sha,pilot_selection,final_selection,check_preserved
from package_accuracy_models import graph

def main():
    p=argparse.ArgumentParser();p.add_argument('--pilot',action='store_true');args=p.parse_args()
    selected,base=pilot_selection() if args.pilot else final_selection()
    current=read(ASSETS/'models/manifest.json');check_preserved(current,base)
    model=selected['model']
    lock=FOCUS/('pilot_selection.json' if args.pilot else 'validation_selection.json')
    if lock.exists():assert read(lock)['selection']==selected,'M selection already locked'
    else:save(lock,{'selection':selected,'locked_utc':datetime.now(timezone.utc).isoformat()})
    base_lock=FOCUS/'base_manifest_lock.json'
    if not base_lock.exists():save(base_lock,{'sha256':sha(S_FOCUS/'apk_model_manifest.json'),'manifest':base})
    # Every refusal above precedes all graph/manifest mutation.
    target=ASSETS/model['asset']
    target.parent.mkdir(parents=True,exist_ok=True)
    graph(ROOT/model['source']/'model.onnx',target,model['sha256'])
    manifest=copy.deepcopy(base);manifest['models'].append(model)
    manifest.update(updated_utc=datetime.now(timezone.utc).isoformat(),active_study='yolo26m_v1',
                    medium_selection={'stage':selected['stage'],'complete_m_seeds':len(selected['cohort']),'base_modes':8,'selection_lock':str(lock.relative_to(ROOT))})
    check_preserved(manifest,base);save(ASSETS/'models/manifest.json',manifest)
    save(FOCUS/('pilot_apk_model_manifest.json' if args.pilot else 'apk_model_manifest.json'),manifest)
    print({'M_seed':model['seed'],'stage':selected['stage'],'modes':len(manifest['models']),
           'next':'Add model9 voice/label, bumpAPK, actualNote7 parity and10 fresh-process bitmap timings before phone_gate'})
if __name__=='__main__':main()
