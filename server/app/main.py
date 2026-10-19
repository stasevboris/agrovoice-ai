import uuid
from pathlib import Path
import os

from fastapi import Depends, FastAPI, HTTPException
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from sqlalchemy import select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.config import settings
from app.db import session
from app.models import User
from app.security import current_user, denied, dummy_hash, issue_token, passwords
from app.sync import router as sync_router
from app.web import router as web_router

app = FastAPI(title="AgroVoice API", version="0.3.0")
app.add_middleware(SessionMiddleware, secret_key=settings().jwt_secret,
                   session_cookie="agrovoice_session", max_age=1800, same_site="strict",
                   https_only=os.environ.get("COOKIE_SECURE") == "1")
app.include_router(sync_router)
app.include_router(web_router)
app.mount("/static", StaticFiles(directory=Path(__file__).resolve().parents[1] / "static"), name="static")


class Login(BaseModel):
    username: str = Field(min_length=1, max_length=80)
    password: SecretStr


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class Profile(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: uuid.UUID
    username: str


@app.get("/health")
def health(database: Session = Depends(session)):
    try:
        database.execute(text("SELECT 1"))
    except SQLAlchemyError:
        raise HTTPException(503, "Database unavailable") from None
    return {"status": "ok", "database": "ok"}


@app.post("/auth/token", response_model=Token)
def login(request: Login, database: Session = Depends(session)):
    user = database.scalar(select(User).where(User.username == request.username))
    valid = passwords.verify(request.password.get_secret_value(), user.password_hash if user else dummy_hash)
    if not valid or user is None or not user.active:
        raise denied()
    return Token(access_token=issue_token(user.id), expires_in=settings().token_minutes * 60)


@app.get("/me", response_model=Profile)
def profile(user: User = Depends(current_user)):
    return user
