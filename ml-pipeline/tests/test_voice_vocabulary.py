"""Every word of every fixed voice command must exist in the Vosk model vocabulary.

Vosk silently drops grammar words it does not know ("Ignoring word missing in vocabulary"), so such
phrases can never be recognised. Found on the phone on 17.10.2026: «листай», «скрой».
"""
import re
import struct
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
FST = ROOT/"android-app/app/src/main/assets/voice/ru/graph/Gr.fst"
COMMANDS = ROOT/"android-app/app/src/main/java/com/agronomy/phytomonitor/voice/VoiceCommands.kt"
SYMBOL_TABLE_MAGIC = struct.pack("<i", 2125658996)  # OpenFst SymbolTable


def vocabulary(fst_bytes: bytes) -> set:
    position = fst_bytes.find(SYMBOL_TABLE_MAGIC)
    while position >= 0:
        offset = position+4
        (name_length,) = struct.unpack_from("<i", fst_bytes, offset)
        offset += 4+name_length
        _available_key, size = struct.unpack_from("<qq", fst_bytes, offset)
        offset += 16
        if 0 < size < 5_000_000:
            words = set()
            for _ in range(size):
                (length,) = struct.unpack_from("<i", fst_bytes, offset)
                offset += 4
                words.add(fst_bytes[offset:offset+length].decode("utf-8"))
                offset += length+8  # word bytes + int64 key
            return words
        position = fst_bytes.find(SYMBOL_TABLE_MAGIC, position+1)
    return set()


def command_phrases() -> set:
    """Quoted lowercase Russian phrases in VoiceCommands.kt (aliases, grammar extras, number words)."""
    return set(re.findall(r'"([а-яё][а-яё ]*)"', COMMANDS.read_text(encoding="utf-8")))


@unittest.skipUnless(FST.exists(), "Vosk model assets are not present (run android-app/fetch_assets.py)")
class VoiceVocabularyTest(unittest.TestCase):
    def test_every_command_word_is_in_vosk_vocabulary(self):
        words = vocabulary(FST.read_bytes())
        self.assertGreater(len(words), 100_000)
        missing = {}
        for phrase in command_phrases():
            for word in phrase.split():
                if word not in words:
                    missing.setdefault(word, []).append(phrase)
        # 'четвертое' is only a parser alias for 'четвёртое'; the grammar uses the ё form.
        missing.pop("четвертое", None)
        self.assertEqual(missing, {}, "Vosk cannot recognise these words; rephrase the commands")


if __name__ == "__main__":
    unittest.main()
