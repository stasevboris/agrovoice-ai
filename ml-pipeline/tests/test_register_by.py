import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "kb"))

import extract_register_by as extract  # noqa: E402
import fill_register_treatments as fill  # noqa: E402


def row(**changes):
    base = {"section": "fungicide", "user_category": "professional", "page": 26, "header_unit": "л/га, кг/га",
            "product_cell": "СИНЕКУРА, СТС (металаксил-М, 40 г/кг), ФАДЕР, Польша", "product": "СИНЕКУРА",
            "formulation": "СТС", "active_substance": "металаксил-М, 40 г/кг", "applicant": "ФАДЕР, Польша",
            "rate": "2,5", "crop_text": "Картофель", "crops": ["potato"], "object": "Фитофтороз, альтернариоз",
            "method": "Опрыскивание в период вегетации", "waiting_days": "43", "max_treatments": "3"}
    base.update(changes)
    return base


class RegisterExtractionTests(unittest.TestCase):
    def test_numeric_range_split_over_lines_stays_a_range(self):
        self.assertEqual("0,15-0,25", extract.clean("0,15-\n0,25"))
        self.assertEqual("Фитофтороз, альтернариоз", extract.clean("Фитофтороз, аль-\nтернариоз"))
        self.assertEqual("0,04", extract.normalize_rate("0, 04"))

    def test_product_cell_parts(self):
        parts = extract.split_product("ИНТРАДА, СК (азоксистробин, 250 г/л), АО Фирма «Август», Россия (Р), (П-3)")
        self.assertEqual(("ИНТРАДА", "СК", "азоксистробин, 250 г/л"),
                         (parts["product"], parts["formulation"], parts["active_substance"]))
        self.assertTrue(extract.starts_product("ВИЗАРД 200 РП (ацетамиприд, 200 г/кг), Мексика"))
        self.assertFalse(extract.starts_product("ООО «АДАМА РУС», Россия (Р), (П-3)"))
        self.assertFalse(extract.starts_product("Сингента Кроп Протекшн АГ, Швейцария (Р), (П-3)"))

    def test_crop_words(self):
        self.assertEqual(["tomato"], extract.crops_of("Томат защищенного грунта"))
        self.assertEqual([], extract.crops_of("Рапс озимый"))


class RegisterFillTests(unittest.TestCase):
    def test_row_is_copied_without_conversion(self):
        item = fill.treatment(row(), "potato__late_blight")
        self.assertEqual(("2,5", 43, 3, "BY", "B01"),
                         (item["rate"], item["waiting_days"], item["max_treatments"], item["registered_in"], item["source"]))
        self.assertIn("с. 26", item["locator"])

    def test_non_numeric_limits_stay_in_note(self):
        item = fill.treatment(row(max_treatments="1-2", waiting_days="-"), "potato__late_blight")
        self.assertIsNone(item["max_treatments"])
        self.assertIsNone(item["waiting_days"])
        self.assertIn("Кратность в реестре: 1-2", item["note"])

    def test_matching_needs_crop_and_harmful_object(self):
        self.assertTrue(fill.matches(row(), "potato__late_blight"))
        self.assertFalse(fill.matches(row(), "tomato__late_blight"))
        self.assertFalse(fill.matches(row(object="Колорадский жук"), "potato__late_blight"))
        # "Бактериоз" is a different disease from bacterial wilt and must not match it.
        self.assertFalse(fill.matches(row(crops=["cucumber"], object="Бактериоз"), "cucumber__bacterial_wilt"))


if __name__ == "__main__":
    unittest.main()
