import hashlib
import json

import pytest


def test_prepared_model_requires_release_evidence_and_hash(tmp_path):
    from prepare_update_feed import prepared_models

    model = tmp_path / "sample.onnx"
    model.write_bytes(b"synthetic test fixture, not a model")
    with pytest.raises(ValueError):
        prepared_models(tmp_path)
    for name in ("MODEL_CARD.md", "RELEASE_NOTES.md", "LICENSE", "ATTRIBUTION.md"):
        (tmp_path / name).write_text("test", encoding="utf-8")
    sha = hashlib.sha256(model.read_bytes()).hexdigest()
    (tmp_path / "SHA256SUMS.txt").write_text(f"{sha}  sample.onnx\n", encoding="utf-8")
    metadata = {"family": "yolo", "dataset": "allcrops_v1", "sha256": sha,
                "classes": ["test"], "labels_ru": ["test"]}
    (tmp_path / "sample.manifest_entry.json").write_text(json.dumps(metadata), encoding="utf-8")
    files = prepared_models(tmp_path)
    assert files[0][1]["sha256"] == sha
    assert files[0][1]["metadata"]["manifest_entry"] == metadata
    model.write_bytes(b"corrupt")
    with pytest.raises(ValueError, match="hash"):
        prepared_models(tmp_path)
