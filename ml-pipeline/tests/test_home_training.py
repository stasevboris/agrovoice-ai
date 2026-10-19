import importlib.util
from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


pilot = load("home_pilot", "gpu_kit/alternatives/train_home_pilot.py")
intent = load("intent_classifier", "assistant/train_intent_classifier.py")


class HomeWindowTest(unittest.TestCase):
    def test_deadline_has_checkpoint_and_hard_stop_reserves(self):
        now = datetime(2026, 10, 19, 13, tzinfo=timezone(timedelta(hours=3)))
        soft, hard = pilot.deadlines("2026-10-19T23:00:00+03:00", now)
        end = datetime(2026, 10, 19, 23, tzinfo=now.tzinfo).timestamp()
        self.assertEqual(30 * 60, end - soft)
        self.assertEqual(5 * 60, end - hard)

    def test_expired_next_day_or_ambiguous_deadlines_rejected(self):
        now = datetime(2026, 10, 19, 22, 45, tzinfo=timezone(timedelta(hours=3)))
        for value in ("2026-10-19T23:00:00+03:00", "2026-10-20T23:00:00+03:00",
                      "2026-10-19T23:00:00", "2026-10-19T23:30:00+03:00"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                pilot.deadlines(value, now)


class IntentTrainingTest(unittest.TestCase):
    def test_scenario_paraphrases_cannot_cross_splits(self):
        with self.assertRaises(ValueError):
            intent.check_splits({"train": [{"group": "same", "utterance": "first"}],
                                 "val": [{"group": "same", "utterance": "other"}]})

    def test_validation_tokens_never_expand_training_vocabulary(self):
        row = {"utterance": "совсем новое", "state": {"screen": "main"}}
        vocabulary = {"word=известное": 0}
        result = intent.matrix([row], vocabulary)
        self.assertEqual((1, 2), result.shape)
        self.assertEqual(0, result[0, 0])
        self.assertEqual({"word=известное": 0}, vocabulary)

    def test_abstention_never_produces_a_command_and_dictation_copies_text(self):
        import numpy as np
        rows = [{"id": "a", "split": "test", "utterance": "сделай фото"}]
        execute = intent.outcome({"decision": "execute", "actions": [{"action": "CAMERA", "value": ""}]})
        clarify = intent.outcome({"decision": "clarify", "actions": []})
        prediction = intent.answers(rows, np.array([[0.6, 0.4]]), [execute, clarify], 0.8)
        import json
        self.assertEqual([], json.loads(prediction[0]["answer"])["actions"])
        dictate = intent.outcome({"decision": "dictate", "actions": []})
        prediction = intent.answers(rows, np.array([[1.0]]), [dictate], 0.8)
        self.assertEqual("сделай фото", json.loads(prediction[0]["answer"])["text"])
