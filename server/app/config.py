"""Explicit local/server configuration; secrets never have working defaults."""
from dataclasses import dataclass, field
from functools import lru_cache
import os


@dataclass(frozen=True)
class Settings:
    database_url: str = field(repr=False)
    jwt_secret: str = field(repr=False)
    jwt_issuer: str = "agrovoice"
    jwt_audience: str = "agrovoice-android"
    token_minutes: int = 30


@lru_cache
def settings():
    secret = os.environ.get("JWT_SECRET", "")
    if len(secret) < 32:
        raise RuntimeError("Set JWT_SECRET to at least 32 random characters")
    database = os.environ.get("DATABASE_URL", "")
    if not database.startswith("postgresql+psycopg://"):
        raise RuntimeError("DATABASE_URL must use PostgreSQL with psycopg")
    return Settings(database, secret)
