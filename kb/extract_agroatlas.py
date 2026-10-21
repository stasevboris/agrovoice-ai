"""Переносит описания болезней из Агроэкологического атласа в черновые карточки.

    python -X utf8 kb/extract_agroatlas.py            # обновить kb/kb_v1.json
    python -X utf8 kb/extract_agroatlas.py --dry-run  # только показать, что будет добавлено

Класс берётся только из проверенного соответствия `kb/agroatlas_map_v1.json`.
Страницы кэшируются в `artifacts/kb_sources/agroatlas/pages` (в git не идут).
Карточка остаётся `draft`: текст цитируется с указанием раздела, специалист его
не проверял. Нормы препаратов в текст не переносятся — только реестр.
"""

import argparse
import html
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KB_PATH = ROOT / "kb" / "kb_v1.json"
MAP_PATH = ROOT / "kb" / "agroatlas_map_v1.json"
CACHE = ROOT / "artifacts" / "kb_sources" / "agroatlas" / "pages"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"

SOURCE = {
    "id": "B21",
    "kind": "scientific",
    "title": "Агроэкологический атлас России и сопредельных государств: сельскохозяйственные растения, их вредители, болезни и сорняки",
    "publisher": "Проект AgroAtlas (Ильина И.Ю. и др.)",
    "url": "https://agroatlas.ru/ru/content/diseases/index.html",
    "edition": "2003–2009, раздел «Болезни сельскохозяйственных культур»",
    "accessed": "2026-10-21",
    "region": "RU и сопредельные государства",
    "rights": (
        "Цитирование отдельных фрагментов с указанием источника, раздела и авторов страницы. "
        "Массовое копирование и распространение не разрешено. Материал исторический (2003–2009): "
        "пригоден для описания симптомов, но не для действующих регламентов и не как диагноз."
    ),
    "extracted": True,
}

SYMPTOM_WORDS = (
    "лист", "пятн", "налет", "налёт", "симптом", "пораж", "бур", "жёлт", "желт",
    "края", "жилк", "язв", "гнил", "увядан", "курчав", "мозаик", "окаймлен", "череш",
    "скручив", "засых", "хрупк", "ткани",
)
# Строение самого возбудителя (размеры, жгутики, споры) — не признак на листе.
PATHOGEN_WORDS = ("кониди", "мкм", "жгутик", "гиф", "мицел", "клетк", "аскоспор", "псевдо", "споранги")
CONDITION_WORDS = ("температур", "влажн", "осадк", "погод", "услови", "перезим", "развит")
PREVENTION_WORDS = ("защитн", "меры борьбы", "устойчив", "профилакт", "севооборот", "сорт")


def fetch(url, cache_path):
    if cache_path.exists():
        return cache_path.read_text(encoding="utf-8")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=40) as response:
        page = response.read().decode("utf-8", "replace")
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(page, encoding="utf-8")
    return page


def plain(fragment):
    text = re.sub(r"<br\s*/?>", " ", fragment)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text)
    text = re.sub(r"https?://\S+", "", text)
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"\s+([.,;:])", r"\1", text)
    text = re.sub(r"(\d)\s*\.\s*[СC](?=\s|$|[,;).])", r"\1 °C", text)
    return text.strip()


def find_section(page_sections, needle):
    """Точное совпадение заголовка важнее вхождения: в навигации есть «Агроэкология»."""
    candidates = [(title, text) for title, text in page_sections.items() if needle in title.lower()]
    if not candidates:
        return ""
    for title, text in candidates:
        if title.lower().strip() == needle:
            return text
    for title, text in candidates:
        if title.lower().startswith(needle):
            return text
    return candidates[-1][1]


def sections(page):
    """Заголовки <h3> и текст до следующего заголовка."""
    result = {}
    parts = re.split(r"<h3>(.*?)</h3>", page, flags=re.S)
    for index in range(1, len(parts) - 1, 2):
        title = plain(parts[index]).rstrip(".").strip()
        result[title] = plain(parts[index + 1])
    return result


def sentences(text):
    return [item.strip() for item in re.split(r"(?<=[.!?])\s+", text) if item.strip()]


def usable(sentence, skip_words=()):
    lowered = sentence.lower()
    if len(sentence) < 25:
        return False
    if any(word in lowered for word in skip_words):
        return False
    if re.search(r"(?:\d\s*\.\s|\s\.\s|\(\s*\))", sentence):
        return False
    if re.search(r"(?:воздуха|минимальная|максимальная|температура|влажность)$", sentence.rstrip(".")):
        return False
    return True


def pick(text, words, limit, skip_words=()):
    """Предложения с признаками, сначала самые информативные, в порядке источника."""
    chosen = []
    for position, sentence in enumerate(sentences(text)):
        if not usable(sentence, skip_words):
            continue
        score = sum(1 for word in words if word in sentence.lower())
        if score:
            chosen.append((score, position, sentence))
    chosen.sort(key=lambda item: (-item[0], item[1]))
    return [sentence for _, _, sentence in chosen[:limit]]


def statements(card, section, texts, locator):
    """Добавляет только новые формулировки, сохраняя порядок источника."""
    known = {item["text"] for item in card[section]}
    added = []
    for text in texts:
        if text in known:
            continue
        card[section].append({"text": text, "source": SOURCE["id"], "locator": locator})
        known.add(text)
        added.append(text)
    return added


def merge(card, page_sections, note):
    locator = "раздел «Морфология и биология»"
    if note:
        locator = f"{locator}; {note}"
    symptoms = pick(find_section(page_sections, "морфолог"), SYMPTOM_WORDS, 3, PATHOGEN_WORDS)
    conditions = pick(find_section(page_sections, "экология"), CONDITION_WORDS, 2)
    prevention = pick(find_section(page_sections, "хозяйствен"), PREVENTION_WORDS, 2)
    added = {
        "symptoms": statements(card, "symptoms", symptoms, locator),
        "conditions": statements(card, "conditions", conditions, "раздел «Экология»"),
        "prevention": statements(card, "prevention", prevention, "раздел «Хозяйственное значение»"),
    }
    for section, items in added.items():
        if items and section in card["missing"]:
            card["missing"].remove(section)
    if any(added.values()) and SOURCE["id"] not in card["sources"]:
        card["sources"].append(SOURCE["id"])
    return added


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    document = json.loads(KB_PATH.read_text(encoding="utf-8"))
    mapping = json.loads(MAP_PATH.read_text(encoding="utf-8"))
    codes = {card["code"] for card in document["cards"]}
    unknown = sorted(set(mapping["pages"]) - codes)
    if unknown:
        sys.exit(f"в соответствии есть классы вне taxonomy: {unknown}")

    if SOURCE["id"] not in {item["id"] for item in document["sources"]} and not args.dry_run:
        document["sources"].append(SOURCE)

    total = 0
    for code, item in sorted(mapping["pages"].items()):
        url = mapping["base"] + item["path"] + "/index.html"
        page = fetch(url, CACHE / (item["path"].replace("/", "_") + ".html"))
        card = next(card for card in document["cards"] if card["code"] == code)
        added = merge(card, sections(page), item.get("note"))
        count = sum(len(value) for value in added.values())
        total += count
        print(f"{code}: +{count} (симптомы {len(added['symptoms'])}, условия {len(added['conditions'])}, "
              f"профилактика {len(added['prevention'])})")
        if args.dry_run:
            for section, texts in added.items():
                for text in texts:
                    print(f"   [{section}] {text}")

    if args.dry_run:
        print(f"всего без записи: {total}")
        return
    KB_PATH.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"записано утверждений: {total}; карточки остаются draft")


if __name__ == "__main__":
    main()
