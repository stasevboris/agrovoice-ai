import hashlib
import json

import pytest
from tests.test_api import client, database, migrate_test_database  # noqa: F401
from tests.test_sync import auth, push, update  # noqa: F401


def test_backup_keeps_photo_and_rejects_corruption(client, database, auth, tmp_path):
    from app.backup import write_snapshot, verify_backup, restore_backup

    body = b"original-photo-for-backup"
    item = update(body)
    assert push(client, auth, item).status_code == 200
    photo = item["data"]["photos"][0]
    assert client.put(f'/photos/{photo["id"]}', content=body,
                      headers={**auth, "X-Content-SHA256": photo["sha256"]}).status_code == 200
    directory = tmp_path / "backup"
    write_snapshot(database.connection(), directory)
    manifest = verify_backup(directory)
    assert manifest["tables"]["photos"]["rows"] == 1
    assert manifest["photos"][0]["sha256"] == hashlib.sha256(body).hexdigest()
    assert (directory / manifest["photos"][0]["file"]).read_bytes() == body
    with pytest.raises(FileExistsError):
        write_snapshot(database.connection(), directory)
    with pytest.raises(FileExistsError):
        restore_backup(directory, "agrovoice_restore_test", directory)
    (directory / manifest["photos"][0]["file"]).write_bytes(b"corrupt")
    with pytest.raises(ValueError, match="mismatch"):
        verify_backup(directory)
    with pytest.raises(ValueError, match="mismatch"):
        restore_backup(directory, "agrovoice_restore_test", tmp_path / "must_not_exist")
    assert not (tmp_path / "must_not_exist").exists()


def test_backup_rejects_traversal_and_restore_requires_new_name(tmp_path):
    from app.backup import validate_restore_name, verify_backup

    with pytest.raises(ValueError):
        validate_restore_name("agrovoice")
    with pytest.raises(ValueError):
        validate_restore_name("agrovoice_restore_x;DROP DATABASE agrovoice")
    validate_restore_name("agrovoice_restore_test_123")
    (tmp_path / "manifest.json").write_text(json.dumps({"schema_version": 1,
        "tables": {"users": {"file": "../users.jsonl", "rows": 1}}, "photos": []}), encoding="utf-8")
    with pytest.raises(ValueError):
        verify_backup(tmp_path)


def test_snapshot_preserves_pending_photo_review_and_rejects_bad_path(client, database, auth, tmp_path):
    from app.backup import write_snapshot, verify_backup
    from app.models import Inspection, ReviewEvent, User
    from sqlalchemy import select

    item = update(b"pending original")
    push(client, auth, item)
    inspection = database.scalar(select(Inspection))
    owner = database.scalar(select(User))
    database.add(ReviewEvent(owner_id=owner.id, inspection_id=inspection.client_id,
        reviewer_id=owner.id, evidence_version=inspection.version, status="confirmed"))
    database.flush()
    root = tmp_path / "snapshot"
    manifest = write_snapshot(database.connection(), root)
    assert manifest["tables"]["review_events"]["rows"] == 1
    assert manifest["tables"]["photos"]["rows"] == 1
    assert manifest["photos"] == []
    assert json.loads((root / "photos.jsonl").read_text())["content_file"] is None
    manifest["tables"]["users"]["file"] = "../users.jsonl"
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="path"):
        verify_backup(root)
