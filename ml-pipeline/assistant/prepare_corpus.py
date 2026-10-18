"""Build a small, explicitly synthetic Russian action-routing SFT/probe corpus."""

import argparse
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).with_name("scenarios.json")
VOICE_SOURCE = ROOT / "android-app/app/src/main/java/com/agronomy/phytomonitor/voice/VoiceCommands.kt"

SYSTEM = """Ты — маршрутизатор команд AgroVoice. Вход: JSON со state и utterance.
Верни только JSON с decision (execute, clarify, reject, dictate), actions и text.
actions — список объектов action/value. Предлагай только перечисленные действия.
Не сообщай об успешном выполнении: исполнитель ещё не запускался.
В режиме note всё произнесённое — текст заметки: dictate, actions=[], text=utterance.
В остальных режимах отрицание запрещает соответствующее действие; явную отмену
текущего действия передай CANCEL. Исправление «не картофель, а томат» выбирает томат.
Нет фотографии — уточни перед анализом. Нет результата — уточни перед чтением
или сохранением. Если анализ идёт, дождись завершения. Номер фото существует
только в gallery и лежит от 1 до gallery_count. CONFIRM требует pending либо
выбранного фото в gallery. Без контекста «да» требует уточнения.
Системные действия Android, удаление, отправка и медицинские/агрономические
назначения не входят в этот протокол. Нет verified_kb — не придумывай лечение.
Порог приложения: 10..80. CROP: Tomato, Potato, Cucumber, all.
MODEL: только available_models. Составные команды выполняются последовательно
после проверки результата каждого шага; список — план, а не факт исполнения.
"""


def actions_from_kotlin(text):
    match = re.search(r"enum class VoiceAction\s*\{([^}]+)\}", text)
    if not match:
        raise ValueError("VoiceAction enum not found")
    return {action.strip() for action in match.group(1).split(",")}


def validate_target(state, target, allowed):
    decision = target["decision"]
    if decision not in {"execute", "clarify", "reject", "dictate"}:
        raise ValueError("Invalid decision")
    if (decision == "execute") != bool(target["actions"]):
        raise ValueError("Only execute can contain actions")
    if state["screen"] == "note" and decision != "dictate":
        raise ValueError("Dictation cannot trigger commands")
    for item in target["actions"]:
        action, value = item["action"], item["value"]
        if action not in allowed:
            raise ValueError(f"Action is absent from app: {action}")
        if action in {"ANALYZE", "COMPARE"} and not state["has_photo"]:
            raise ValueError("Photo required")
        if action in {"SAVE_INSPECTION", "SPEAK_RESULT", "ASSESS"}:
            if not state["has_result"] or state["analysis_running"]:
                raise ValueError("Completed result required")
        if action == "PHOTO_NUMBER":
            if state["screen"] != "gallery" or not 1 <= int(value) <= state["gallery_count"]:
                raise ValueError("Invalid photo number/context")
        if action == "CONFIRM" and not state["pending"]:
            if state["screen"] != "gallery" or not state["selected_photo"]:
                raise ValueError("Confirmation has no pending object")
        if action == "CROP" and value not in {"Tomato", "Potato", "Cucumber", "all"}:
            raise ValueError("Unsupported crop")
        if action == "MODEL" and value not in state["available_models"]:
            raise ValueError("Unavailable model")
        if action == "THRESHOLD" and not 10 <= int(value) <= 80:
            raise ValueError("Invalid threshold")


def build_records(scenarios, allowed):
    seen_ids = set()
    seen_inputs = set()
    rows = []
    system = SYSTEM + "\nРазрешённые action: " + ", ".join(sorted(allowed))
    for scenario in scenarios:
        group = scenario["id"]
        if group in seen_ids or scenario["split"] not in {"train", "val", "test"}:
            raise ValueError("Duplicate group or invalid split")
        seen_ids.add(group)
        state = {"screen": scenario["screen"], "has_photo": False, "has_result": False,
                 "analysis_running": False, "gallery_count": 0, "selected_photo": None,
                 "pending": None, "speaking": False, "help_open": False, "verified_kb": False,
                 "available_models": ["yolo26n", "yolo26s", "yolo11n"]}
        state.update(scenario.get("state", {}))
        for index, utterance in enumerate(scenario["utterances"]):
            # Exact paraphrases must not leak across scenario splits.
            normalized = re.sub(r"[^\w]+", " ", utterance.lower().replace("ё", "е")).strip()
            if normalized in seen_inputs:
                raise ValueError(f"Duplicate utterance: {utterance}")
            seen_inputs.add(normalized)
            target = {"decision": scenario.get("decision", "execute"),
                      "actions": [{"action": a, "value": v} for a, v in scenario.get("actions", [])],
                      "text": scenario.get("text", "")}
            if target["decision"] == "dictate":
                target["text"] = utterance
            validate_target(state, target, allowed)
            user = {"state": state, "utterance": utterance}
            rows.append({"id": f"{group}:{index}", "group": group, "split": scenario["split"],
                         "provenance": "authored_synthetic", "state": state, "utterance": utterance,
                         "target": target, "messages": [
                             {"role": "system", "content": system},
                             {"role": "user", "content": json.dumps(user, ensure_ascii=False)},
                             {"role": "assistant", "content": json.dumps(target, ensure_ascii=False)}]})
    return rows


def prepare(out):
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    allowed = actions_from_kotlin(VOICE_SOURCE.read_text(encoding="utf-8"))
    rows = build_records(source["scenarios"], allowed)
    out.mkdir(parents=True, exist_ok=False)
    for split in ("train", "val", "test"):
        with (out / f"{split}.jsonl").open("w", encoding="utf-8") as stream:
            for row in rows:
                if row["split"] == split:
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    report = {"counts": dict(Counter(r["split"] for r in rows)),
              "groups": dict(Counter(s["split"] for s in source["scenarios"])),
              "decisions": dict(Counter(r["target"]["decision"] for r in rows)),
              "source": "ml-pipeline/assistant/scenarios.json", "license": source["license"],
              "provenance": source["provenance"],
              "limitations": ["Seed SFT corpus and synthetic regression probe, not a user benchmark.",
                               "No recorded voices, no measured model accuracy.",
                               "Split by authored scenario group; no claim of semantic independence.",
                               "Contextual JSON dispatcher is a target protocol, not implemented in Android.",
                               "Test is a development regression set; collect independent speakers/scenarios for acceptance.",
                               "FunctionGemma needs its native tool template; do not train it blindly on Qwen chat format."]}
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "ml-pipeline/dataset_assistant_ru_v1")
    prepare(parser.parse_args().out)
