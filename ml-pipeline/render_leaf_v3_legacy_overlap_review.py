"""Render the 37 inherited non-exact overlap clues for visual adjudication."""
from pathlib import Path
import hashlib
import json
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "docs/experiments/yolo26s_data_v1"
OUT = BASE / "legacy_overlap_visual_r1"


def main():
    if OUT.exists():
        raise FileExistsError("Keep prior visual review evidence")
    manifest = json.loads((ROOT / "ml-pipeline/dataset_clean_v2/manifest.json").read_text(encoding="utf-8"))
    records = {r["filename"]: r for r in manifest}
    links = [json.loads(line) for line in (BASE / "candidate_overlap_full_r2/links.jsonl").read_text(encoding="utf-8").splitlines()]
    pairs = [link for link in links if link["a"]["source"] == link["b"]["source"] == "HistoricalV2"]
    assert len(pairs) == 37 and not any(link["exact_decoded"] for link in pairs)
    OUT.mkdir(exist_ok=False)
    font = ImageFont.truetype("C:/Windows/Fonts/consola.ttf", 17)
    index = []
    canvas = None
    for i, pair in enumerate(pairs):
        if i % 4 == 0:
            canvas = Image.new("RGB", (1200, 1720), "white")
        draw = ImageDraw.Draw(canvas)
        top = (i % 4) * 430
        draw.text((12, top + 4), f"Pair {i+1:02d}  pHash={pair['phash_distance']}  dHash={pair['dhash_distance']}  non-exact", fill="black", font=font)
        evidence = dict(pair=i+1, link=pair, records=[])
        for side, label in enumerate(("a", "b")):
            info = pair[label]
            row = records[info["id"]]
            path = ROOT / "ml-pipeline/dataset_clean_v2/images" / row["split"] / row["filename"]
            raw = path.read_bytes()
            assert hashlib.sha256(raw).hexdigest() == row["prepared_sha256"]
            x = 12 + side * 595
            draw.text((x, top + 28), row["filename"], fill="black", font=font)
            draw.text((x, top + 49), f"{row['split']}  source={row['source_dataset']}  classes={sorted({int(b[0]) for b in row['boxes']})}", fill="black", font=font)
            with Image.open(path) as source:
                preview = ImageOps.contain(ImageOps.exif_transpose(source).convert("RGB"), (575, 340))
            canvas.paste(preview, (x + (575-preview.width)//2, top + 78 + (340-preview.height)//2))
            evidence["records"].append(dict(id=row["filename"], split=row["split"],
                                           path=str(path.relative_to(ROOT)), sha256=row["prepared_sha256"]))
        index.append(evidence)
        if i % 4 == 3 or i == len(pairs)-1:
            canvas.save(OUT / f"sheet-{i//4+1:02d}.png")
    (OUT / "index.json").write_text(json.dumps(dict(pairs=index, sheets=10, inspected_sheets=[],
                                                    status="pending_visual_review"), indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(pairs=len(pairs), sheets=10, originals_sha_verified=74)))


if __name__ == "__main__":
    main()
