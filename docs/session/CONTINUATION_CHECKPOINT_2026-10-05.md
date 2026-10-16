# Контрольная точка AgroVoice — 2026-10-05T12:30:23.271054+00:00

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

1. Use candidate_overlap_full_r3/candidate_disposition.jsonl and group_closure_r1/candidate_split_constraints.jsonl in the new manifest; neither is an accepted training dataset
2. Keep all ten OLID conflicting-label exact copies quarantined; original archive bytes were verified identical in each pair
3. Keep the 71 historical images in legacy_overlap_visual_r1/visual_adjudication.json quarantined for the NEW base pending object/label review; do not modify frozen old data
4. Complete source-backed class semantics and whole-visible-leaf boxes for PlantVillage and cucumber; resolve geometry flags and unknown capture groups
5. Historical train-family candidates (1781) can only be training if all other checks pass; 447 historical holdout-family candidates are never new train or independent test
6. OLID capture dates are confounded with crop/state; do not use date-only split as all-class independent holdout
7. Build all-crop schema/provenance manifest/grouped independent val/test, freeze recipe, pass preflight and show Boris actual source counts before GPU
8. Then run exactly two sequential YOLO26s seeds 20261003/20261004, max60 epochs/patience15; no automatic third pilot or M restart

Новая S — отдельная схема со всеми исходнымиID0..28/всеми культурами, достоверными добавленными состояниями; старый unknown29 не конкурирует сhealthy. Только после готовых данных/lock/preflight и сообщения состава: два seed20261003/04,416/b16/FP32/AdamWlr0.001/max60/patience15 на существующейRX9060XT;60 — предел, не доказанный оптимум. Затем обеval/кривые/выбор до новогоtest/экспорт, корректный расширенный evaluator и новый отдельныйAPK-режим при сохранении9исторических примеров и функций. Телефон/оборудование/публикацию не трогать сейчас.

## Лимит и автоматика

Actual get_usage_limits12:31:04UTC: использовано37% пятичасового/99% недельного, осталось63%/около1%; ordinaryUsageAllowed=true. Борис отменил15%резерв/ожидание; следующему запуску заново читать фактическую квоту. Инструмент не показывает токены, поэтому требование «100к осталось» нельзя сопоставить с процентами; reproducible checkpoint сохранён заранее. АвтоматикаACTIVE до полного нового этапа, не приостанавливать из-за DOCX или одного аудита.

## Additive class support audit

class_support_r1/report.json verifies all 29 original ID/name positions and tabulates all 54,305 PlantVillage originals across 38 categories with geometry flags and grouped split restrictions. No constraint records missing. Candidate counts remain distinct from accepted data and independent plants; train accepted remains zero. Completed audits should not be rerun. Actual quota at this heartbeat: 61% five-hour, about 1% weekly, ordinaryUsageAllowed=true.
