"""CPU softmax baseline for the authored Russian routing corpus, split by scenario.

Train-only vocabulary/labels; epoch and abstention threshold selected on val.
This small supervised baseline is not a free-dialogue model or an Android feature.
"""
import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import time

os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
os.environ.setdefault("OMP_NUM_THREADS", "2")
import numpy as np

MSK = timezone(timedelta(hours=3))


def features(row):
    text = " ".join(re.findall(r"\w+", row["utterance"].lower().replace("ё", "е")))
    values = Counter("word=" + word for word in text.split())
    padded = " " + text + " "
    for size in (3, 4, 5):
        values.update("char=" + padded[index:index + size] for index in range(len(padded) - size + 1))
    for key, value in sorted(row["state"].items()):
        values["state=" + key + ":" + json.dumps(value, ensure_ascii=False, sort_keys=True)] = 2
    return values


def outcome(target):
    return json.dumps({"decision": target["decision"], "actions": target["actions"]},
                      ensure_ascii=False, sort_keys=True)


def matrix(rows, vocabulary):
    encoded = np.zeros((len(rows), len(vocabulary) + 1), dtype=np.float32)
    for index, row in enumerate(rows):
        for token, count in features(row).items():
            if token in vocabulary:
                encoded[index, vocabulary[token]] = count
        length = np.linalg.norm(encoded[index])
        if length:
            encoded[index] /= length
        encoded[index, -1] = 1
    return encoded


def probabilities(inputs, weights):
    scores = inputs @ weights
    scores -= scores.max(axis=1, keepdims=True)
    scores = np.exp(scores)
    return scores / scores.sum(axis=1, keepdims=True)


def answers(rows, probabilities_, labels, threshold):
    output = []
    for row, distribution in zip(rows, probabilities_):
        index = int(distribution.argmax())
        confidence = float(distribution[index])
        answer = json.loads(labels[index])
        if answer["decision"] == "execute" and confidence < threshold:
            answer = {"decision": "clarify", "actions": []}
        answer["text"] = row["utterance"] if answer["decision"] == "dictate" else ""
        output.append({"id": row["id"], "split": row["split"], "utterance": row["utterance"],
                       "model": "cpu_char_word_softmax", "confidence": confidence,
                       "answer": json.dumps(answer, ensure_ascii=False)})
    return output


def score(rows, predictions):
    correct = 0
    correct_execute = 0
    false_execute = 0
    for row, prediction in zip(rows, predictions):
        answer = json.loads(prediction["answer"])
        matches = outcome(answer) == outcome(row["target"])
        correct += int(matches)
        correct_execute += int(matches and answer["decision"] == "execute")
        false_execute += int(answer["decision"] == "execute" and row["target"]["decision"] != "execute")
    return {"examples": len(rows), "exact_decision_and_actions": correct,
            "correct_executions": correct_execute, "false_executions_on_nonexecute": false_execute}


def check_splits(parts):
    groups = [set(row["group"] for row in rows) for rows in parts.values()]
    texts = [set(row["utterance"].lower().replace("ё", "е").strip() for row in rows) for rows in parts.values()]
    for index in range(len(groups)):
        for other in range(index):
            if groups[index] & groups[other] or texts[index] & texts[other]:
                raise ValueError("Scenario group or utterance crosses splits")


