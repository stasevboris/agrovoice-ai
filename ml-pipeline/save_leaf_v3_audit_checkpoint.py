"""Synchronize the already completed CPU audits; does not run an audit or training."""
from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import tomllib
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = ROOT / 'docs/experiments/yolo26s_data_v1'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def append_once(path, marker, body):
    text = path.read_text(encoding='utf-8')
    if marker not in text:
        path.write_text(text.rstrip() + '\n\n' + body.strip() + '\n', encoding='utf-8')


def main():
    now = datetime.now(timezone.utc).isoformat()
    overlap = read(BASE / 'candidate_overlap_full_r3/status.json')
    groups = read(BASE / 'group_closure_r1/status.json')
    visual = read(BASE / 'legacy_overlap_visual_r1/visual_adjudication.json')
    assert overlap['counts']['candidate_records'] == 55992
    assert overlap['counts']['incremental_checked'] == 15611
    assert overlap['counts']['additional_links'] == 0
    assert groups['counts']['candidate_records'] == 55992
    assert visual['status'] == 'all_37_pairs_visually_reviewed'
    assert visual['stats'] == {'same_source_photo_variants': 37, 'cross_split_pairs': 9, 'label_set_disagreement_pairs': 19}
    for folder, status in [('candidate_overlap_full_r3', overlap), ('group_closure_r1', groups)]:
        for filename, expected in status['output_sha256'].items():
            assert sha(BASE / folder / filename) == expected, (folder, filename)
    plan = read(BASE / 'plan.draft.json')
    for filename, expected in plan['protected_sources'].items():
        assert sha(ROOT / 'ml-pipeline' / filename) == expected, filename
    assert sha(ROOT / 'ml-pipeline/dataset_clean_v2/manifest.json') == '6eeb02546dfe35f48cd6a8f37931b325e74b8c338e62c0c18708e3784832abfe'
    ps = subprocess.run(['powershell', '-NoProfile', '-Command',
        "@(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('python.exe','pythonw.exe','wsl.exe') } | Select-Object ProcessId,CreationDate,Name,CommandLine) | ConvertTo-Json -Depth 3 -Compress"],
        check=True, capture_output=True, text=True)
    processes = json.loads(ps.stdout) if ps.stdout.strip() else []
    if isinstance(processes, dict):
        processes = [processes]
    process_ids = {p['ProcessId'] for p in processes}
    process_observation = {'checked_utc': now, 'processes': processes,
        'note': 'Includes this checkpoint Python process; PID alone never establishes an old worker identity.'}
    write(ROOT / 'docs/session/leaf_v3_process_observation.json', process_observation)
    active_path = ROOT / 'docs/session/active_olid_geometry.json'
    active = read(active_path)
    geometry = read(ROOT / active['status_path'])
    assert 'completed' in geometry['status'] or 'complete' in geometry['status'], geometry['status']
    assert active['pid'] not in process_ids and active['worker_pid'] not in process_ids
    active.update(status='completed', process_running=False, observed_utc=now,
                  authoritative_status=geometry['status'], observation='Original launcher/worker PIDs absent; completed source status read. Do not rerun.')
    write(active_path, active)

    next_steps = [
        'Use candidate_overlap_full_r3/candidate_disposition.jsonl and group_closure_r1/candidate_split_constraints.jsonl in the new manifest; neither is an accepted training dataset',
        'Keep all ten OLID conflicting-label exact copies quarantined; original archive bytes were verified identical in each pair',
        'Keep the 71 historical images in legacy_overlap_visual_r1/visual_adjudication.json quarantined for the NEW base pending object/label review; do not modify frozen old data',
        'Complete source-backed class semantics and whole-visible-leaf boxes for PlantVillage and cucumber; resolve geometry flags and unknown capture groups',
        'Historical train-family candidates (1781) can only be training if all other checks pass; 447 historical holdout-family candidates are never new train or independent test',
        'OLID capture dates are confounded with crop/state; do not use date-only split as all-class independent holdout',
        'Build all-crop schema/provenance manifest/grouped independent val/test, freeze recipe, pass preflight and show Boris actual source counts before GPU',
        'Then run exactly two sequential YOLO26s seeds 20261003/20261004, max60 epochs/patience15; no automatic third pilot or M restart',
    ]
    budget = {'checked_utc': '2026-10-05T12:25:16+00:00', 'five_hour_used_percent': 34,
              'five_hour_remaining_percent': 66, 'weekly_used_percent': 99, 'weekly_remaining_percent': 1,
              'ordinary_usage_allowed': True, 'source': 'actual get_usage_limits',
              'token_balance_available': False, 'user_reserve_cancelled': True}
    progress = {'overlap_r3': {'status': overlap['status'], 'counts': overlap['counts'],
        'ledger': 'docs/experiments/yolo26s_data_v1/candidate_overlap_full_r3/candidate_disposition.jsonl'},
        'group_closure_r1': {'status': groups['status'], 'counts': groups['counts'],
        'constraints': 'docs/experiments/yolo26s_data_v1/group_closure_r1/candidate_split_constraints.jsonl'},
        'legacy_overlap_visual_r1': {'status': visual['status'], 'stats': visual['stats'],
        'unique_historical_records': 71, 'records_in_cross_split_pairs': 18,
        'adjudication': 'docs/experiments/yolo26s_data_v1/legacy_overlap_visual_r1/visual_adjudication.json'},
        'new_training_accepted': 0, 'new_gpu_runs': 0}
    for rel in ['docs/session/yolo26s_data_work_state.json', 'docs/session/accuracy_work_state.json']:
        path = ROOT / rel
        state = read(path)
        state.update(updated_utc=now, next=next_steps, remaining_percent=66,
                     weekly_remaining_percent=1, budget_check_utc=budget['checked_utc'],
                     last_budget_check_utc=budget['checked_utc'], actual_budget_observation=budget,
                     resume_not_before_local=None, budget_stop_remaining_percent=None,
                     continuation_checkpoint='docs/session/CONTINUATION_CHECKPOINT_2026-10-05.md')
        if 'progress' in state:
            state['progress'].update(progress)
            state['progress']['full_overlap_candidates'] = 55992
            state['progress']['full_overlap_r3_complete'] = True
        else:
            state['data_followup'].update(progress)
            state['data_followup']['latest_overlap_review'] = progress['overlap_r3']['ledger']
            state['automation_instruction_utf8'] = 'Current prompt update prepared: documents complete, r3/group closure/37 visual pairs complete, M stopped, exactly two S only after ready data.'
            state['last_heartbeat_check'].update(updated_utc=now, M_worker_running=False,
                action='Completed extra9 incremental overlap, row-level disposition, group closure, all37 historical visual comparisons; train accepted0/GPU0')
            state['last_known_remaining_percent'] = 66
            state['last_known_weekly_remaining_percent'] = 1
        if 'budget_policy_override' in state:
            state['budget_policy_override'].update(observed_five_hour_remaining_percent=66,
                observed_weekly_remaining_percent=1, check_utc=budget['checked_utc'])
        write(path, state)

    addendum = '''# Ограничения независимости исторических оценок — 5 октября 2026

Все 37 близких пар из старого PlantDoc просмотрены на десяти листах: это версии одних исходных фотографий, отличающиеся сжатием, размером или надписями. Девять пар (18 записей) пересекают исторические разделы, включая train↔val и train↔test. Всего просмотр затрагивает 71 уникальное историческое изображение. Поэтому старые val/test нельзя считать полностью независимым доказательством обобщения на новые растения.

В 19 парах различаются наборы классов. Это требует проверки конкретных объектов и исходной разметки: различие набора классов не означает автоматически неверный диагноз каждого объекта. Встречаются также противоречия культуры и состояния. Диагнозы по внешнему виду не назначались, автоматическое голосование меток не использовалось.

Числа старых оценок и их исходные файлы сохранены. Они остаются историческими измерениями на прежнем разбиении; они не заменяют новый независимый test. Защищённые train/eval и замороженные данные не изменены, повторной настройки по test не было.

Для НОВОЙ базы все 71 изображения временно помещены в карантин до проверки рамок/меток. Если временно исключить затронутые train-изображения, ненулевая поддержка остаётся у всех 29 исходных классов (это не готовое новое разбиение).

Источник решений: [visual_adjudication.json](legacy_overlap_visual_r1/visual_adjudication.json). Групповые ограничения: [candidate_split_constraints.jsonl](group_closure_r1/candidate_split_constraints.jsonl). Ни один новый пример этим аудитом не принят в обучение.
'''
    addendum_path = BASE / 'HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md'
    addendum_path.write_text(addendum, encoding='utf-8')
    for rel, link in [
        ('docs/history/reports/07_YOLO26_FOCUS_2026-10-04.md', '../../experiments/yolo26s_data_v1/HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md'),
        ('docs/history/reports/08_YOLO26M_PILOT_2026-10-05.md', '../../experiments/yolo26s_data_v1/HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md'),
        ('docs/experiments/study10_v2/FINAL_REPORT_2026-10-04.md', '../yolo26s_data_v1/HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md'),
    ]:
        path = ROOT / rel
        if path.exists():
            text = path.read_text(encoding='utf-8')
            if 'HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md' not in text:
                first, rest = text.split('\n', 1)
                note = f'\n\n**Уточнение 5 октября:** визуально подтверждены версии одинаковых фотографий между прежними train и val/test. Числа ниже сохраняются как исторические; независимость оценки ограничена. [Доказательства и ограничения]({link}).\n'
                path.write_text(first + note + '\n' + rest, encoding='utf-8')
    current = '''## Полный аудит r3 и ограничения групп — 5 октября, 12:25 UTC

Проверены все 55 992 новых кандидата против 3 354 исторических записей. Дополнительные 15 611 PlantVillage-оригиналов девяти категорий проверены инкрементально: новых совпадений по прежнему правилу не найдено; 857 ранее сохранённых связей унаследованы без изменения байтов. Это не исключает пропущенные повороты, обрезки или другие стороны одного листа.

Создан настоящий построчный [реестр 55 992 кандидатов](candidate_overlap_full_r3/candidate_disposition.jsonl): 792 точные исторические копии; 46 записей с внутренними совпадениями требуют дедупликации; 10 записей пяти конфликтных пар OLID в карантине; 55 144 без совпадений по этому правилу всё ещё требуют проверки семантики, рамок и групп. Предыдущий REVIEW_DECISIONS r2 содержал сводные числа, а не полный построчный реестр; текущий реестр r3 устраняет этот пробел. Все пять пар OLID сверены с оригинальными ZIP: JPEG-байты внутри каждой пары также идентичны, метки противоречат.

Авторский leaf-map проверен по закреплённому архиву. Связи физических листьев, копий и временных групп распространены транзитивно: [реестр ограничений](group_closure_r1/candidate_split_constraints.jsonl). 1 781 кандидат связан с историческими train-семействами и пригоден только для train после остальных проверок; 447 связаны с историческими контрольными семействами и не допускаются в новый train или независимый test. Это ограничения групп, а не 2 228 независимых растений. Остальные неизвестные группы не объявлены независимыми.

Все 37 исторических близких пар реально просмотрены: одинаковые исходные фотографии, девять пар между разделами и 19 пар с различием наборов классов. [Дополнение об ограничениях прежних метрик](HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md). Старые данные/оценки сохранены; 71 затронутое изображение временно исключается только из проекта новой базы до проверки разметки. Поддержка всех 29 исходных классов при таком исключении остаётся ненулевой.

Все новые CPU-аудиты завершены и проверены SHA; не запускать повторно без обнаруженной ошибки. Новых принятых train-примеров0, GPU-запусков0. Остаются достоверные соответствия состояний, рамки видимого листа, неизвестные физические группы и независимые val/test. Оба DOCX завершены до этого аудита и не пересобираются; новые исходники и результаты хранятся отдельно с SHA и точкой продолжения.
'''
    for name in ['REPORT.md', 'SOURCE_AUDIT_REPORT_2026-10-05.md']:
        path = BASE / name
        if name.startswith('SOURCE_'):
            text = path.read_text(encoding='utf-8').replace('Full pair-level decisions are in [the review JSON]', 'Aggregate decisions (not a full row ledger) are in [the review JSON]')
            path.write_text(text, encoding='utf-8')
        append_once(path, '## Полный аудит r3 и ограничения групп', current)
    protocol_path = BASE / 'PROTOCOL_DRAFT.md'
    protocol = protocol_path.read_text(encoding='utf-8')
    protocol = protocol.replace('Резерв пользователя: при остатке ≤15% пятичасового окна сохранить точку и ждать 01:00 следующего дня Europe/Minsk; недельный лимит также сохранять.',
        'Борис прямым сообщением 5 октября отменил резерв15% и обязательное ожидание до01:00; работать при фактически доступной квоте, сохранять воспроизводимую точку продолжения и не придумывать остаток токенов.')
    protocol_path.write_text(protocol, encoding='utf-8')
    append_once(ROOT / 'PROJECT_GUIDE.md', '### 101. 5 октября: все дополнительные категории и транзитивные ограничения',
        '''### 101. 5 октября: все дополнительные категории и транзитивные ограничения

Завершён инкрементальный overlap_r3: все55992 кандидата/3354 исторических;15611extra не дали новых совпадений по фиксированному правилу. Сохранён построчный реестр, прежние857 связей неизменны. По проверенному авторскому leaf-map и связям копий1781 кандидат ограничен train-семейством,447 — историческим holdout-семейством;10 конфликтных OLID-записей в карантине. Это не готовые split/диагнозы. Все37 исторических near-пар визуально просмотрены и являются версиями одинаковых фото:9 пересекают разделы,19 различаются набором классов. Старые метрики получили явное дополнение об ограничениях независимости; числа/frozen source неизменны. Новыхtrain0/GPU0. ОбаDOCX полностью готовы, повторнаяQA не нужна. Точное продолжение: [контрольная точка](docs/session/CONTINUATION_CHECKPOINT_2026-10-05.md).''')
    checkpoint = f'''# Контрольная точка AgroVoice — {now}

## Готовые документы

Исходное описание обновлено: `C:/Users/Boris/Downloads/Diplom_AgroVoice_Opisanie_Temy_i_Steka_2026-09-22.docx`, SHA31fa051053265f89bcfa4261cb221067699b3c02dbf8ff4be6320af7f94c02d5. Резерв: `artifacts/documents/2026-10-05/original_2026-09-22.docx`. История: `artifacts/documents/2026-10-05/AgroVoice_Polnaya_Istoriya_Razrabotki_2026-10-05.docx`, SHA54f5bbb348da6cec3082a200e2c7da52b1d1350cc8ab22a51ec0dc78f950b689. Все791 страницы просмотрены; все276 встроенных исходников проверены. Не пересобирать или повторять QA без конкретной ошибки. Snapshot документов заканчивается07:13:48UTC; более поздние аудиты ниже хранятся отдельно.

## Завершённые CPU-этапы — не повторять

- PlantVillage54305 оригинала/38 категорий из уже проверенного ZIP;38694 прежних29 +15611extra9. Геометрия готова как аудит, не принятие train.
- OLID1047 геометрических решений,938 captureEXIF; датированный split не обеспечивает все классы. Пять конфликтных пар JPEG идентичны байтами, все10 записей в карантине.
- `candidate_overlap_full_r3/status.json`:55992 кандидата +3354 история,857 унаследованных связей,0 новых дляextra9. Полный55992-строчный ledger: `candidate_overlap_full_r3/candidate_disposition.jsonl`. R2 REVIEW_DECISIONS — сводка, не построчный список.
- `group_closure_r1/status.json`: авторскийleaf-map совпадает с закреплённым архивом;1781 train-family only,447 holdout-family never-new-train/test,10 конфликтных. Реестр: `group_closure_r1/candidate_split_constraints.jsonl`. Ограничения групп не подтверждают физическую независимость всех остальных.
- `legacy_overlap_visual_r1/visual_adjudication.json`: просмотрены10 листов/37 пар; всеsamephoto,9crosssplit,19classsetdifferent,71уникальная старая запись. Для НОВОЙ базы временный карантин71; старое разбиение неизменно. Все29 исходных классов сохраняют ненулевую train-поддержку при таком временном исключении. См. `HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md`.
- SHA выходовr3/group closure,7 защищённых train/eval и старого manifest повторно проверены при сохранении этой точки.

Все пути аудитов выше относительно `docs/experiments/yolo26s_data_v1/`. Уже выполненные скрипты: `ml-pipeline/audit_leaf_v3_overlap_incremental.py`, `audit_leaf_v3_group_closure.py`, `render_leaf_v3_legacy_overlap_review.py`. Скрипт синхронизации `save_leaf_v3_audit_checkpoint.py` не обучает и не повторяет аудит.

## Обучение и процессы

M штатно остановлена по решению Бориса: M03/M04 завершены, M05epoch53 `last_complete.pt`175386287байт/SHA d6e5010b840b3d354755bab39ff326b49941ad53726f2450720a6d25627d13a8 сохранён. `queue_status.json` авторитетныйstopped. `active_training.json` старый указатель; PID без имени/времени создания не использовать как доказательство живого worker. Не запускать Start-Yolo26mStudy/пилот.

Новых принятыхtrain0/GPU0. Процессы записаны в `docs/session/leaf_v3_process_observation.json`; старые OLID launcher/worker отсутствуют и указатель синхронизирован с completed. Не запускать вторую нагрузку без свежей проверкиPID/creation/status/logs.

## Следующие действия по порядку

''' + '\n'.join(f'{i}. {s}' for i, s in enumerate(next_steps, 1)) + '''

Новая S — отдельная схема со всеми исходнымиID0..28/всеми культурами, достоверными добавленными состояниями; старый unknown29 не конкурирует сhealthy. Только после готовых данных/lock/preflight и сообщения состава: два seed20261003/04,416/b16/FP32/AdamWlr0.001/max60/patience15 на существующейRX9060XT;60 — предел, не доказанный оптимум. Затем обеval/кривые/выбор до новогоtest/экспорт, корректный расширенный evaluator и новый отдельныйAPK-режим при сохранении9исторических примеров и функций. Телефон/оборудование/публикацию не трогать сейчас.

## Лимит и автоматика

Actual get_usage_limits12:25:16UTC: использовано34% пятичасового/99% недельного, осталось66%/около1%; ordinaryUsageAllowed=true. Борис отменил15%резерв/ожидание; следующему запуску заново читать фактическую квоту. Инструмент не показывает токены, поэтому требование «100к осталось» нельзя сопоставить с процентами; reproducible checkpoint сохранён заранее. АвтоматикаACTIVE до полного нового этапа, не приостанавливать из-за DOCX или одного аудита.
'''
    (ROOT / 'docs/session/CONTINUATION_CHECKPOINT_2026-10-05.md').write_text(checkpoint, encoding='utf-8')

    auto = tomllib.loads(pathlib.Path('C:/Users/Boris/.codex/automations/agrovoice/automation.toml').read_text(encoding='utf-8'))
    prompt = auto['prompt']
    cutoff = prompt.find('Актуальное прямое решение Бориса5октября08:00UTC:')
    if cutoff >= 0:
        prompt = prompt[:cutoff].rstrip()
    prompt += '''

Контрольная точка 5 октября12:25UTC: docs/session/CONTINUATION_CHECKPOINT_2026-10-05.md. Борис отменил15%резерв/ночное ожидание и разрешил всю доступную квоту. Actual12:25UTC:66% пятичасового/около1% недельного; заново get_usage_limits, токены из процентов не угадывать. Сохранять воспроизводимые промежуточные результаты постоянно.

ОбаDOCX полностью завершены: исходник Downloads обновлён, backup сохранён; история791/791страниц/132листа реально просмотрены, все276 встроенных источниковSHA/CRC прошли. document_work_state complete и FINAL_QA_REPORT. Не повторять QA/сборку без конкретного дефекта. Snapshot07:13:48UTC; последующие аудиты отдельно.

CPU-аудиты завершены: PlantVillage54305/38категорий (38694+15611), OLID1047геометрических решений/938EXIF. overlap_full_r3 теперь включает все55992кандидата+3354historical;extra15611 новых совпадений0,857унаследованных связей неизменны. candidate_disposition.jsonl — реальный построчный55992реестр; прежнийr2REVIEW_DECISIONS только сводка. ПятьOLIDконфликтных пар проверены JPEG-байтами,10записей остаютсяquarantine. group_closure_r1:1781trainfamilyonlyifaccepted/447historicalholdoutfamilyneverthelessnotnewtrain/test/10conflicts. Leaf-map байты совпали сZIP. legacy_overlap_visual_r1:все37пар реально просмотрены,одинаковыефото/9crosssplit/19classsetdisagreement/71историческоеизображение. Для НОВОЙ базы71карантин доannotationreview; старыеfrozenданные/числа неизменны, отчёты получили HISTORICAL_OVERLAP_ADDENDUM. Не повторять этиCPU-скрипты/загрузки/просмотр без ошибки.

Принятыхновыхtrain0/GPU0. Остаются источниковаясемантика/wholevisibleleafboxes/unknownphysicalgroups/independentsplits всехкультур. Mостановлена, не возобновлять. После готовыхданных/lock/preflight/сообщённыхБорису реальныхколичеств выполнить только2Sseed20261003/04, затем корректнаяval/выбордоtest/экспорт/проверенныйотдельныйAPKрежим со своейсхемой при сохранении9старых. АвтоматикунеPAUSEиз-заDOCX/бюджета; окончание по всему согласованномуэтапу. Тихо при неизменном не требующемдействийсостоянии; уведомлять толькоозначимомрезультате/ошибке/решении.
'''
    payload = {'mode': 'update', 'id': auto['id'], 'kind': auto['kind'], 'name': auto['name'],
               'prompt': prompt, 'rrule': auto['rrule'], 'status': auto['status'],
               'targetThreadId': auto['target_thread_id']}
    write(ROOT / 'docs/session/agrovoice_automation_update_payload.json', payload)
    print(json.dumps({'checkpoint_saved': now, 'source_hashes_verified': True,
        'completed_audit_rows': 55992, 'new_training_accepted': 0,
        'automation_payload_ready': True, 'docx_rebuilt': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
