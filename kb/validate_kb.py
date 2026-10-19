"""Check the AgroVoice knowledge base: JSON Schema plus the rules a schema cannot express.

Usage: python -X utf8 kb/validate_kb.py [kb/kb_v1.json]
Needs the `jsonschema` package (ml-pipeline/requirements-accuracy-extra.txt).
"""

import argparse
import json
import re
import sys
from collections import Counter
from datetime import date
from pathlib import Path

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
KB = ROOT / "kb/kb_v1.json"
SCHEMA = ROOT / "kb/kb_v1.schema.json"

STATEMENT_SECTIONS = ("symptoms", "conditions", "prevention", "agrotech")
# Sections a card of each kind must either fill, mark as missing or mark as checked and empty.
APPLICABLE = {
    "disease": STATEMENT_SECTIONS + ("treatments",),
    "pest": STATEMENT_SECTIONS + ("treatments",),
    "nutrition": STATEMENT_SECTIONS + ("fertilization",),
    "healthy": ("agrotech",),
}
# K-02: orange is described only; it is not grown in Belarus, so products are not applicable.
CROPS_WITHOUT_PRODUCTS = {"orange"}
# A rate in free text would bypass the register check: «2 л/га», «0,5 кг/га», «10 мл на 10 л».
RATE_IN_TEXT = re.compile(r"\d\s*(л|кг|г|мл)\s*(/|на\s)\s*(га|сот|м2|м²|л|10\s*л|100\s*м)", re.IGNORECASE)


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def schema_errors(document, schema):
    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(document), key=lambda error: list(error.path))
    return [f"схема: {'/'.join(str(part) for part in error.path) or '<корень>'}: {error.message}" for error in errors]


def validate(document, taxonomy, schema):
    """All problems as readable lines; an empty list means the base is consistent."""
    problems = schema_errors(document, schema)
    if problems:
        return problems
    sources = {source["id"]: source for source in document["sources"]}
    duplicates = [key for key, count in Counter(source["id"] for source in document["sources"]).items() if count > 1]
    problems += [f"источник {key}: повторяется id" for key in duplicates]
    classes = {item["code"]: item for item in taxonomy["classes"]}
    codes = Counter(card["code"] for card in document["cards"])
    problems += [f"{code}: карточка повторяется" for code, count in codes.items() if count > 1]
    problems += [f"{code}: нет карточки для класса taxonomy" for code in classes if code not in codes]
    for card in document["cards"]:
        problems += card_problems(card, classes.get(card["code"]), sources)
    return problems


def card_problems(card, known, sources):
    code = card["code"]
    if known is None:
        return [f"{code}: класса нет в taxonomy_v1"]
    problems = []
    if card["crop"] != known["crop"] or card["kind"] != known["kind"]:
        problems.append(f"{code}: культура или тип не совпадают с taxonomy ({known['crop']}, {known['kind']})")
    problems += section_problems(card)
    problems += reference_problems(card, sources)
    problems += product_problems(card, sources)
    if card["status"] == "verified":
        problems += verification_problems(card)
    return problems


def section_problems(card):
    code = card["code"]
    applicable = APPLICABLE[card["kind"]]
    if card["crop"] in CROPS_WITHOUT_PRODUCTS:
        applicable = tuple(section for section in applicable if section not in ("treatments", "fertilization"))
    problems = []
    missing = set(card["missing"])
    none_found = set(card["none_found"])
    for section in missing & none_found:
        problems.append(f"{code}: раздел {section} одновременно «нет данных» и «проверено, пусто»")
    for section in ("symptoms", "conditions", "prevention", "agrotech", "treatments", "fertilization"):
        filled = bool(card[section])
        marked = section in missing or section in none_found
        if section not in applicable:
            if filled or marked:
                problems.append(f"{code}: раздел {section} не применим к типу {card['kind']}")
            continue
        if filled and marked:
            problems.append(f"{code}: раздел {section} заполнен, но отмечен как пустой")
        if not filled and not marked:
            problems.append(f"{code}: раздел {section} пуст без явной отметки missing или none_found")
    for section in STATEMENT_SECTIONS:
        for item in card[section]:
            if RATE_IN_TEXT.search(item["text"]):
                problems.append(f"{code}: норма в тексте раздела {section}; нормы — только в записи реестра")
    return problems


def reference_problems(card, sources):
    code = card["code"]
    used = set(card["sources"]) | set(card["candidate_sources"])
    for section in ("symptoms", "conditions", "prevention", "agrotech", "treatments", "fertilization"):
        used |= {item["source"] for item in card[section]}
    problems = [f"{code}: нет источника {key} в списке sources" for key in sorted(used - set(sources))]
    cited = set()
    for section in ("symptoms", "conditions", "prevention", "agrotech", "treatments", "fertilization"):
        cited |= {item["source"] for item in card[section]}
    for key in sorted(cited - set(card["sources"])):
        problems.append(f"{code}: источник {key} использован, но не указан в sources карточки")
    return problems


def product_problems(card, sources):
    code = card["code"]
    problems = []
    for item in card["treatments"] + card["fertilization"]:
        source = sources.get(item["source"])
        if source is not None and source["kind"] != "by_register":
            problems.append(f"{code}: препарат {item['product']} не из Госреестра РБ ({item['source']})")
    return problems


def verification_problems(card):
    code = card["code"]
    problems = []
    if not card["verified_by"]:
        problems.append(f"{code}: verified без имени специалиста")
    if card["verified_at"] is None or card["review_due"] is None:
        problems.append(f"{code}: verified без даты проверки или пересмотра")
    elif date.fromisoformat(card["review_due"]) <= date.fromisoformat(card["verified_at"]):
        problems.append(f"{code}: дата пересмотра не позже даты проверки")
    if card["missing"]:
        problems.append(f"{code}: verified с незаполненными разделами {', '.join(card['missing'])}")
    if not card["sources"]:
        problems.append(f"{code}: verified без источников")
    return problems


def summary(document):
    cards = document["cards"]
    statuses = Counter(card["status"] for card in cards)
    gaps = Counter(section for card in cards for section in card["missing"])
    products = sum(len(card["treatments"]) for card in cards)
    return {
        "cards": len(cards),
        "status": dict(statuses),
        "treatments": products,
        "missing_sections": dict(gaps),
        "sources": len(document["sources"]),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("kb", nargs="?", type=Path, default=KB)
    args = parser.parse_args()
    document = load(args.kb)
    taxonomy = load(ROOT / document.get("taxonomy", "ml-pipeline/allcrops/taxonomy_v1.json"))
    problems = validate(document, taxonomy, load(SCHEMA))
    for problem in problems:
        print(problem)
    if problems:
        print(f"Ошибок: {len(problems)}")
        return 1
    print(json.dumps(summary(document), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
