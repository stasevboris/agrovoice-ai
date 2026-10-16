# Full overlap review — 5 October 2026

Input: `links.jsonl`, SHA-256 `368163d65a45b000a5852310e9f64c2f5da2b0c8f59bb38514a344e8fd56223d`. The source files, frozen dataset, and labels were not modified.

## Deterministic duplicate screen

- **792** candidate-to-historical links were found, all exact after RGB decoding. They identify **792 unique candidate records**: 739 PlantVillage originals and 53 cucumber-source originals. Their historical counterparts are all in the frozen `train` (632) or `val_aux` (160) splits; none of these links points to a historical main `test` record. Keep the candidate originals in the provenance archive but exclude these exact copies from the new independent split.
- **28** exact duplicate edges occur within new sources: 21 PlantVillage, 6 OLID, and 1 cucumber-source pair. This is not 28 independent photographs proven by capture ID. The full source identities are in `REVIEW_DECISIONS_2026-10-05.json`.
- **5 OLID pairs** have different publisher state labels despite identical decoded RGB (three `tomato__N` / `tomato__K`; two `tomato__MIT` / `tomato__N`). Keep these ten records quarantined until source metadata resolves the conflicting labels. Do not choose a diagnosis from appearance.
- **37 non-exact links** are internal to the previously frozen v2 dataset. They are recorded as inherited audit clues only; no old images, split assignments, or reports were changed.

No source originals are accepted for training by this audit; the accepted-new-training count remains **0**. A hash match demonstrates pixel identity after decode, not physical-plant identity. Near-hash links alone are not enough to identify a shared leaf or capture session.
