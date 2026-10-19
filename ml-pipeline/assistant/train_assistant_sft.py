"""Small, bounded Qwen3 LoRA experiment on authored routing examples, not agronomy knowledge."""
import json
import random
import time
import statistics
from pathlib import Path


def score(rows, predictions):
    exact = 0
    execute_exact = 0
    false_execute = 0
    invalid = 0
    for row, prediction in zip(rows, predictions):
        try:
            value = json.loads(prediction["answer"])
            if not isinstance(value, dict) or not isinstance(value.get("actions"), list):
                raise ValueError("Not a protocol object")
        except (ValueError, TypeError):
            invalid += 1
            continue
        target = row["target"]
        executed = value.get("decision") == "execute"
        false_execute += int(executed and target["decision"] != "execute")
        matches = value.get("decision") == target["decision"] and value["actions"] == target["actions"]
        if target["decision"] == "dictate":
            matches = matches and value.get("text") == row["utterance"]
        exact += int(matches)
        execute_exact += int(matches and executed)
    return {"examples": len(rows), "exact_decision_actions_or_dictation": exact,
            "correct_executions": execute_exact, "false_execute_proposals": false_execute, "invalid_json": invalid}


def encode(row, tokenizer, maximum=4096):
    prompt = tokenizer.apply_chat_template(row["messages"][:2], tokenize=True,
        add_generation_prompt=True, enable_thinking=False)
    answer = tokenizer.encode(json.dumps(row["target"], ensure_ascii=False), add_special_tokens=False)
    answer += [tokenizer.eos_token_id]
    if len(prompt) + len(answer) > maximum:
        raise ValueError(f"Refusing to truncate state/answer for {row['id']}")
    return prompt, prompt + answer, [-100] * len(prompt) + answer


