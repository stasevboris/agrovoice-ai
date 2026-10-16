"""Prepare GitHub Release files for trained allcrops models (run after the GPU session).

  python -X utf8 ml-pipeline/prepare_model_release.py runs/allcrops_v1/allcrops_v1_yolo26s_416_s20261003 \
      runs/allcrops_v1/allcrops_v1_yolo26n_416_s20261003 --tag models-allcrops-v1 --update-config

Each run directory comes from the GPU kit (results_*.zip) and must contain weights/best.onnx,
android_manifest_entry.json, run_summary.json and eval/evaluation.json.
Output: artifacts/release/<tag>/ with the ONNX files, SHA256SUMS.txt, MODEL_CARD.md, RELEASE_NOTES.md,
LICENSE and ATTRIBUTION.md; the model card is also written to docs/MODEL_CARD_<tag>.md.
Only YOLO models trained on allcrops_v1 are accepted (DEIMv2 weights must not be published).
Every number in the card comes from evaluation.json / run_summary.json.
"""
from __future__ import annotations

import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
PRIORITY_CROPS = ("potato", "tomato", "cucumber", "apple")
DOMAINS = (("field_val", "поле, val (PlantDoc)"), ("field_test", "поле, test (PlantDoc)"),
           ("lab_val", "одиночные листья, val"), ("lab_test", "одиночные листья, test"))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def load_run(run: Path) -> dict:
    files = {"onnx": run/"weights/best.onnx", "entry": run/"android_manifest_entry.json",
             "summary": run/"run_summary.json", "evaluation": run/"eval/evaluation.json"}
    missing = [str(path) for path in files.values() if not path.exists()]
    if missing:
        raise SystemExit(f"{run.name}: нет файлов {missing}")
    entry = json.loads(files["entry"].read_text(encoding="utf-8"))
    summary = json.loads(files["summary"].read_text(encoding="utf-8"))
    if entry.get("family") != "yolo" or entry.get("dataset") != "allcrops_v1":
        raise SystemExit(f"{run.name}: публикуются только YOLO-модели allcrops_v1")
    if summary.get("status") != "done":
        raise SystemExit(f"{run.name}: обучение не завершено (status={summary.get('status')})")
    if sha256(files["onnx"]) != entry["sha256"]:
        raise SystemExit(f"{run.name}: SHA-256 best.onnx не совпадает с android_manifest_entry.json")
    return dict(name=run.name, files=files, entry=entry, summary=summary,
                evaluation=json.loads(files["evaluation"].read_text(encoding="utf-8")))


def metric_table(runs) -> list[str]:
    lines = ["| Модель | "+" | ".join(label for _, label in DOMAINS)+" |",
             "|---|"+"---:|"*len(DOMAINS)]
    for run in runs:
        cells = []
        for key, _ in DOMAINS:
            value = run["evaluation"].get(key)
            cells.append(f"{value['map50']:.3f} / {value['map50_95']:.3f}" if value else "—")
        lines.append(f"| {run['name']} | "+" | ".join(cells)+" |")
    return lines


def priority_table(run) -> list[str]:
    classes = run["entry"]["classes"]
    labels = dict(zip(classes, run["entry"]["labels_ru"]))
    lines = ["| Класс | поле, test | одиночные листья, test |", "|---|---:|---:|"]
    field = run["evaluation"].get("field_test", {}).get("per_class_map50_95", {})
    lab = run["evaluation"].get("lab_test", {}).get("per_class_map50_95", {})
    for code in classes:
        if code.split("__")[0] in PRIORITY_CROPS:
            lines.append(f"| {labels[code]} | {field.get(code, '—')} | {lab.get(code, '—')} |")
    return lines


