"""Тесты отбора веб-фотографий: стоковые и соцсети не должны попадать в набор."""

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ml-pipeline" / "external"))

import prepare_web_search as web  # noqa: E402
import collect_web_images as collect  # noqa: E402


def photo(page_url, image_url="https://cdn.example.org/leaf.jpg"):
    return {"sources": [{"page_url": page_url, "image_url": image_url}]}


class BlockedDomainTests(unittest.TestCase):
    def test_stock_and_social_are_blocked(self):
        for url in (
            "https://www.dreamstime.com/photo",
            "https://www.alamy.com/stock-photo.html",
            "https://ru.pinterest.com/pin/1",
            "https://www.youtube.com/watch?v=1",
            "https://www.ozon.ru/product/1",
        ):
            self.assertTrue(web.blocked(photo(url)), url)

    def test_ordinary_garden_page_is_kept(self):
        self.assertFalse(web.blocked(photo("https://ogorod.ru/bolezni-ogurca")))
        self.assertFalse(web.blocked(photo("https://ag.purdue.edu/tomato-disease")))

    def test_blocked_by_image_host_when_page_is_missing(self):
        self.assertTrue(web.blocked(photo(None, "https://i.pinimg.com/564x/leaf.jpg")))

    def test_split_is_stable_for_a_site(self):
        first = web.split_of("ogorod.ru")
        self.assertEqual(first, web.split_of("ogorod.ru"))
        self.assertIn(first, {"train", "val"})


class ExtraQueryTests(unittest.TestCase):
    def test_extra_phrases_name_real_classes_and_are_unique(self):
        config = json.loads((ROOT/"ml-pipeline/external/web_queries_extra_v1.json").read_text(encoding="utf-8"))
        classes = {item["code"] for item in json.loads(
            (ROOT/"ml-pipeline/allcrops/taxonomy_v1.json").read_text(encoding="utf-8"))["classes"]}
        self.assertTrue(set(config) <= classes)
        for code, queries in config.items():
            self.assertGreaterEqual(len(queries), 3, code)
            self.assertEqual(len(queries), len(set(queries)), code)
            for query in queries:
                self.assertTrue(query.strip(), code)


if __name__ == "__main__":
    unittest.main()
