import uuid

from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from sqlalchemy import select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.config import settings
from app.db import session
from app.models import User
from app.security import current_user, denied, dummy_hash, issue_token, passwords

app = FastAPI(title="AgroVoice API", version="0.1.0")


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
