import hashlib
import json

import pytest

from tests.test_api import client, database, migrate_test_database  # noqa: F401
from tests.test_sync import auth  # noqa: F401


@pytest.fixture
def feed(tmp_path, monkeypatch):
    body = b'{"schema":"agrovoice-kb-v1","kb_version":"test"}'
    (tmp_path / "kb.json").write_bytes(body)
    entry = {"id": "kb-test", "kind": "kb", "version": "test", "file_name": "kb.json",
             "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest(),
             "metadata": {"status": "draft"}}
    index = {"schema_version": 1, "releases": [entry]}
    (tmp_path / "index.json").write_text(json.dumps(index), encoding="utf-8")
    monkeypatch.setenv("RELEASES_DIR", str(tmp_path))
    return tmp_path, index, body


def test_updates_require_bearer_and_download_matches_metadata(client, auth, feed):
    root, index, body = feed
    assert client.get("/updates").status_code == 401
    assert client.get("/updates/kb-test/download").status_code == 401
    response = client.get("/updates", headers=auth)
    assert response.status_code == 200
    entry = response.json()["releases"][0]
    assert entry["download_path"] == "/updates/kb-test/download"
    download = client.get(entry["download_path"], headers=auth)
    assert download.status_code == 200
    assert download.content == body
    assert download.headers["x-content-sha256"] == entry["sha256"]
    assert int(download.headers["content-length"]) == entry["bytes"]
    assert client.get("/updates/missing/download", headers=auth).status_code == 404


@pytest.mark.parametrize("invalid", ["../kb.json", "a/b.json", "C:\\kb.json", "/kb.json"])
def test_feed_rejects_paths(client, auth, feed, invalid):
    root, index, _ = feed
    index["releases"][0]["file_name"] = invalid
    (root / "index.json").write_text(json.dumps(index), encoding="utf-8")
    assert client.get("/updates", headers=auth).status_code == 503


def test_corrupt_missing_and_oversize_release_are_not_served(client, auth, feed):
    root, index, _ = feed
    (root / "kb.json").write_bytes(b"corrupt")
    assert client.get("/updates/kb-test/download", headers=auth).status_code == 503
    index["releases"][0]["bytes"] = 5 * 1024 * 1024 + 1
    (root / "index.json").write_text(json.dumps(index), encoding="utf-8")
    assert client.get("/updates", headers=auth).status_code == 503


def test_unconfigured_feed_is_empty(client, auth, monkeypatch):
    monkeypatch.delenv("RELEASES_DIR", raising=False)
    assert client.get("/updates", headers=auth).json() == {"schema_version": 1, "releases": []}


def test_symlink_and_deleted_release_fail_closed(client, auth, feed, tmp_path):
    root, _, body = feed
    path = root / "kb.json"
    path.unlink()
    assert client.get("/updates/kb-test/download", headers=auth).status_code == 503
    outside = tmp_path / "outside.json"
    outside.write_bytes(body)
    path.symlink_to(outside)
    assert client.get("/updates/kb-test/download", headers=auth).status_code == 503


def test_unprepared_model_cannot_be_advertised(client, auth, feed):
    root, index, _ = feed
    index["releases"][0]["kind"] = "model"
    (root / "index.json").write_text(json.dumps(index), encoding="utf-8")
    assert client.get("/updates", headers=auth).status_code == 503


def test_prepared_model_metadata_is_preserved(client, auth, feed):
    root, index, body = feed
    entry = index["releases"][0]
    entry["kind"] = "model"
    entry["metadata"] = {"prepared_by": "prepare_model_release.py", "manifest_entry": {
        "family": "yolo", "dataset": "allcrops_v1", "sha256": entry["sha256"],
        "classes": ["synthetic"], "labels_ru": ["synthetic"]}}
    (root / "index.json").write_text(json.dumps(index), encoding="utf-8")
    result = client.get("/updates", headers=auth)
    assert result.status_code == 200
    assert result.json()["releases"][0]["metadata"] == entry["metadata"]
    assert client.get("/updates/kb-test/download", headers=auth).content == body
