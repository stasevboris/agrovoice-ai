import uuid

import pytest
from sqlalchemy import event, select

from app.models import User
from app.security import passwords
from tests.test_api import client, database, migrate_test_database  # noqa: F401
from tests.test_sync import auth, other_auth, push, update  # noqa: F401
from tests.test_web import login, sample_record


@pytest.mark.parametrize("expert", [False, True])
def test_cookie_original_download_preserves_bytes_and_is_an_attachment(client, database, auth, expert):
    item = sample_record(client, auth)
    owner_id = database.scalar(select(User.id).where(User.username == "alice"))
    photo = item["data"]["photos"][0]
    original = client.get(f'/photos/{photo["id"]}', headers=auth).content
    if expert:
        database.add(User(username="expert", password_hash=passwords.hash("expert-test-password"), is_expert=True))
        database.flush()
        login(client, "expert", "expert-test-password")
    else:
        login(client)
    response = client.get(f'/original/{owner_id}/{photo["id"]}')
    assert response.status_code == 200
    assert response.content == original
    assert response.headers["content-type"] == "application/octet-stream"
    assert response.headers["content-disposition"] == f'attachment; filename="{photo["id"]}.bin"'
    assert response.headers["x-content-sha256"] == photo["sha256"]
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["cache-control"] == "private, no-store"
    assert client.get(f'/photos/{photo["id"]}', headers=auth).content == original


def test_original_download_requires_cookie_login(client, database, auth):
    item = sample_record(client, auth)
    owner_id = database.scalar(select(User.id).where(User.username == "alice"))
    photo_id = item["data"]["photos"][0]["id"]
    response = client.get(f"/original/{owner_id}/{photo_id}", follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/login"


@pytest.mark.parametrize("route", ["original", "preview"])
def test_foreign_cookie_user_cannot_load_photo_bytes(client, database, auth, other_auth, route):
    item = sample_record(client, auth)
    owner_id = database.scalar(select(User.id).where(User.username == "alice"))
    photo_id = item["data"]["photos"][0]["id"]
    login(client, "bob", "bob-test-password")
    database.expunge_all()
    statements = []

    def capture(connection, cursor, statement, parameters, context, executemany):
        statements.append(statement)

    connection = database.connection()
    event.listen(connection, "before_cursor_execute", capture)
    try:
        response = client.get(f"/{route}/{owner_id}/{photo_id}")
    finally:
        event.remove(connection, "before_cursor_execute", capture)
    assert response.status_code == 404
    assert all("photos.content" not in statement.split("FROM", 1)[0] for statement in statements)


def test_unuploaded_photo_has_no_false_saved_message_or_download_link(client, database, auth):
    item = update(b"not uploaded yet")
    assert push(client, auth, item).status_code == 200
    owner_id = database.scalar(select(User.id).where(User.username == "alice"))
    photo_id = item["data"]["photos"][0]["id"]
    login(client)
    page = client.get(f'/inspections/{owner_id}/{item["id"]}')
    assert page.status_code == 200
    assert "Фото ещё не загружено на сервер" in page.text
    assert f'/original/{owner_id}/{photo_id}' not in page.text
    assert "Исходное фото сохранено на сервере" not in page.text
    response = client.get(f"/original/{owner_id}/{photo_id}")
    assert response.status_code == 404
    assert response.json()["detail"] == "Photo upload is incomplete"
    assert client.get(f"/original/{owner_id}/{uuid.uuid4()}").status_code == 404


def test_card_links_to_original_even_when_preview_is_unavailable_and_escapes_note(client, database, auth, monkeypatch):
    item = sample_record(client, auth, note='<a href="/evil">unexpected link</a>')
    owner_id = database.scalar(select(User.id).where(User.username == "alice"))
    photo_id = item["data"]["photos"][0]["id"]
    login(client)
    monkeypatch.setattr("app.web.MAX_PREVIEW_PIXELS", 100)
    assert client.get(f"/preview/{owner_id}/{photo_id}").status_code == 415
    page = client.get(f'/inspections/{owner_id}/{item["id"]}')
    assert f'href="/original/{owner_id}/{photo_id}"' in page.text
    assert "Скачать исходное фото" in page.text
    assert "Исходное фото сохранено на сервере" in page.text
    assert "предпросмотр" in page.text
    assert '<a href="/evil">' not in page.text
    assert '&lt;a href=' in page.text
    assert client.get(f"/original/{owner_id}/{photo_id}").status_code == 200


def test_undecodable_image_still_has_unchanged_downloadable_original(client, database, auth):
    content = b"synthetic file that is not a decodable image"
    item = update(content)
    assert push(client, auth, item).status_code == 200
    photo = item["data"]["photos"][0]
    assert client.put(f'/photos/{photo["id"]}', content=content,
                      headers={**auth, "X-Content-SHA256": photo["sha256"]}).status_code == 200
    owner_id = database.scalar(select(User.id).where(User.username == "alice"))
    login(client)
    assert client.get(f'/preview/{owner_id}/{photo["id"]}').status_code == 415
    response = client.get(f'/original/{owner_id}/{photo["id"]}')
    assert response.status_code == 200
    assert response.content == content
