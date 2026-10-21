"""Extract rows of the Belarus plant protection register for the AgroVoice crops.

Input: PDF sections downloaded from https://ggiskzr.by/reestr-szr/ (not stored in git).
Output: one JSON row per product × crop × harmful object, with the page number,
copied verbatim from the table. Nothing is converted or guessed: "То же" and merged
cells take the value of the row above, exactly as the printed table means.

    python -X utf8 kb/extract_register_by.py artifacts/kb_sources/reestr_by --out kb/register_by_rows.json
"""

import argparse
import hashlib
import json
import re
from pathlib import Path


# Section: kind, user category, unit printed in the table header (None: unit is inside the rate cell),
# and whether the last two columns are "count, waiting days" (retail table) instead of "waiting days, count".
SECTIONS = {
    "6.1. Инсектициды и акарициды.pdf": ("insecticide", "professional", "л/га, кг/га", False),
    "6.3. Фунгициды.pdf": ("fungicide", "professional", "л/га, кг/га", False),
    "6.7. Биопрепараты.pdf": ("biological", "professional", None, False),
    "7.2. СЗР для розницы.pdf": ("retail", "household", None, True),
}
CROPS = {
    "apple": ["яблон"], "blueberry": ["голубик"], "cherry": ["вишн"], "corn": ["кукуруз"],
    "cucumber": ["огур"], "grape": ["виноград"], "orange": ["цитрус", "апельсин"], "peach": ["персик"],
    "pepper": ["перец", "перца"], "potato": ["картоф"], "raspberry": ["малин"], "soybean": ["соя", "сои"],
    "squash": ["тыкв", "кабач", "патисон"], "strawberry": ["землян", "клубник"], "tomato": ["томат", "помидор"],
}
TABLE_SETTINGS = {"vertical_strategy": "lines", "horizontal_strategy": "lines"}
SAME = "то же"
PRODUCT_PREFIXES = ("Биопрепарат", "Биопестицид", "Препарат", "Средство", "Краска", "БИОПРЕПАРАТ", "«")
ORGANIZATION_WORDS = {"ООО", "ОАО", "ЗАО", "АО", "ГНУ", "ГНПО", "РУП", "УП", "УО", "ТУ", "ОДО", "ЧПУП", "ЧУП",
                      "ИООО", "СООО", "НАН"}


def clean(value):
    if value is None:
        return None
    # Word hyphenation is joined; a dash between numbers ("0,15-\n0,25") stays a range.
    text = re.sub(r"(?<=[^\W\d_])-\n(?=[^\W\d_])", "", value)
    text = text.replace("-\n", "-").replace("\n", " ")
    return re.sub(r"\s+", " ", text).strip()


def normalize_rate(text):
    """Only removes a stray space inside a printed number: "0, 04" -> "0,04"."""
    return re.sub(r"(\d), (\d)", lambda match: match.group(1) + "," + match.group(2), text)


def crops_of(text):
    lowered = (text or "").lower()
    return [crop for crop, stems in CROPS.items() if any(stem in lowered for stem in stems)]


def starts_product(cell):
    """A page-top cell starting a new product, not the applicant/titre of the previous one."""
    if cell.startswith(PRODUCT_PREFIXES):
        return True
    first = cell.split()[0].strip(",")
    return len(first) >= 2 and first.isupper() and first not in ORGANIZATION_WORDS


def split_product(cell):
    """'КВАДРИС, СК (азоксистробин, 250 г/л), Сингента..., (Р), (П-3)' → parts."""
    match = re.match(r"^(?P<name>[^(]+?),?\s+(?P<form>[А-ЯA-Z]{1,5})\s*\((?P<substance>.+?\d.*?)\),?\s*(?P<rest>.*)$", cell)
    if not match:
        # Biological products and old Belarusian names: «name», form, titre (substance), applicant.
        loose = re.match(r"^(?P<head>[^(]+)\((?P<substance>[^)]*)\)(?P<rest>.*)$", cell)
        if not loose:
            return {"product": cell, "formulation": None, "active_substance": None, "applicant": None}
        return {"product": loose["head"].strip(" ,"), "formulation": None,
                "active_substance": loose["substance"].strip(), "applicant": loose["rest"].strip(" ,")}
    return {"product": match["name"].strip(), "formulation": match["form"].strip(),
            "active_substance": match["substance"].strip(), "applicant": match["rest"].strip()}


def table_rows(pdf_path):
    import pdfplumber  # only needed for reading PDFs; the helpers above are tested without it

    with pdfplumber.open(pdf_path) as pdf:
        for page_number, page in enumerate(pdf.pages, 1):
            for table in page.find_tables(TABLE_SETTINGS):
                for row in table.extract():
                    yield page_number, [clean(cell) for cell in row]


def extract(pdf_path, kind, user_category, header_unit, count_first):
    previous = {"product": None, "rate": None, "crop": None, "object": None, "method": None,
                "waiting_days": None, "max_treatments": None}
    rows = []
    page_with_rows = None
    for page, cells in table_rows(pdf_path):
        if len(cells) != 7:
            continue
        product, rate, crop, harmful, method, waiting, count = cells
        if count_first:
            waiting, count = count, waiting
        if crop and ("Культура" in crop or "обрабатывае" in crop):
            continue
        first_on_page = page != page_with_rows
        page_with_rows = page
        if product and not any(cells[1:]):
            # Active-substance heading between products.
            previous = dict.fromkeys(previous)
            continue
        current = dict(previous)
        continues = product and (product.startswith("(") or product[0].islower()
                                 or (first_on_page and not starts_product(product)))
        if continues and current["product"]:
            # The product cell continues from the previous page.
            current["product"] = current["product"] + " " + product
        elif product:
            current["product"] = product
        for key, value in (("rate", rate), ("crop", crop), ("object", harmful), ("method", method),
                           ("waiting_days", waiting), ("max_treatments", count)):
            if value and value.lower() != SAME:
                current[key] = value
        previous = current
        if not rate or not current["product"]:
            continue
        matched = crops_of(current["crop"])
        if not matched:
            continue
        rows.append({"section": kind, "user_category": user_category, "page": page, "header_unit": header_unit,
                     "product_cell": current["product"], **split_product(current["product"]),
                     "rate": normalize_rate(current["rate"]), "crop_text": current["crop"], "crops": matched,
                     "object": current["object"], "method": current["method"],
                     "waiting_days": current["waiting_days"], "max_treatments": current["max_treatments"]})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    files = []
    rows = []
    for name, (kind, category, header_unit, count_first) in SECTIONS.items():
        path = args.folder / name
        files.append({"file": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        section_rows = extract(path, kind, category, header_unit, count_first)
        print(f"{name}: {len(section_rows)} rows")
        rows.extend(section_rows)
    result = {"source": "https://ggiskzr.by/reestr-szr/", "files": files, "rows": rows}
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
