"""Copy register rows that match a taxonomy_v1 disease or pest into kb_v1.json cards.

Rows come from extract_register_by.py; values are copied as printed. Cards stay
"draft": without a specialist the application shows them as unchecked text and does
not read them aloud. Only treatments with source B01 are replaced; other card fields
are left as they are.

    python -X utf8 kb/fill_register_treatments.py kb/register_by_rows.json
"""

import argparse
import json
import re
from pathlib import Path

KB = Path(__file__).with_name("kb_v1.json")
SECTION_TITLES = {
    "insecticide": "6.1 «Инсектициды и акарициды»",
    "fungicide": "6.3 «Фунгициды»",
    "biological": "6.7 «Биопрепараты»",
    "retail": "7.2 «СЗР для розницы»",
}
# Card code → words of the register's "harmful organism" column. A row matches when one word is present.
# Words that need a specialist to confirm the match are listed in NAME_NOTES.
OBJECT_WORDS = {
    "potato__late_blight": ["фитофтороз"],
    "potato__early_blight": ["альтернариоз"],
    "tomato__late_blight": ["фитофтороз"],
    "tomato__early_blight": ["альтернариоз"],
    "tomato__septoria_leaf_spot": ["септориоз"],
    "tomato__leaf_mold": ["кладоспориоз", "бурая пятнистость"],
    "tomato__bacterial_spot": ["бактериальная пятнистость", "черная бактериальная", "чёрная бактериальная"],
    "tomato__target_spot": ["коринеспороз"],
    "tomato__mite_damage": ["паутинный клещ", "паутинные клещи"],
    "tomato__leaf_miner": ["минирующ", "минер", "минёр", "томатная моль", "tuta"],
    "cucumber__downy_mildew": ["пероноспороз", "ложная мучнистая роса"],
    "cucumber__anthracnose": ["антракноз"],
    "cucumber__bacterial_wilt": ["бактериальное увядание"],
    "apple__scab": ["парша"],
    "apple__black_rot": ["черный рак", "чёрный рак"],
    "apple__cedar_rust": ["ржавчина"],
    "cherry__powdery_mildew": ["мучнистая роса"],
    "corn__gray_leaf_spot": ["церкоспороз"],
    "corn__common_rust": ["ржавчина"],
    "corn__northern_leaf_blight": ["гельминтоспориоз"],
    "grape__black_rot": ["черная гниль", "чёрная гниль"],
    "grape__esca": ["эска"],
    "grape__leaf_blight": ["изариопсис"],
    "peach__bacterial_spot": ["бактериальная пятнистость"],
    "pepper__bacterial_spot": ["бактериальная пятнистость", "черная бактериальная", "чёрная бактериальная"],
    "squash__powdery_mildew": ["мучнистая роса"],
    "strawberry__leaf_scorch": ["пятнистост"],
}
NAME_NOTES = {
    "apple__black_rot": "«Чёрный рак» в реестре сопоставлен с black rot той же группы возбудителей; соответствие должен подтвердить специалист.",
    "apple__cedar_rust": "В реестре — общая «ржавчина»; виды ржавчины не уточнены.",
    "corn__common_rust": "В реестре — общая «ржавчина» кукурузы.",
    "strawberry__leaf_scorch": "В реестре — пятнистости листьев земляники; соответствие leaf scorch (Diplocarpon earlianum) не подтверждено.",
    "tomato__leaf_mold": "«Бурая пятнистость» томата в реестре сопоставлена с кладоспориозом.",
}


def as_int(value, minimum):
    if value and re.fullmatch(r"\d+", value.strip()) and int(value) >= minimum:
        return int(value)
    return None


def treatment(row, code):
    title = SECTION_TITLES[row["section"]]
    name = row["product"] + (f", {row['formulation']}" if row["formulation"] else "")
    unit = row["header_unit"] or "единица указана в норме"
    notes = [f"Культура в реестре: {row['crop_text']}.", f"Вредный объект: {row['object']}."]
    if row["method"]:
        notes.append(f"Способ и ограничения: {row['method']}.")
    if as_int(row["max_treatments"], 1) is None and row["max_treatments"]:
        notes.append(f"Кратность в реестре: {row['max_treatments']}.")
    if as_int(row["waiting_days"], 0) is None and row["waiting_days"]:
        notes.append(f"Срок последней обработки в реестре: {row['waiting_days']}.")
    if row["header_unit"]:
        notes.append("Единица (л или кг) соответствует препаративной форме.")
    if len(re.findall(r"\d[\d,\-]*\s*(?:л|мл|г|кг|мг)\b", row["rate"])) > 1:
        notes.append("В ячейке реестра несколько норм для разных способов применения: сверяйте со страницей реестра.")
    if code in NAME_NOTES:
        notes.append(NAME_NOTES[code])
    return {
        "product": row["product"],
        "active_substance": row["active_substance"] or row["product_cell"],
        "rate": row["rate"],
        "unit": unit,
        "max_treatments": as_int(row["max_treatments"], 1),
        "waiting_days": as_int(row["waiting_days"], 0),
        "hazard_class": None,
        "bees_class": None,
        "registered_in": "BY",
        "register_year": 2026,
        "register_entry": f"{name}; раздел {title}, с. {row['page']}",
        "user_category": row["user_category"],
        "source": "B01",
        "locator": f"раздел {title}, с. {row['page']}",
        "note": " ".join(notes),
    }


def matches(row, code):
    crop = code.split("__")[0]
    harmful = (row["object"] or "").lower()
    return crop in row["crops"] and any(word in harmful for word in OBJECT_WORDS[code])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("rows", type=Path)
    args = parser.parse_args()
    rows = json.loads(args.rows.read_text(encoding="utf-8"))["rows"]
    kb = json.loads(KB.read_text(encoding="utf-8"))
    counts = {}
    for card in kb["cards"]:
        if card["code"] not in OBJECT_WORDS:
            continue
        kept = [item for item in card["treatments"] if item["source"] != "B01"]
        found = [treatment(row, card["code"]) for row in rows if matches(row, card["code"])]
        unique = list({json.dumps(item, ensure_ascii=False, sort_keys=True): item for item in found}.values())
        card["treatments"] = kept + unique
        counts[card["code"]] = len(unique)
        if unique:
            if "B01" not in card["sources"]:
                card["sources"].append("B01")
            card["candidate_sources"] = [source for source in card["candidate_sources"] if source != "B01"]
            card["missing"] = [section for section in card["missing"] if section != "treatments"]
    # Keep the line endings the file already has, so git shows only the changed cards.
    newline = "\r\n" if b"\r\n" in KB.read_bytes() else "\n"
    KB.write_text(json.dumps(kb, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline=newline)
    print(json.dumps(counts, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
