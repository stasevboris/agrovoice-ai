import importlib.util
from pathlib import Path
import tempfile
import unittest

import numpy as np
from scipy.io import wavfile


SOURCE = Path(__file__).resolve().parents[1] / "assistant/recorded_asr_check.py"
spec = importlib.util.spec_from_file_location("recorded_asr_check", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RecordedAsrCheckTest(unittest.TestCase):
    def test_selection_is_fixed_val_only_and_one_recording_per_sentence(self):
        records = [dict(id=str(index // 2), filename=f"{index}.wav", split="val")
                   for index in range(30)]
        selected = module.select_samples(records)
        self.assertEqual(selected, module.select_samples(list(reversed(records))))
        self.assertEqual(len(selected), 12)
        self.assertEqual(len({item["id"] for item in selected}), 12)
        records[0]["split"] = "train"
        with self.assertRaisesRegex(ValueError, "val"):
            module.select_samples(records)

    def test_float_wav_conversion_clips_and_preserves_samples(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "source.wav"
            target = Path(folder) / "target.wav"
            wavfile.write(source, 16000, np.array([-1.1, -1, 0, 0.5, 1, 1.1], np.float32))
            module.convert_audio(source, target, module.sha256(source))
            rate, pcm = wavfile.read(target)
            self.assertEqual(rate, 16000)
            self.assertEqual(pcm.dtype, np.int16)
            np.testing.assert_array_equal(pcm, [-32768, -32768, 0, 16384, 32767, 32767])
            with self.assertRaisesRegex(ValueError, "SHA"):
                module.convert_audio(source, target, "wrong")

    def test_word_error_counts_include_substitution_deletion_insertion_and_denominator(self):
        self.assertEqual(module.word_errors("Клён, ёлка растут!", "клен елка растут"),
                         dict(substitutions=0, deletions=0, insertions=0, reference_words=3))
        self.assertEqual(module.word_errors("один два три", "один пять три"),
                         dict(substitutions=1, deletions=0, insertions=0, reference_words=3))
        self.assertEqual(module.word_errors("один два три", "один три"),
                         dict(substitutions=0, deletions=1, insertions=0, reference_words=3))
        self.assertEqual(module.word_errors("один три", "один два три"),
                         dict(substitutions=0, deletions=0, insertions=1, reference_words=2))
        self.assertEqual(module.word_errors("один три", ""),
                         dict(substitutions=0, deletions=2, insertions=0, reference_words=2))

    def test_corpus_wer_uses_total_words_not_mean_of_utterance_rates(self):
        cases = [dict(file="a.wav", reference="один", pcm_sha256="a"),
                 dict(file="b.wav", reference="один два три", pcm_sha256="b")]
        rows = []
        for mode in module.MODES:
            rows.extend([dict(file="a.wav", pcm_sha256="a", mode=mode, transcript="", segments=[]),
                         dict(file="b.wav", pcm_sha256="b", mode=mode, transcript="один два три", segments=[])])
        report = module.score(dict(cases=cases), dict(samples=rows, complete=True))
        self.assertEqual(report["corpus"]["dictation"]["wer"], 0.25)
        self.assertEqual(report["corpus"]["dictation"]["reference_words"], 4)
        with self.assertRaisesRegex(ValueError, "duplicate|missing"):
            module.score(dict(cases=cases), dict(samples=rows + rows[:1], complete=True))
        with self.assertRaisesRegex(ValueError, "incomplete"):
            module.score(dict(cases=cases), dict(samples=rows, complete=False))


if __name__ == "__main__":
    unittest.main()