def train(data, out, deadline, epochs=200):
    end = datetime.fromisoformat(deadline)
    now = datetime.now(MSK)
    if end.tzinfo is None or end.utcoffset() != timedelta(hours=3) or end.date() != now.date():
        raise ValueError("Explicit same-day Moscow deadline required")
    if (end.hour, end.minute) != (23, 0) or now >= end - timedelta(minutes=10):
        raise ValueError("Outside authorized training window")
    stop = (end - timedelta(minutes=10)).timestamp()
    parts = {split: [json.loads(line) for line in (data / f"{split}.jsonl").read_text(encoding="utf-8").splitlines()]
             for split in ("train", "val", "test")}
    check_splits(parts)
    import prepare_corpus
    allowed = prepare_corpus.actions_from_kotlin(prepare_corpus.VOICE_SOURCE.read_text(encoding="utf-8"))
    for rows in parts.values():
        for row in rows:
            prepare_corpus.validate_target(row["state"], row["target"], allowed)
    out.mkdir(parents=True, exist_ok=False)
    training = parts["train"]
    vocabulary = {token: index for index, token in enumerate(sorted({key for row in training for key in features(row)}))}
    labels = sorted({outcome(row["target"]) for row in training})
    inputs = matrix(training, vocabulary)
    targets = np.array([labels.index(outcome(row["target"])) for row in training])
    validation = matrix(parts["val"], vocabulary)
    weights = np.zeros((inputs.shape[1], len(labels)), dtype=np.float32)
    best_weights = weights.copy()
    best_key = None
    history = []
    start = time.perf_counter()
    for epoch in range(epochs):
        if time.time() >= stop:
            break
        distribution = probabilities(inputs, weights)
        loss = float(-np.log(distribution[np.arange(len(training)), targets] + 1e-9).mean())
        distribution[np.arange(len(training)), targets] -= 1
        gradient = inputs.T @ distribution / len(training) + 0.0001 * weights
        weights -= 4.0 * gradient
        metrics = score(parts["val"], answers(parts["val"], probabilities(validation, weights), labels, 0))
        key = (-metrics["false_executions_on_nonexecute"], metrics["exact_decision_and_actions"], -loss)
        history.append({"epoch": epoch + 1, "train_loss": loss, "val": metrics})
        if best_key is None or key > best_key:
            best_key = key
            best_weights = weights.copy()
            best_epoch = epoch + 1
    if not history:
        raise RuntimeError("No optimizer step fitted before deadline")
    thresholds = []
    for threshold in (0.0, 0.2, 0.4, 0.6, 0.8):
        metrics = score(parts["val"], answers(parts["val"], probabilities(validation, best_weights), labels, threshold))
        thresholds.append({"threshold": threshold, **metrics})
    selected = max(thresholds, key=lambda item: (-item["false_executions_on_nonexecute"], item["exact_decision_and_actions"], -item["threshold"]))
    predictions = []
    results = {}
    for split, rows in parts.items():
        predicted = answers(rows, probabilities(matrix(rows, vocabulary), best_weights), labels, selected["threshold"])
        predictions.extend(predicted)
        results[split] = score(rows, predicted)
        results[split]["target_outcomes_absent_from_train"] = sum(outcome(row["target"]) not in labels for row in rows)
    np.savez_compressed(out / "weights.npz", weights=best_weights)
    (out / "model.json").write_text(json.dumps({"vocabulary": vocabulary, "labels": labels, "threshold": selected["threshold"]}, ensure_ascii=False), encoding="utf-8")
    (out / "predictions.json").write_text(json.dumps(predictions, ensure_ascii=False, indent=2), encoding="utf-8")
    report = {"model": "char 3/4/5-grams + words + state; L2 softmax, CPU NumPy",
              "data": data.as_posix(),
              "seconds": time.perf_counter() - start, "numpy": np.__version__, "seed": "zero initialization; deterministic full-batch gradient descent",
              "epochs": len(history), "selected_epoch": best_epoch, "threshold": selected["threshold"],
              "vocabulary_train_only": len(vocabulary), "labels_train_only": len(labels),
              "splits": results, "threshold_selection_val_only": thresholds, "history": history,
              "limitations": ["Authored synthetic corpus; no live speech or independent-user acceptance result.",
                              "Template classification cannot generate unseen action/argument combinations.",
                              "Test only evaluated after model/threshold selection; historical corpus was visible to developers.",
                              "No Android model integration; executor validation is a separate check."]}
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--deadline", required=True)
    parser.add_argument("--epochs", type=int, default=200)
    args = parser.parse_args()
    report = train(args.data, args.out, args.deadline, args.epochs)
    print(json.dumps({key: report[key] for key in ("seconds", "selected_epoch", "threshold", "splits")}, ensure_ascii=False))
