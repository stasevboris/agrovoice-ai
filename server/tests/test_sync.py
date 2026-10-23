import asyncio
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
from threading import Barrier
import uuid

from fastapi import HTTPException
import pytest
from sqlalchemy import delete, event, func, select
from starlette.requests import Request

from app.models import Inspection, Photo, User
from app.db import Session as NewSession
from app.security import issue_token, passwords
from app.sync import Batch, push as push_batch, upload

# Reuse the dedicated PostgreSQL fixture and its outer rollback transaction.
from tests.test_api import client, database, migrate_test_database  # noqa: F401


@pytest.fixture
def auth(database):
    user = database.scalar(select(User).where(User.username == "alice"))
    return {"Authorization": f"Bearer {issue_token(user.id)}"}


@pytest.fixture
def other_auth(database):
    user = User(username="bob", password_hash=passwords.hash("bob-test-password"))
    database.add(user)
    database.flush()
    return {"Authorization": f"Bearer {issue_token(user.id)}"}


def update(photo: bytes | None = None):
    now = datetime.now(timezone.utc).isoformat()
    return {"id": str(uuid.uuid4()), "base_version": 0, "updated_at": now, "data": {
        "legacy_id": "inspection_20261019_001", "created_at": now, "note": "Test note",
        "model_version": "test-model", "model_sha256": "a" * 64,
        "prediction": {"crop": "potato", "detections": []},
        "photos": [] if photo is None else [{"id": str(uuid.uuid4()), "sha256": hashlib.sha256(photo).hexdigest(), "bytes": len(photo)}],
    }}


def push(client, auth, *updates):
    return client.post("/sync/inspections", headers=auth, json={"inspections": list(updates)})


