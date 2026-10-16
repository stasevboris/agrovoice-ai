"""Replace only YOLO26s after ten full-val evaluations; retain all APK0.8 examples."""
import copy
from datetime import datetime, timezone
from package_accuracy_models import graph
from yolo26_focus_validation import ASSETS, FOCUS, ROOT, read, save, cohort_selection, plan_and_examples, check_preserved_models


def main():
    selection = cohort_selection()  # No asset writes before the complete-cohort guard.
    _, examples = plan_and_examples()
    manifest = read(ASSETS / 'models/manifest.json')
    check_preserved_models(manifest, examples)
    lock = FOCUS / 'validation_selection.json'
    if lock.exists():
        assert read(lock)['selection'] == selection, 'Val selection already locked'
    else:
        save(lock, {'selection': selection, 'locked_utc': datetime.now(timezone.utc).isoformat()})
    selected = selection['models']['yolo26s']
    graph(ROOT / selected['source'] / 'model.onnx', ASSETS / selected['asset'], selected['sha256'])
    manifest = copy.deepcopy(examples)
    manifest['models'] = [selected if m['id'] == 'yolo26s' else m for m in manifest['models']]
    manifest.update(updated_utc=datetime.now(timezone.utc).isoformat(), active_study='yolo26s_focus_v1',
                    focus_selection={'models': ['yolo26n', 'yolo26s'], 'complete_s_seeds': 10,
                                     'examples_preserved_from': 'APK0.8', 'validation_lock': str(lock.relative_to(ROOT))})
    check_preserved_models(manifest, examples, selected)
    save(ASSETS / 'models/manifest.json', manifest)
    save(FOCUS / 'apk_model_manifest.json', manifest)
    print({'packaged_s_seed': selected['seed'], 'fixed_n_seed': 20261012, 'preserved_modes': len(manifest['models'])})


if __name__ == '__main__':
    main()
