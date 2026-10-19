"""Ask a local Ollama model to route the assistant corpus (zero-shot, no training).

Usage: python -X utf8 ml-pipeline/assistant/run_ollama_router.py --model qwen3:0.6b
Writes one JSON line per utterance: the model answer as text, untouched. The Android executor judges the
answers in RulesBaselineTest-style tests (LlmRouterBaselineTest); this script only collects them.
Standard library only; Ollama must be running on 127.0.0.1:11434.
"""

import argparse
import json
import time
import urllib.request
from pathlib import Path

import prepare_corpus

ROOT = Path(__file__).resolve().parents[2]
# The answer shape of the protocol; Ollama turns it into constrained decoding, like a llama.cpp grammar.
ANSWER_SCHEMA = {
    "type": "object",
    "properties": {
        "decision": {"enum": ["execute", "clarify", "reject", "dictate"]},
        "actions": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"action": {"type": "string"}, "value": {"type": "string"}},
                "required": ["action", "value"],
            },
        },
        "text": {"type": "string"},
    },
    "required": ["decision", "actions", "text"],
}


def ask(model, messages, host):
    body = {
        "model": model,
        "messages": messages,
        "stream": False,
        "think": False,
        "format": ANSWER_SCHEMA,
        "options": {"temperature": 0, "seed": 20261019},
    }
    request = urllib.request.Request(f"{host}/api/chat", data=json.dumps(body).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", default="qwen3:0.6b")
    parser.add_argument("--host", default="http://127.0.0.1:11434")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    source = json.loads(prepare_corpus.SOURCE.read_text(encoding="utf-8"))
    allowed = prepare_corpus.actions_from_kotlin(prepare_corpus.VOICE_SOURCE.read_text(encoding="utf-8"))
    rows = prepare_corpus.build_records(source["scenarios"], allowed)
    name = args.model.replace(":", "_").replace("/", "_")
    out = args.out or ROOT / f"ml-pipeline/.cache/assistant_llm/{name}.jsonl"
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as stream:
        for row in rows:
            started = time.perf_counter()
            reply = ask(args.model, row["messages"][:2], args.host)
            record = {
                "id": row["id"], "split": row["split"], "utterance": row["utterance"],
                "model": args.model, "answer": reply["message"]["content"],
                "latency_ms": round((time.perf_counter() - started) * 1000),
            }
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
    print(json.dumps({"model": args.model, "rows": len(rows), "out": str(out)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