def test_repeat_conflict_and_server_expert_status(client, database, auth):
    item = update()
    first = push(client, auth, item).json()["results"][0]
    assert first["status"] == "accepted"
    assert first["inspection"]["version"] == 1
    assert first["inspection"]["expert_status"] == "unreviewed"
    repeated = push(client, auth, item).json()["results"][0]
    assert repeated["status"] == "unchanged"
    assert repeated["inspection"] == first["inspection"]
    changed = deepcopy(item)
    changed["data"]["note"] = "Changed offline"
    changed["updated_at"] = (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat()
    conflict = push(client, auth, changed).json()["results"][0]
    assert conflict["status"] == "conflict"
    assert conflict["inspection"]["data"]["note"] == "Test note"
    record = database.scalar(select(Inspection))
    record.expert_status = "confirmed"
    database.flush()
    changed["base_version"] = 1
    accepted = push(client, auth, changed).json()["results"][0]
    assert accepted["status"] == "accepted"
    assert accepted["inspection"]["version"] == 2
    assert accepted["inspection"]["expert_status"] == "confirmed"
    assert database.scalar(select(func.count()).select_from(Inspection)) == 1
    changed["data"]["expert_status"] = "confirmed"
    assert push(client, auth, changed).status_code == 422


def test_reject_stale_clock_even_with_current_version(client, auth):
    item = update()
    push(client, auth, item)
    item["base_version"] = 1
    item["data"]["note"] = "Same timestamp, changed payload"
    assert push(client, auth, item).json()["results"][0]["status"] == "conflict"


def test_changed_prediction_requires_new_expert_review(client, database, auth):
    item = update()
    push(client, auth, item)
    record = database.scalar(select(Inspection))
    record.expert_status = "confirmed"
    database.flush()
    item["base_version"] = 1
    item["updated_at"] = (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat()
    item["data"]["prediction"]["crop"] = "tomato"
    reply = push(client, auth, item).json()["results"][0]
    assert reply["status"] == "accepted"
    assert reply["inspection"]["expert_status"] == "unreviewed"


def test_accounts_are_isolated_even_for_same_uuid(client, auth, other_auth):
    item = update(b"test photo bytes")
    push(client, auth, item)
    assert client.get("/sync/inspections", headers=other_auth).json()["inspections"] == []
    photo = item["data"]["photos"][0]
    assert client.get(f'/photos/{photo["id"]}', headers=other_auth).status_code == 404
    headers = {**other_auth, "X-Content-SHA256": photo["sha256"]}
    assert client.put(f'/photos/{photo["id"]}', headers=headers, content=b"test photo bytes").status_code == 404
    item["data"]["note"] = "Bob's independent inspection"
    assert push(client, other_auth, item).json()["results"][0]["status"] == "accepted"
    assert client.get("/sync/inspections", headers=auth).json()["inspections"][0]["data"]["note"] == "Test note"


def test_pagination_and_incremental_update(client, auth):
    items = [update() for _ in range(3)]
    assert push(client, auth, *items).status_code == 200
    first = client.get("/sync/inspections?limit=2", headers=auth).json()
    assert len(first["inspections"]) == 2 and first["has_more"]
    second = client.get(f'/sync/inspections?after={first["next_after"]}', headers=auth).json()
    assert len(second["inspections"]) == 1 and not second["has_more"]
    changed = items[0]
    changed.update(base_version=1, updated_at=(datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat())
    changed["data"]["note"] = "Later revision"
    push(client, auth, changed)
    third = client.get(f'/sync/inspections?after={second["next_after"]}', headers=auth).json()
    assert [record["id"] for record in third["inspections"]] == [changed["id"]]


def test_photo_repeat_mismatch_truncation_and_retry(client, database, auth):
    content = b"a small test photo payload"
    item = update(content)
    push(client, auth, item)
    photo = item["data"]["photos"][0]
    url = f'/photos/{photo["id"]}'
    headers = {**auth, "X-Content-SHA256": photo["sha256"]}
    assert client.get(url, headers=auth).status_code == 404
    for invalid, expected in [(content[:-2], 422), (b"x" * len(content), 422), (content + b"x", 413)]:
        assert client.put(url, headers=headers, content=invalid).status_code == expected
        assert database.scalar(select(Photo)).content is None
    assert client.put(url, headers={**auth, "X-Content-SHA256": "0" * 64}, content=content).status_code == 409
    assert client.put(url, headers=headers, content=content).json()["status"] == "stored"
    assert client.put(url, headers=headers, content=content).json()["status"] == "unchanged"
    downloaded = client.get(url, headers=auth)
    assert downloaded.content == content
    assert downloaded.headers["x-content-sha256"] == photo["sha256"]


def test_metadata_update_does_not_read_photo_bytes(client, database, auth):
    content = b"photo payload for metadata-only edit"
    item = update(content)
    assert push(client, auth, item).status_code == 200
    photo = item["data"]["photos"][0]
    assert client.put(f'/photos/{photo["id"]}', content=content,
                      headers={**auth, "X-Content-SHA256": photo["sha256"]}).status_code == 200
    database.expunge_all()
    item["base_version"] = 1
    item["updated_at"] = (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat()
    item["data"]["note"] = "Metadata-only correction"
    statements = []

    def capture(connection, cursor, statement, parameters, context, executemany):
        statements.append(statement)

    connection = database.connection()
    event.listen(connection, "before_cursor_execute", capture)
    try:
        response = push(client, auth, item)
    finally:
        event.remove(connection, "before_cursor_execute", capture)
    assert response.json()["results"][0]["status"] == "accepted"
    assert response.json()["results"][0]["missing_photos"] == []
    assert any("FROM photos" in statement for statement in statements)
    # A WHERE filter on content is allowed; selecting the bytes is not.
    assert all("photos.content" not in statement.split("FROM")[0] for statement in statements)
    assert client.get(f'/photos/{photo["id"]}', headers=auth).content == content


def test_push_lists_only_photos_without_stored_bytes(client, auth):
    first, second = b"first photo bytes", b"second photo bytes"
    item = update(first)
    item["data"]["photos"].append({"id": str(uuid.uuid4()), "sha256": hashlib.sha256(second).hexdigest(),
                                   "bytes": len(second)})
    ids = [photo["id"] for photo in item["data"]["photos"]]
    assert push(client, auth, item).json()["results"][0]["missing_photos"] == ids
    photo = item["data"]["photos"][0]
    assert client.put(f'/photos/{photo["id"]}', content=first,
                      headers={**auth, "X-Content-SHA256": photo["sha256"]}).status_code == 200
    repeated = push(client, auth, item).json()["results"][0]
    assert repeated["status"] == "unchanged"
    assert repeated["missing_photos"] == [ids[1]]
    item["base_version"] = 1
    item["updated_at"] = (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat()
    item["data"]["note"] = "Note-only correction"
    edited = push(client, auth, item).json()["results"][0]
    assert edited["status"] == "accepted"
    assert edited["missing_photos"] == [ids[1]]


def test_disconnect_during_stream_and_full_retry(client, database, auth):
    content = b"partial then connection lost"
    item = update(content)
    push(client, auth, item)
    messages = iter([{"type": "http.request", "body": content[:5], "more_body": True},
                     {"type": "http.disconnect"}])

    async def receive():
        return next(messages)

    request = Request({"type": "http", "method": "PUT", "path": "/", "headers": []}, receive=receive)
    photo = item["data"]["photos"][0]
    owner = database.scalar(select(User).where(User.username == "alice"))
    with pytest.raises(HTTPException) as error:
        asyncio.run(upload(uuid.UUID(photo["id"]), request, photo["sha256"], owner, database))
    assert error.value.status_code == 400
    assert database.scalar(select(Photo)).content is None
    assert client.put(f'/photos/{photo["id"]}', headers={**auth, "X-Content-SHA256": photo["sha256"]},
                      content=content).json()["status"] == "stored"


def test_metadata_rebinding_and_duplicate_batch_are_rejected(client, auth):
    item = update(b"original bytes")
    push(client, auth, item)
    different = update()
    different["data"]["photos"] = deepcopy(item["data"]["photos"])
    result = push(client, auth, different).json()["results"][0]
    assert result["status"] == "conflict" and result["reason"] == "photo_identity"
    assert push(client, auth, item, item).status_code == 422
    item["expert_status"] = "confirmed"
    assert push(client, auth, item).status_code == 422


def test_authentication_required(client):
    item = update()
    assert client.post("/sync/inspections", json={"inspections": [item]}).status_code == 401
    assert client.get("/sync/inspections").status_code == 401
    assert client.get(f'/photos/{uuid.uuid4()}').status_code == 401


def test_concurrent_retries_use_one_version(migrate_test_database):
    owner_id = uuid.uuid4()
    with NewSession() as database:
        database.add(User(id=owner_id, username=f"concurrency-{owner_id}", password_hash="unused-in-this-test"))
        database.commit()
    item = update()
    barrier = Barrier(2)

    def send():
        with NewSession() as database:
            owner = database.get(User, owner_id)
            barrier.wait(timeout=10)
            return push_batch(Batch(inspections=[item]), owner, database)["results"][0]

    try:
        with ThreadPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(lambda _: send(), range(2)))
        assert sorted(result["status"] for result in results) == ["accepted", "unchanged"]
        assert [result["inspection"]["version"] for result in results] == [1, 1]
        with NewSession() as database:
            owner = database.get(User, owner_id)
            assert owner.sync_revision == 1
            assert database.scalar(select(func.count()).select_from(Inspection).where(Inspection.owner_id == owner_id)) == 1
    finally:
        # Only this test's account in agrovoice_test; never the application database.
        with NewSession() as database:
            database.execute(delete(Inspection).where(Inspection.owner_id == owner_id))
            database.execute(delete(User).where(User.id == owner_id))
            database.commit()
