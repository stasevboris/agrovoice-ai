from datetime import datetime, timedelta, timezone
import uuid

from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
import jwt
import pytest
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.config import settings
from app.db import engine, session
from app.main import app
from app.models import User
from app.security import passwords


@pytest.fixture(scope="session", autouse=True)
def migrate_test_database():
    assert engine.url.database == "agrovoice_test", "Tests require their dedicated database"
    command.upgrade(Config("alembic.ini"), "head")


@pytest.fixture
def database():
    with engine.connect() as connection:
        transaction = connection.begin()
        with Session(bind=connection) as database:
            database.add(User(username="alice", password_hash=passwords.hash("correct-test-password")))
            database.flush()
            yield database
        transaction.rollback()


@pytest.fixture
def client(database):
    app.dependency_overrides[session] = lambda: database
    with TestClient(app) as client:
        yield client
    app.dependency_overrides.clear()


def test_health_uses_real_postgresql_and_migration(client, database):
    assert client.get("/health").json() == {"status": "ok", "database": "ok"}
    assert database.scalar(text("SELECT version_num FROM alembic_version")) == "0002_sync"


def test_password_login_and_bearer_profile(client):
    reply = client.post("/auth/token", json={"username": "alice", "password": "correct-test-password"})
    assert reply.status_code == 200
    token = reply.json()["access_token"]
    profile = client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert profile.status_code == 200
    assert profile.json()["username"] == "alice"
    assert set(profile.json()) == {"id", "username"}


@pytest.mark.parametrize("username,password", [("alice", "wrong"), ("unknown", "wrong")])
def test_invalid_credentials_have_the_same_response(client, username, password):
    reply = client.post("/auth/token", json={"username": username, "password": password})
    assert reply.status_code == 401
    assert reply.json() == {"detail": "Authentication required"}


def test_missing_and_expired_tokens_are_rejected(client):
    assert client.get("/me").status_code == 401
    now = datetime.now(timezone.utc)
    config = settings()
    expired = jwt.encode({"sub": str(uuid.uuid4()), "iat": now - timedelta(hours=2),
                          "exp": now - timedelta(hours=1), "iss": config.jwt_issuer, "aud": config.jwt_audience},
                         config.jwt_secret, algorithm="HS256")
    assert client.get("/me", headers={"Authorization": f"Bearer {expired}"}).status_code == 401
