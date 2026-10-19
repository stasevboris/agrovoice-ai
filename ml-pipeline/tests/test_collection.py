import json
from pathlib import Path
import sys
import tempfile
import unittest
import wave

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "collection"))
from evaluate_collection import evaluate
from validate_collection import validate


class CollectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def image(self, identity="a", split="val", **changes):
        # Bytes are fixture data, never counted as actual field observations.
        Image.new("RGB", (16, 16), ((ord(identity[0]) * 31) % 256, 20, 30)).save(self.root / f"{identity}.jpg")
        return dict(id=identity, file=f"{identity}.jpg", modality="image", split=split,
                    source="test-only", source_kind="real", series=f"series-{identity}",
                    site=f"site-{identity}", plant=f"plant-{identity}", scenario="known_leaf",
                    label="potato__late_blight", label_source="TEST expert", rights="fixture",
                    light="daylight", **changes)

    def test_empty_is_awaiting_data_not_success(self):
        self.assertEqual("awaiting_data", validate([], self.root)["status"])

    def test_groups_and_exact_duplicates_do_not_cross_splits(self):
        first = self.image()
        second = self.image("b", "test")
        self.assertEqual("valid", validate([first, second], self.root)["status"])
        for key in ("plant", "series", "site"):
            changed = dict(second, **{key: first[key]})
            self.assertTrue(any("split leakage" in error for error in validate([first, changed], self.root)["errors"]))
        (self.root / "b.jpg").write_bytes((self.root / "a.jpg").read_bytes())
        self.assertEqual("invalid", validate([first, second], self.root)["status"])

    def test_unknown_labels_paths_and_real_light_pairs(self):
        first = self.image()
        first.update(scenario="unknown_crop")
        self.assertEqual("invalid", validate([first], self.root)["status"])
        first.update(label="TEST unknown species")
        self.assertEqual("valid", validate([first], self.root)["status"])
        self.assertEqual("invalid", validate([dict(first, file="../outside.jpg")], self.root)["status"])
        first.update(pair="pair-a", light="light")
        self.assertEqual("invalid", validate([first], self.root)["status"])
        second = self.image("b")
        second.update(pair="pair-a", light="shadow", plant=first["plant"], label=first["label"], scenario=first["scenario"])
        self.assertEqual("valid", validate([first, second], self.root)["status"])

    def test_speech_requires_consent_state_and_independent_speakers(self):
        (self.root / "state.json").write_text(json.dumps({"screen": "main"}), encoding="utf-8")
        row = self.image()
        row.update(modality="audio", scenario="command", speaker="person-a", transcript="TEST command",
                   consent="evaluation", consent_ref="local consent", expected_actions="[]", context="state.json")
        with wave.open(str(self.root / "a.wav"), "wb") as recording:
            recording.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            recording.writeframes(b"\x00\x00" * 160)
        row["file"] = "a.wav"
        self.assertEqual("valid", validate([row], self.root)["status"])
        self.assertEqual("invalid", validate([dict(row, split="train")], self.root)["status"])
        self.assertEqual("invalid", validate([dict(row, consent_ref="")], self.root)["status"])
        self.assertEqual("invalid", validate([dict(row, context="missing.json")], self.root)["status"])
        self.assertEqual("invalid", validate([dict(row, source_kind="synthetic")], self.root)["status"])
        second = self.image("b", "test")
        second.update({key: row[key] for key in ("modality", "scenario", "speaker", "transcript", "consent", "consent_ref", "expected_actions", "context")})
        second["file"] = "a.wav"
        self.assertTrue(any("speaker" in error for error in validate([row, second], self.root)["errors"]))

    def test_evaluation_counts_refusal_wrong_acceptance_and_false_action(self):
        rows = [self.image(), self.image("b"), self.image("c")]
        rows[1].update(scenario="unknown_crop", label="TEST species")
        rows[2].update(modality="audio", scenario="background", expected_actions="[]")
        predictions = [{"id": "a", "accepted": False}, {"id": "b", "accepted": True, "label": "peach__healthy"},
                       {"id": "c", "actions": [{"action": "SAVE_INSPECTION", "value": ""}]}]
        result = evaluate(rows, predictions, "val")
        self.assertEqual(3, len(result["errors"]))
        self.assertEqual(1, result["groups"]["image/unknown_crop"]["wrong_accepted"])
        self.assertEqual(1, result["groups"]["audio/background"]["false_actions"])
        self.assertIsNone(result["groups"]["image/known_leaf"]["risk_among_accepted"])
        for invalid in (predictions[:-1], predictions + [predictions[0]]):
            with self.assertRaises(ValueError):
                evaluate(rows, invalid, "val")


if __name__ == "__main__":
    unittest.main()