def train_sft(args):
    from train_home_pilot import deadlines, write_json
    soft, _ = deadlines(args.deadline)
    import torch
    import transformers
    import peft
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig, get_peft_model
    import prepare_corpus

    if args.model is None or not torch.cuda.is_available() or not torch.version.hip:
        raise RuntimeError("Local model snapshot and working ROCm are required")
    torch.set_num_threads(4)
    torch.manual_seed(20261019)
    parts = {split: [json.loads(line) for line in (args.data / f"{split}.jsonl").read_text(encoding="utf-8").splitlines()]
             for split in ("train", "val", "test")}
    if args.smoke:
        parts = {"train": parts["train"][:8], "val": parts["val"][:4], "test": []}
    from train_intent_classifier import check_splits
    check_splits(parts)
    allowed = prepare_corpus.actions_from_kotlin(prepare_corpus.VOICE_SOURCE.read_text(encoding="utf-8"))
    for rows in parts.values():
        for row in rows:
            prepare_corpus.validate_target(row["state"], row["target"], allowed)
    tokenizer = AutoTokenizer.from_pretrained(args.model, local_files_only=True, trust_remote_code=False)
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    encoded = {split: [encode(row, tokenizer) for row in rows] for split, rows in parts.items()}
    base = AutoModelForCausalLM.from_pretrained(args.model, local_files_only=True, trust_remote_code=False,
        torch_dtype=torch.float32, attn_implementation="eager").to("cuda")
    adapter = LoraConfig(r=8, lora_alpha=16, lora_dropout=0.05, target_modules=["q_proj", "v_proj"],
                         task_type="CAUSAL_LM", bias="none")
    model = get_peft_model(base, adapter)
    model.config.use_cache = False
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    trainable = [parameter for parameter in model.parameters() if parameter.requires_grad]
    optimizer = torch.optim.AdamW(trainable, lr=0.0002)
    write_json(args.out / "environment.json", {"torch": torch.__version__, "transformers": transformers.__version__,
        "peft": peft.__version__, "gpu": torch.cuda.get_device_name(0), "dtype": "float32", "attention": "eager",
        "model_snapshot": str(args.model), "trainable_parameters": sum(parameter.numel() for parameter in trainable),
        "lora": {"r": 8, "alpha": 16, "dropout": 0.05, "targets": ["q_proj", "v_proj"]},
        "learning_rate": 0.0002, "microbatch": 1, "gradient_accumulation": 8,
        "maximum_sequence_tokens": {split: max((len(item[1]) for item in items), default=0) for split, items in encoded.items()},
        "loss_scope": "assistant answer tokens only", "thinking": False, "seed": 20261019})

    def before_step():
        if time.time() >= soft or (args.out / "STOP").exists():
            model.save_pretrained(args.out / "interrupted_adapter")
            raise RuntimeError("Stopped with adapter saved before deadline")

    def loss_for(item):
        _, ids, labels = item
        inputs = torch.tensor([ids], device="cuda")
        targets = torch.tensor([labels], device="cuda")
        return model(input_ids=inputs, attention_mask=torch.ones_like(inputs), labels=targets).loss

    # No optimizer updates during the short hardware/timing check.
    timings = []
    model.train()
    for item in encoded["train"][:3]:
        before_step()
        started = time.monotonic()
        loss = loss_for(item)
        if not torch.isfinite(loss):
            raise RuntimeError("Non-finite warm-up loss")
        loss.backward()
        optimizer.zero_grad(set_to_none=True)
        torch.cuda.synchronize()
        timings.append(time.monotonic() - started)
    write_json(args.out / "benchmark.json", {"forward_backward_seconds": timings, "status": "passed"})
    epochs = 1 if args.smoke else min(args.epochs, 3)
    projected_training = statistics.median(timings[1:]) * len(parts["train"]) * epochs
    if time.time() + projected_training * 2 + 900 >= soft:
        raise RuntimeError("Measured backward speed leaves insufficient time for training and evaluation")

    def predict(split, label):
        model.eval()
        answers = []
        for row, item in zip(parts[split], encoded[split]):
            before_step()
            prompt = torch.tensor([item[0]], device="cuda")
            started = time.monotonic()
            with torch.no_grad():
                output = model.generate(input_ids=prompt, attention_mask=torch.ones_like(prompt),
                    do_sample=False, max_new_tokens=192, use_cache=True, pad_token_id=tokenizer.pad_token_id)
            answer = tokenizer.decode(output[0, prompt.shape[1]:], skip_special_tokens=True)
            answers.append({"id": row["id"], "split": split, "utterance": row["utterance"], "model": label,
                            "answer": answer, "latency_ms": round((time.monotonic() - started) * 1000)})
        return answers

    with model.disable_adapter():
        base_val = predict("val", "Qwen3-0.6B base FP32")
    write_json(args.out / "base_val.json", base_val)
    best_key = None
    best_epoch = None
    history = []
    torch.manual_seed(20261019)
    for epoch in range(epochs):
        model.train()
        optimizer.zero_grad(set_to_none=True)
        order = list(range(len(encoded["train"])))
        random.Random(20261019 + epoch).shuffle(order)
        losses = []
        for position, index in enumerate(order):
            before_step()
            loss = loss_for(encoded["train"][index])
            if not torch.isfinite(loss):
                raise RuntimeError("Non-finite training loss")
            divisor = min(8, len(order) - (position // 8) * 8)
            (loss / divisor).backward()
            losses.append(float(loss.detach().cpu()))
            if (position + 1) % 8 == 0 or position + 1 == len(order):
                torch.nn.utils.clip_grad_norm_(trainable, 1.0, error_if_nonfinite=True)
                optimizer.step()
                optimizer.zero_grad(set_to_none=True)
            if position % 20 == 0:
                print(f"epoch {epoch + 1}, example {position + 1}/{len(order)}, loss {losses[-1]:.4f}", flush=True)
        checkpoint = args.out / f"adapter_epoch_{epoch + 1}"
        model.save_pretrained(checkpoint)
        answers = predict("val", f"Qwen3-0.6B LoRA epoch {epoch + 1}")
        measured = score(parts["val"], answers)
        history.append({"epoch": epoch + 1, "mean_train_loss": sum(losses) / len(losses), "val": measured})
        write_json(args.out / f"val_epoch_{epoch + 1}.json", answers)
        write_json(args.out / "history.json", history)
        key = (-measured["false_execute_proposals"], measured["correct_executions"], measured["exact_decision_actions_or_dictation"])
        if best_key is None or key > best_key:
            best_key, best_epoch = key, epoch + 1
    if best_epoch is None:
        raise RuntimeError("No SFT epoch completed")
    model.load_adapter(args.out / f"adapter_epoch_{best_epoch}", adapter_name="selected")
    model.set_adapter("selected")
    with model.disable_adapter():
        base_test = predict("test", "Qwen3-0.6B base FP32")
    trained_test = predict("test", "Qwen3-0.6B selected LoRA")
    write_json(args.out / "base_test.json", base_test)
    write_json(args.out / "trained_test.json", trained_test)
    write_json(args.out / "report.json", {"selected_epoch": best_epoch, "history": history,
        "smoke": args.smoke, "test_evaluated": not args.smoke,
        "base_val": score(parts["val"], base_val), "base_test": score(parts["test"], base_test),
        "trained_test": score(parts["test"], trained_test),
        "selection": "val only: false execute proposals first, then exact executions",
        "limitations": ["Authored synthetic corpus; not independent speech or an acceptance benchmark.",
                        "Unconstrained HF FP32 generation differs from earlier Ollama JSON-schema baselines.",
                        "Raw text still needs manual review; action metrics do not validate agronomic advice.",
                        "Adapter is not quantized, not installed on Android, and not published."]})
