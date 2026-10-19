import importlib.util
from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest
import tempfile
import json


ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


pilot = load("home_pilot", "gpu_kit/alternatives/train_home_pilot.py")
intent = load("intent_classifier", "assistant/train_intent_classifier.py")
probe = load("crop_probe", "gpu_kit/alternatives/train_crop_probe.py")
evaluation = load("pilot_evaluation", "gpu_kit/alternatives/evaluate_home_pilot.py")
sft = load("assistant_sft", "assistant/train_assistant_sft.py")


class AssistantSftTest(unittest.TestCase):
    def test_only_assistant_tokens_are_labelled_and_long_json_is_not_truncated(self):
        class Tokenizer:
            eos_token_id = 9

            def apply_chat_template(self, *args, **kwargs):
                return [1, 2, 3]

            def encode(self, *args, **kwargs):
                return [4, 5]

        row = {"id": "fixture", "messages": [{}, {}], "target": {"decision": "reject", "actions": []}}
        prompt, tokens, labels = sft.encode(row, Tokenizer())
        self.assertEqual([1, 2, 3], prompt)
        self.assertEqual([1, 2, 3, 4, 5, 9], tokens)
        self.assertEqual([-100, -100, -100, 4, 5, 9], labels)
        with self.assertRaisesRegex(ValueError, "truncate"):
            sft.encode(row, Tokenizer(), maximum=5)

    def test_invalid_answers_false_actions_and_dictation_are_distinguished(self):
        rows = [{"utterance": "не снимай", "target": {"decision": "reject", "actions": []}},
                {"utterance": "заметка", "target": {"decision": "dictate", "actions": [], "text": "заметка"}},
                {"utterance": "x", "target": {"decision": "clarify", "actions": []}}]
        answers = [{"answer": json.dumps({"decision": "execute", "actions": [{"action": "CAMERA", "value": ""}]})},
                   {"answer": json.dumps({"decision": "dictate", "actions": [], "text": "другой текст"})},
                   {"answer": "not json"}]
        result = sft.score(rows, answers)
        self.assertEqual(1, result["false_execute_proposals"])
        self.assertEqual(1, result["invalid_json"])
        self.assertEqual(0, result["exact_decision_actions_or_dictation"])


class PilotEvaluationTest(unittest.TestCase):
    def test_prediction_batches_are_bounded_without_losing_images(self):
        images = list(range(19))
        batches = list(evaluation.batches(images))
        self.assertEqual([8, 8, 3], [len(batch) for batch in batches])
        self.assertEqual(images, [image for batch in batches for image in batch])

    def test_only_validation_images_are_accepted_for_rejection_probe(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for folder, filename in (("dataset_nonplant_openimages_v2", "manifest.jsonl"),
                                     ("dataset_unknown_beans_screened_v1", "val.jsonl"),
                                     ("dataset_unknown_deepweeds_v1", "val.jsonl")):
                (root / folder).mkdir()
                rows = [{"split": split, "image": f"{split}.jpg"} for split in ("train", "val", "test")]
                (root / folder / filename).write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
            selected = evaluation.validation_sources(root)
            self.assertTrue(all(len(paths) == 1 and paths[0].endswith("val.jpg") for paths in selected.values()))


class CropProbeTest(unittest.TestCase):
    def test_test_rows_are_not_used_and_groups_cannot_cross_splits(self):
        rows = [{"group": "a", "split": "train", "domain": "lab"},
                {"group": "b", "split": "val", "domain": "field"},
                {"group": "c", "split": "val", "domain": "lab"},
                {"group": "d", "split": "test", "domain": "field"}]
        parts = probe.splits(rows)
        self.assertEqual(3, sum(len(items) for items in parts.values()))
        self.assertEqual([rows[1]], parts["val_field"])
        rows[-1]["group"] = "a"
        with self.assertRaisesRegex(ValueError, "group crosses"):
            probe.splits(rows)

    def test_macro_f1_reports_only_classes_present_in_validation(self):
        measured = probe.metrics([0, 0, 1], [0, 1, 1])
        self.assertAlmostEqual(2 / 3, measured["macro_f1_present_classes"])
        self.assertAlmostEqual(2 / 3, measured["accuracy"])
        self.assertEqual([2, 1], measured["support"][:2])


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
