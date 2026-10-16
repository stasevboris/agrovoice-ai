"""Record actual source-audit progress; neither freeze nor launch training."""
from __future__ import annotations
import argparse, hashlib, json
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'docs/experiments/yolo26s_data_v1'
PV=ROOT/'ml-pipeline/source_candidates/yolo26s_data_v1/plantvillage_all_crops'


def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--remaining',type=int,required=True)
    parser.add_argument('--weekly-remaining',type=int,required=True)
    parser.add_argument('--budget-check-utc',required=True)
    args=parser.parse_args()
    now=datetime.now(timezone.utc).isoformat()
    stage=read(PV/'archive_stage_status.json')
    geometry=read(BASE/'plantvillage_full_geometry_r1/status.json')
    visual=read(BASE/'olid_full_review_r2/visual_review.json')
    olid=read(BASE/'olid_review_r2_chroma/inventory.json')
    r3path=BASE/'olid_original_geometry_r3/status.json'
    r3=read(r3path) if r3path.exists() else {'status':'not_started'}
    mapping=read(BASE/'source_class_mapping.draft.json')['PlantVillage_mapping']
    plan=read(BASE/'plan.draft.json')
    if plan['seeds']!=[20261003,20261004] or plan['launch_allowed']:
        raise ValueError('Audit report expects the authorized two-run blocked draft')
    protected={name:sha(ROOT/'ml-pipeline'/name) for name in plan['protected_sources']}
    if protected!=plan['protected_sources']:raise ValueError('Protected sources changed')
    if len(visual['visually_seen_ids'])!=1047:raise ValueError('Full actual OLID visual review required')
    snapshot=dict(status='audited_sources_pending_final_annotations_splits',updated_utc=now,
        new_training_started=False,new_training_images_accepted=0,
        PlantVillage=dict(stage=stage,geometry=geometry),
        OLID=dict(originals=1047,class_counts=olid['source_class_counts'],
            source_archives=olid['archives'],visually_seen_proposals=1047,sheets=66,
            visual_review_sha256=sha(BASE/'olid_full_review_r2/visual_review.json'),original_geometry=r3),
        source_contract='ANNOTATION_CONTRACT_DRAFT.md',contract_sha256=sha(BASE/'ANNOTATION_CONTRACT_DRAFT.md'),
        protected_sources=protected,requested_runs=2,seeds=plan['seeds'],recipe=plan['recipe'],
        ready_dataset=False,ready_frozen_plan=False,
        budget=dict(remaining=args.remaining,weekly_remaining=args.weekly_remaining,checked_utc=args.budget_check_utc))
    write(BASE/'audit_progress_2026-10-05.json',snapshot)
    lines=['# Аудит источников новой YOLO26s: фактический срез', '',f'ОбновленоUTC: {now}.', '',
        '**Новый набор ещё не готов; новых принятых train-фото0, нового GPU-обучения0.** M отменена, M03/M04/M05checkpoint сохранены. APK0.16 и девять режимов не менялись.', '',
        '## PlantVillage: все прежние культуры', '',
        'Публичный закреплённый snapshot полностью получен; требуемые77388файлов (38694RGB и38694масок) проверены по авторскимGitblob. Архив2508320118байт, SHA256 `08c1367853c3b4afe636755ea84d454f73920e82ffcfd3453bd26b1c5d26a070`. Это38694оригинала, не77388обучающих изображений. Источник: [официальныйрепозиторий](https://github.com/spMohanty/PlantVillage-Dataset/tree/7f7ecc7e1eaca78107e3affe7cb5abd9427e139a), лицензияCCBY-SA3.0. SHA/версии сохранены вsnapshot и исходномmanifest.', '',
        f'Полный CPU-аудит: {geometry["checked_pairs"]}пар; {geometry["counts"]["no_flags"]}без автоматических замечаний, {geometry["counts"]["flagged"]}с замечаниями. Отсутствие флага не является приёмкой. Флаги пересекаются; их нельзя складывать как число отклонённых фото.', '',
        '| ID | Прежний класс | Пар оригинал–маска | Без автофлагов |', '|---:|---|---:|---:|']
    for m in sorted(mapping,key=lambda x:x['proposed_existing_id']):
        c=geometry['class_counts'][m['source_class']]
        lines.append(f'|{m["proposed_existing_id"]}|{m["proposed_existing_name"]}|{c["pairs"]}|{c["no_flags"]}|')
    lines+=['', 'Все29 ID0…28 сохраняются. Ноль в последнем столбце чаще означает неизвестную физическую группу; он не означает отмену культуры. Прежние PlantDocклассы не удаляются. Девять прочих категорий исходногоPVархива не подменяют эти29 и пока не приняты без отдельного разбора.', '',
        '| Автоматический флаг | Число пар с этим флагом |','|---|---:|']
    for key,value in geometry['flags'].items():lines.append(f'|{key}|{value}|')
    lines+=['', '## OLID: авторские новые состояния', '',
        'Проверены три архивныхMD5 и все1047оригиналов (249огурцов/798томатов). Все1047предложений действительно просмотрены на66листах; точныеID/наблюдения иSHAиндекса сохранены в[visual_review.json](olid_full_review_r2/visual_review.json). Этот просмотр относится к1024pxсжатымpreview. Авторские диагнозы не переопределялись по картинке. [Статьяметодов](https://www.frontiersin.org/journals/plant-science/articles/10.3389/fpls.2023.1251888/full), [выпуск1.1.0/CCBY4](https://zenodo.org/records/8105154).', '',
        '| Исходная метка | Оригиналов |','|---|---:|']
    for key,value in sorted(olid['source_class_counts'].items()):lines.append(f'|{key}|{value}|')
    lines+=['', 'LM=минёр, не плесень; MIT=повреждение клещом без автоматического подтверждения двупятнистого вида; JASMIT=комбинированное повреждение. N/K/NK — отдельные дефициты, не инфекции. Здоровье сохраняется по авторскому происхождению метки, не по нашей визуальной догадке.', '',
        'Часть томатных образцов — связанные составные листья. Уточнён[контрактобъекта](ANNOTATION_CONTRACT_DRAFT.md): один авторский изолированный листовой образец может иметь соединённые боковые пластинки; общая рамка должна включать их. Отдельный лежащий рядом лист не объединяется молча. Перваяlargest-componentверсия могла пропускать боковые части. Пересчёт из SHA-проверенных оригиналов сохраняется отдельно, без перезаписи предыдущих доказательств.', '',
        f'Текущий оригинальныйCPU-аудит: `{r3["status"]}`, проверено{r3.get("checked",0)}/1047. EXIF-время исследуется для блоков съёмки, но не объявляетсяIDрастения. Чёрный фон и обе стороны листа ограничивают перенос на поле.', '',
        '## Огурец, поле и независимая проверка', '',
        '640листовых оригиналов Sultana/Zenodo16816441 проинвентаризированы;165предложений рамок просмотрены. Разметка bacterialwilt/downymildew не становится целолистовой автоматически. FreshLeaf не доказывает здоровье любого неоднозначного примера. Старый огуречный unknown29 сохраняется только в старых графах; его новый источник-обоснованный перевод ещё не завершён.', '',
        'Полевые PLDD-UP15519 и PakistanTomato7200 подтверждены карточками и авторскимиSHA, однако завершённыеZIP ещё не получены обычной загрузкой. Эти числа не входят в принятыйtrain. Wageningen823JPG содержит разметку участков ткани на78кадрах, не готовые рамки всех листьев. Родственные копии Sultana/Zenodo не являются независимымtest.', '',
        'Проверка1919ранних кандидатов против3354исторических записей дала7внутренних и89исторических близких связей. Это связи, не уникальные фото. Повороты/обратные стороны/новые38694PV требуют расширенного аудита. Историческиеval/test не переходят вtrain. Для новых состояний нужны честно описанные группы и поддержкаval/test; случайное разбиение файлов безIDрастений не выдаётся за полевую независимость.', '',
        '## Что именно будет обучаться', '',
        'Два свежих YOLO26s Detect на RX9060XT16ГБ/WSL, последовательно seed20261003/04. Сохранить все29PlantDocID и все прежние культуры; новые достоверные состояния добавлять отдельно. COCOвеса уже на диске. Проект416,batch16,FP32/AMPoff,AdamWlr0.001,cos_lr, максимум60эпох,patience15.60 — прежний проверенный предел, не установленный оптимум новых данных. Первый неизменный технический запуск входит в этидва; третьего автоматического пилота/десяти обучений нет.', '',
        'ДоGPU: окончательные рамки/происхождение/дедупликация/группы/схема/разбиение, точный фактический состав сообщён Борису, затем data+recipe lock и строгийpreflight. После двух: отдельныеval/кривые/ошибки, выбор поval до новогоtest, корректныйevaluator расширенной головы, экспорт и отдельный новый режимAPK с собственной схемой при сохранении девяти примеров/всехголосовыхи полевых функций. Старые защищённые evaluator/данные не меняются.', '',
        'AP не процент правильных диагнозов; уверенность не медицинская/агрономическая достоверность. Процент поражения не заявляется без подходящихметок.', '',
        f'Реальныйлимит при проверке{args.budget_check_utc}: остаток{args.remaining}%пятичасового/{args.weekly_remaining}%недельного. Сохранённое старое ожидание до05октября01:00Minsk исполнено; новой обязательной паузы на этомсрезе нет.' ]
    (BASE/'SOURCE_AUDIT_REPORT_2026-10-05.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    statepath=ROOT/'docs/session/yolo26s_data_work_state.json';s=read(statepath)
    s.update(updated_utc=now,remaining_percent=args.remaining,weekly_remaining_percent=args.weekly_remaining,
        budget_check_utc=args.budget_check_utc,active_olid_geometry='docs/session/active_olid_geometry.json')
    s['progress'].update(PlantVillage_verified_pairs=38694,PlantVillage_verified_source_files=77388,
        PlantVillage_full_geometry_checked=geometry['checked_pairs'],PlantVillage_full_geometry_flagged=geometry['counts']['flagged'],
        OLID_all_proposals_visually_seen=1047,OLID_all_sheets_seen=66,OLID_original_geometry_status=r3['status'])
    s['next']=['Complete original-file OLID geometry and capture-metadata audit; review changed/sample-union boxes',
        'Resolve PV mask flags, physical groups and full-source overlaps; do not cut any previous crop',
        'Acquire accessible field originals and finish cucumber/whole-leaf annotations with trustworthy source labels',
        'Assemble actual all-crop manifest/schema/grouped splits and independent validation; report real counts to Boris',
        'Only after audited dataset/locked plan/preflight: two authorized sequential S runs, full evaluation/export/new separate APK mode']
    s['source_audit_report']='docs/experiments/yolo26s_data_v1/SOURCE_AUDIT_REPORT_2026-10-05.md';write(statepath,s)
    path=ROOT/'docs/session/accuracy_work_state.json';a=read(path)
    a.update(updated_utc=now,remaining_percent=args.remaining,weekly_remaining_percent=args.weekly_remaining,
        last_known_remaining_percent=args.remaining,last_known_weekly_remaining_percent=args.weekly_remaining,
        budget_check_utc=args.budget_check_utc,last_budget_check_utc=args.budget_check_utc)
    a['last_heartbeat_check']=dict(updated_utc=now,M_worker_running=False,M_checkpoint_complete_epoch=53,
        action='Source verification and geometry only; old M not resumed; new GPU0',
        new_scope_state='docs/session/yolo26s_data_work_state.json',source_audit_report=s['source_audit_report'])
    a['next']=s['next'];write(path,a)
    path=BASE/'data_inventory.json';d=read(path)
    d['source_audit_progress']=dict(PlantVillage_pairs_verified=38694,PlantVillage_files_verified=77388,
        PlantVillage_geometry_checked=geometry['checked_pairs'],OLID_originals=1047,
        OLID_proposals_visually_seen=1047,accepted_new_training=0,report=s['source_audit_report'])
    write(path,d)
    print(json.dumps({'PlantVillage_pairs':38694,'OLID_seen':1047,'OLID_original_status':r3['status'],
        'new_train_accepted':0,'new_GPU':0,'protected_SHA':'all7unchanged'},ensure_ascii=False))


if __name__=='__main__':main()
