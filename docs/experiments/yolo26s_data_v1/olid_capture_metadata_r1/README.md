# OLID capture metadata audit — 5 October 2026

Input: `docs\experiments\yolo26s_data_v1\olid_original_geometry_r3\records.jsonl`, SHA-256 `643dd1e54e30c4f987156fa19344873ee94d3636a87bdcefd832b9b596429372`.

The inventory has 1047 original files. EXIF capture timestamps are available for 938; 109 have no capture-time tags. `DateTimeOriginal` and `DateTimeDigitized` agree wherever both are present. In eight files the generic EXIF `DateTime` differs by two days; use `DateTimeOriginal` for capture chronology. Make/model, time-zone offset, and GPS tags are absent in this audit.

## Capture dates and label support

- **2022-03-17** — 409 images: tomato__K 7, tomato__LM 207, tomato__N 23, tomato__N_K 11, tomato__healthy 161
- **2022-04-25** — 40 images: tomato__N 17, tomato__N_K 10, tomato__healthy 13
- **2022-08-16** — 286 images: tomato__JAS_MIT 10, tomato__K 7, tomato__MIT 200, tomato__N 7, tomato__healthy 62
- **2023-05-04** — 114 images: cucumber__K 8, cucumber__N 53, cucumber__N_K 46, cucumber__healthy 7
- **2023-05-06** — 89 images: cucumber__K 16, cucumber__N 36, cucumber__N_K 30, cucumber__healthy 7

There are 8 duplicate-second groups covering 16 records. Group same-second captures conservatively for splitting, but do not treat that as a verified plant identity. The five capture dates are crop/state clustered: cucumber appears on two dates, and several tomato states are concentrated on only one date. A date-only holdout would remove entire crop/state support, so it cannot supply the new study's all-class independent test alone. Identifiable plant/session IDs remain unavailable, and 109 images have no timestamp. The local Zenodo archives contain class-folder images but no per-image plant/session table.

This is metadata analysis only. It does not approve labels or boxes: **0 new images accepted** and no model was trained. Full row-level date groups and caveats are in `audit.json`.
