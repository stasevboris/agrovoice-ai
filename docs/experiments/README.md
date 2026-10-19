# Эксперименты и доказательства

Это архив протоколов и результатов; актуальная очередь — [STATUS](../STATUS.md)
и [PLAYBOOK](../guides/TRAINING_PLAYBOOK.md). Числа брать из первичных JSON/CSV,
учитывая набор, split, checkpoint, seed и версию программы оценки.

| Серия | Вход | Для чего полезна |
|---|---|---|
| Начальный PlantDoc | [MODEL_CARD](MODEL_CARD_plantdoc_clean_v1.md) | Происхождение первого baseline |
| Повторные обучения | [STUDY10_PROTOCOL](STUDY10_PROTOCOL_2026-10-03.md), [study10_v2/FINAL_REPORT](study10_v2/FINAL_REPORT_2026-10-04.md) | Сравнение ранних YOLO на фиксированных условиях |
| Сравнение YOLO | [YOLO_COMPARISON](YOLO_COMPARISON_2026-10-03.md) | Исторический выбор по прежним данным |
| Пилоты архитектур | [accuracy_v1/PROTOCOL](accuracy_v1/PROTOCOL.md) | Детектор, классификатор, маска, дистилляция, DETR; пилот не равен полной серии |
| YOLO26s | [MODEL_CARD](yolo26s_focus_v1/MODEL_CARD.md), [PROTOCOL](yolo26s_focus_v1/PROTOCOL.md) | История S и её выбора |
| YOLO26m | [REPORT](yolo26m_v1/REPORT.md), [PROTOCOL](yolo26m_v1/PROTOCOL.md) | Пилот M и границы сравнения |
| Геометрия и дубликаты | [REPORT](yolo26s_data_v1/REPORT.md), [SOURCE_AUDIT](yolo26s_data_v1/SOURCE_AUDIT_REPORT_2026-10-05.md) | Решения, которыми пользуется allcrops-сборщик |
| Ограничения метрик | [HISTORICAL_OVERLAP_ADDENDUM](yolo26s_data_v1/HISTORICAL_OVERLAP_ADDENDUM_2026-10-05.md) | Обязательное дополнение к старым PlantDoc-результатам |
| Скорость старых версий | [redmi_note_7_10series/REPORT](redmi_note_7_10series/REPORT.md) | Измерения на конкретном телефоне и старой цепочке |

Каталоги результатов и frozen `source_snapshots` оставлены по прежним путям.
Некоторые необходимые сборщику JSONL и превью не хранятся в git; см. [ARCHIVE](../ARCHIVE.md).
`CONTINUE.md` и `ETA` исторические: они не разрешают возобновлять старые обучения.
