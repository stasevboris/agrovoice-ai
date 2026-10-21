"""Knowledge base format (K-01): the real skeleton is valid and each safety rule rejects its violation."""

import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
HAS_JSONSCHEMA = importlib.util.find_spec("jsonschema") is not None
if HAS_JSONSCHEMA:
    sys.path.insert(0, str(ROOT / "kb"))
    import validate_kb


def statement(text="TEST: описание из источника", source="B03"):
    return {"text": text, "source": source, "locator": "с. 1"}


def treatment(source="B01", **changes):
    item = {
        "product": "TEST-препарат", "active_substance": "TEST-вещество", "rate": "TEST", "unit": "TEST",
        "max_treatments": 2, "waiting_days": 20, "hazard_class": None, "bees_class": None,
        "registered_in": "BY", "register_year": 2026, "register_entry": "TEST-запись", "user_category": "professional",
        "source": source, "locator": "TEST-строка"
    }
    item.update(changes)
    return item


@unittest.skipUnless(HAS_JSONSCHEMA, "jsonschema is not installed")
class KnowledgeBaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = validate_kb.load(validate_kb.KB)
        cls.taxonomy = validate_kb.load(ROOT / cls.base["taxonomy"])
        cls.schema = validate_kb.load(validate_kb.SCHEMA)

    def setUp(self):
        self.document = copy.deepcopy(self.base)

    def card(self, code):
        return next(card for card in self.document["cards"] if card["code"] == code)

    def problems(self):
        return validate_kb.validate(self.document, self.taxonomy, self.schema)

    def assertProblem(self, fragment):
        problems = self.problems()
        self.assertTrue(any(fragment in problem for problem in problems), problems)

    def verified_potato(self):
        card = self.card("potato__late_blight")
        card.update(status="verified", verified_by="TEST: специалист", verified_at="2026-10-19",
                    review_due="2027-04-01", sources=["B01", "B03"], missing=[])
        for section in validate_kb.STATEMENT_SECTIONS:
            card[section] = [statement()]
        card["treatments"] = [treatment()]
        return card

    def test_real_skeleton_is_valid_and_covers_taxonomy(self):
        self.assertEqual([], self.problems())
        codes = [card["code"] for card in self.document["cards"]]
        self.assertEqual([item["code"] for item in self.taxonomy["classes"]], codes)
        self.assertTrue(all(card["status"] == "draft" for card in self.document["cards"]))
        treatments = [item for card in self.document["cards"] for item in card["treatments"]]
        # Register rows are copied with their page; they stay unchecked drafts until a specialist reviews them.
        self.assertTrue(all(item["source"] == "B01" and ", с. " in item["locator"] for item in treatments))

    def test_complete_verified_card_is_accepted(self):
        self.verified_potato()
        self.assertEqual([], self.problems())

    def test_verified_needs_expert_dates_sources_and_no_gaps(self):
        card = self.verified_potato()
        card["verified_by"] = None
        self.assertProblem("без имени специалиста")
        card["verified_by"] = "TEST"
        card["review_due"] = "2026-10-01"
        self.assertProblem("дата пересмотра")
        card["review_due"] = "2027-04-01"
        card["agrotech"] = []
        card["missing"] = ["agrotech"]
        self.assertProblem("незаполненными разделами agrotech")

    def test_products_only_from_the_belarus_register(self):
        card = self.verified_potato()
        card["treatments"] = [treatment(source="B03")]
        self.assertProblem("не из Госреестра РБ")
        card["treatments"] = [treatment(registered_in="RU")]
        self.assertProblem("схема:")

    def test_rate_in_free_text_is_rejected(self):
        card = self.verified_potato()
        card["prevention"] = [statement("TEST: опрыскивать 2 л/га")]
        self.assertProblem("норма в тексте")

    def test_every_reference_is_resolvable_and_declared(self):
        card = self.verified_potato()
        card["symptoms"] = [statement(source="X99")]
        self.assertProblem("нет источника X99")
        card["symptoms"] = [statement(source="B04")]
        self.assertProblem("B04 использован, но не указан")

    def test_absence_of_data_is_explicit(self):
        card = self.card("tomato__late_blight")
        card["missing"].remove("symptoms")
        self.assertProblem("symptoms пуст без явной отметки")
        card["missing"].append("symptoms")
        card["symptoms"] = [statement()]
        card["sources"] = ["B03"]
        self.assertProblem("заполнен, но отмечен как пустой")
        card["symptoms"] = []
        card["none_found"] = ["symptoms"]
        self.assertProblem("одновременно")

    def test_products_not_applicable_to_healthy_leaf_or_orange(self):
        self.card("tomato__healthy").update(treatments=[treatment()], sources=["B01"])
        self.assertProblem("tomato__healthy: раздел treatments не применим")
        self.document = copy.deepcopy(self.base)
        self.card("orange__huanglongbing").update(treatments=[treatment()], sources=["B01"])
        self.assertProblem("orange__huanglongbing: раздел treatments не применим")

    def test_cards_match_taxonomy(self):
        self.document["cards"] = [card for card in self.document["cards"] if card["code"] != "corn__common_rust"]
        self.assertProblem("corn__common_rust: нет карточки")
        self.document = copy.deepcopy(self.base)
        self.card("potato__healthy")["crop"] = "tomato"
        self.assertProblem("не совпадают с taxonomy")
        self.document = copy.deepcopy(self.base)
        extra = copy.deepcopy(self.card("potato__healthy"))
        extra["code"] = "potato__invented"
        self.document["cards"].append(extra)
        self.assertProblem("potato__invented: класса нет в taxonomy_v1")

    def test_summary_counts_gaps(self):
        summary = validate_kb.summary(self.document)
        self.assertEqual(49, summary["cards"])
        self.assertEqual({"draft": 49}, summary["status"])
        self.assertEqual(49, summary["missing_sections"]["agrotech"])


if __name__ == "__main__":
    unittest.main()
