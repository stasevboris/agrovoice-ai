"""Final historical test under the explicit ten-S scope; original 80-job guard unchanged."""
from datetime import datetime, timezone
from pathlib import Path
from yolo26_focus_validation import ASSETS, CORE_SHA, FOCUS, ROOT, read, sha, save, cohort_selection, plan_and_examples, check_preserved_models


def main():
    selection = cohort_selection()  # Refuse unfinished cohorts before importing the evaluator.
    _, examples = plan_and_examples()
    assert read(FOCUS / 'validation_selection.json')['selection'] == selection
    manifest = read(ASSETS / 'models/manifest.json')
    check_preserved_models(manifest, examples, selection['models']['yolo26s'])
    for model in selection['models'].values():
        assert sha(ASSETS / model['asset']) == model['sha256']
    lock = FOCUS / 'final_test_selection.json'
    if lock.exists():
        assert read(lock)['selection'] == selection, 'Test weights/modes are locked; do not retune'
    else:
        save(lock, {'selection': selection, 'locked_utc': datetime.now(timezone.utc).isoformat()})
    from evaluate_accuracy_pipeline import evaluate
    folder = FOCUS / 'final_test'
    folder.mkdir(exist_ok=True)
    for kind, model in selection['models'].items():
        path = folder / f'{kind}_test.json'
        if path.exists():
            previous = read(path)
            assert previous['model'] == model and previous['code_sha256'] == CORE_SHA
            assert previous['selection_lock_sha256'] == sha(lock)
            print('REUSE ' + path.name, flush=True)
            continue
        result = evaluate(model, 'test')
        assert not result['partial'] and result['images'] == 236
        result.update(code_sha256=CORE_SHA, selection_lock_sha256=sha(lock), data_sha256=selection['data_sha256'],
                      launcher_sha256=sha(Path(__file__)),
                      note='One selected S and one fixed N checkpoint; not ten-seed test mean. Historical test already seen. Fresh external field control still required; absent classes unassessed.')
        save(path, result)
        print({'mode': kind, 'seed': model['seed'], 'test': result['disease_detection']}, flush=True)


if __name__ == '__main__':
    main()
