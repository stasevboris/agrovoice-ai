"""Тесты разбора Агроэкологического атласа для справочника (без сети)."""

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "kb"))

import extract_agroatlas  # noqa: E402

PAGE = """
<h3>Агроэкология</h3>навигация<h3>Морфология и биология.</h3>
Поражаются листья и черешки. На листьях появляются бурые пятна с темным налетом.
Конидиеносцы простые, конидии 12-47 мкм, расположены цепочками.
<h3>Экология.</h3>Развитию болезни способствуют дожди и температура 15-22.С.
<h3>Хозяйственное значение.</h3>Защитные мероприятия: севооборот и устойчивые сорта.
"""


class SectionTests(unittest.TestCase):
    def test_sections_split_by_headings(self):
        parsed = extract_agroatlas.sections(PAGE)
        self.assertIn("Морфология и биология", parsed)
        self.assertTrue(parsed["Экология"].startswith("Развитию болезни"))

    def test_exact_heading_wins_over_navigation(self):
        parsed = extract_agroatlas.sections(PAGE)
        self.assertTrue(extract_agroatlas.find_section(parsed, "экология").startswith("Развитию"))

    def test_pathogen_morphology_is_not_a_symptom(self):
        text = extract_agroatlas.sections(PAGE)["Морфология и биология"]
        picked = extract_agroatlas.pick(text, extract_agroatlas.SYMPTOM_WORDS, 3, extract_agroatlas.PATHOGEN_WORDS)
        self.assertGreaterEqual(len(picked), 1)
        self.assertIn("бурые пятна", " ".join(picked))
        self.assertNotIn("мкм", " ".join(picked))

    def test_temperature_spacing_is_repaired(self):
        text = extract_agroatlas.plain("оптимум 15-22.С, влажность 80%")
        self.assertIn("15-22 °C", text)

    def test_trailing_incomplete_sentence_is_dropped(self):
        self.assertFalse(extract_agroatlas.usable("Оптимальная температура для роста, относительная влажность воздуха."))


class MappingTests(unittest.TestCase):
    def test_mapping_codes_exist_in_taxonomy(self):
        taxonomy = json.loads((ROOT / "ml-pipeline" / "allcrops" / "taxonomy_v1.json").read_text(encoding="utf-8"))
        codes = {item["code"] for item in taxonomy["classes"]}
        mapping = json.loads((ROOT / "kb" / "agroatlas_map_v1.json").read_text(encoding="utf-8"))
        self.assertTrue(mapping["pages"])
        self.assertEqual(sorted(set(mapping["pages"]) - codes), [])

    def test_every_mapped_page_has_note(self):
        mapping = json.loads((ROOT / "kb" / "agroatlas_map_v1.json").read_text(encoding="utf-8"))
        for code, item in mapping["pages"].items():
            self.assertTrue(item["path"].count("/") == 1, code)
            self.assertNotIn("http", item["path"], code)


if __name__ == "__main__":
    unittest.main()
