from datetime import datetime, timedelta, timezone
import uuid

from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
import jwt
from pwdlib import PasswordHash
from sqlalchemy.orm import Session

from app.config import settings
from app.db import session
from app.models import User

passwords = PasswordHash.recommended()
dummy_hash = passwords.hash("not-a-user-password")
bearer = HTTPBearer(auto_error=False)


def denied():
    return HTTPException(401, "Authentication required", headers={"WWW-Authenticate": "Bearer"})


def issue_token(user_id):
    config = settings()
    now = datetime.now(timezone.utc)
    claims = {"sub": str(user_id), "iat": now, "exp": now + timedelta(minutes=config.token_minutes),
              "iss": config.jwt_issuer, "aud": config.jwt_audience}
    return jwt.encode(claims, config.jwt_secret, algorithm="HS256")


def current_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
                 database: Session = Depends(session)):
    if credentials is None:
        raise denied()
    config = settings()
    try:
        claims = jwt.decode(credentials.credentials, config.jwt_secret, algorithms=["HS256"],
                            issuer=config.jwt_issuer, audience=config.jwt_audience,
                            options={"require": ["sub", "exp", "iat"]})
        user_id = uuid.UUID(claims["sub"])
    except (jwt.PyJWTError, ValueError, TypeError):
        raise denied() from None
    user = database.get(User, user_id)
    if user is None or not user.active:
        raise denied()
    return user