def model_card(runs, tag: str) -> str:
    first = runs[0]
    crops = ", ".join(first["entry"]["crops_ru"][c] for c in first["entry"]["crops"])
    sections = [
        f"# Карточка модели AgroVoice — {tag}",
        "",
        f"Подготовлено {date.today():%d.%m.%Y} скриптом `ml-pipeline/prepare_model_release.py`. Все числа — из `eval/evaluation.json` и `run_summary.json` каждого запуска.",
        "",
        "## Назначение",
        "",
        "Детектор **целого листа** с определением культуры и состояния для офлайн-приложения AgroVoice (Android, ONNX Runtime). "
        f"{len(first['entry']['classes'])} классов, культуры: {crops}.",
        "",
        "**Ограничения.** Это предварительная модель для поддержки решений, а не средство диагностики: результат должен проверять специалист. "
        "Уверенность модели — не вероятность болезни и не процент поражения. Качество на собственных полевых фото пользователей не измерено; "
        "лабораторные снимки (лист на однотонном фоне) составляют большую часть обучающих данных.",
        "",
        "## Модели",
        "",
        "| Файл | Архитектура | Вход | SHA-256 | Обучение |",
        "|---|---|---|---|---|",
    ]
    for run in runs:
        summary = run["summary"]
        sections.append(f"| `{run['name']}.onnx` | {run['entry']['name']} | {run['entry']['side']}×{run['entry']['side']} | `{run['entry']['sha256'][:16]}…` | "
                        f"{summary.get('gpu', '?')}, {summary.get('train_minutes', '?')} мин, seed {run['entry']['seed']} |")
    sections += ["", "Выход ONNX: `[1, 4 + классы, 3549]` — обычная голова YOLO, NMS выполняет приложение.", "",
                 "## Качество (mAP50 / mAP50–95)", "", *metric_table(runs), "",
                 "Поле — фото PlantDoc в естественной обстановке (val 349, test 236 снимков; в test есть несколько старых почти-дубликатов train). "
                 "Одиночные листья — PlantVillage, OLID, Zenodo.", "",
                 f"## Приоритетные культуры, {first['name']} (mAP50–95 по классам)", "", *priority_table(first), "",
                 "Пустая ячейка — класса нет в этой выборке.", "",
                 "## Данные и лицензии", "",
                 "Набор `allcrops_v1` (`ml-pipeline/build_allcrops_v1.py`): PlantDoc (CC BY 4.0), PlantVillage (CC BY-SA 3.0), OLID I (CC BY 4.0), "
                 "Cucumber Disease and Freshness — Curated Annotations (CC BY 4.0). Авторы — `ATTRIBUTION.md`. "
                 "Обучение — Ultralytics YOLO26 (AGPL-3.0); веса распространяются на условиях AGPL-3.0 вместе с проектом.", ""]
    return "\n".join(sections)


def release_notes(runs, tag: str) -> str:
    names = "\n".join(f"- `{run['name']}.onnx` — {run['entry']['name']}" for run in runs)
    return (f"Модели AgroVoice {tag}: распознавание культуры и состояния листа, "
            f"{len(runs[0]['entry']['classes'])} классов.\n\n{names}\n\n"
            "Подробности и метрики — MODEL_CARD.md. Авторы данных — ATTRIBUTION.md. Лицензия — AGPL-3.0.\n\n"
            "**Предварительная модель для поддержки решений, не средство диагностики. Результат проверяет специалист.**\n\n"
            "Установка в приложение: `python android-app/fetch_assets.py`.\n")


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("runs", nargs="+", type=Path)
    parser.add_argument("--tag", default="models-allcrops-v1")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--update-config", action="store_true", help="write the file list into android-app/model_release.json")
    args = parser.parse_args(argv)
    runs = [load_run(run) for run in args.runs]
    out = args.out or ROOT/"artifacts/release"/args.tag
    out.mkdir(parents=True, exist_ok=True)
    sums = []
    for run in runs:
        target = out/f"{run['name']}.onnx"
        shutil.copyfile(run["files"]["onnx"], target)
        sums.append(f"{sha256(target)}  {target.name}")
        (out/f"{run['name']}.manifest_entry.json").write_text(json.dumps(run["entry"], ensure_ascii=False, indent=2), encoding="utf-8")
    (out/"SHA256SUMS.txt").write_text("\n".join(sums)+"\n", encoding="utf-8")
    card = model_card(runs, args.tag)
    (out/"MODEL_CARD.md").write_text(card, encoding="utf-8")
    (out/"RELEASE_NOTES.md").write_text(release_notes(runs, args.tag), encoding="utf-8")
    for name in ("LICENSE", "ATTRIBUTION.md"):
        shutil.copyfile(ROOT/name, out/name)
    if args.out is None:
        (ROOT/"docs"/f"MODEL_CARD_{args.tag}.md").write_text(card, encoding="utf-8")
    if args.update_config:
        config_path = ROOT/"android-app/model_release.json"
        config = json.loads(config_path.read_text(encoding="utf-8"))
        config.update(tag=args.tag, files=[f"{run['name']}.onnx" for run in runs])
        config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(f"Готово: {out}")
    print("\n".join(sums))


if __name__ == "__main__":
    main()
