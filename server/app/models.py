from datetime import datetime
import uuid

from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKeyConstraint, Integer, LargeBinary, String, Text, Uuid, func, true
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    username: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(Text)
    active: Mapped[bool] = mapped_column(Boolean, default=True, server_default=true())
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    sync_revision: Mapped[int] = mapped_column(BigInteger, default=0, server_default="0")
    is_expert: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")


class Inspection(Base):
    __tablename__ = "inspections"
    __table_args__ = (ForeignKeyConstraint(["owner_id"], ["users.id"]),)

    owner_id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    client_id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    version: Mapped[int] = mapped_column(Integer)
    revision: Mapped[int] = mapped_column(BigInteger, index=True)
    payload: Mapped[dict] = mapped_column(JSONB)
    expert_status: Mapped[str] = mapped_column(String(32), default="unreviewed")


class Photo(Base):
    __tablename__ = "photos"
    __table_args__ = (ForeignKeyConstraint(
        ["owner_id", "inspection_id"], ["inspections.owner_id", "inspections.client_id"]),)

    owner_id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    client_id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    inspection_id: Mapped[uuid.UUID] = mapped_column(Uuid)
    sha256: Mapped[str] = mapped_column(String(64))
    size: Mapped[int] = mapped_column(Integer)
    content: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)


class ReviewEvent(Base):
    __tablename__ = "review_events"
    __table_args__ = (
        ForeignKeyConstraint(["owner_id", "inspection_id"], ["inspections.owner_id", "inspections.client_id"]),
        ForeignKeyConstraint(["reviewer_id"], ["users.id"]),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    owner_id: Mapped[uuid.UUID] = mapped_column(Uuid)
    inspection_id: Mapped[uuid.UUID] = mapped_column(Uuid)
    reviewer_id: Mapped[uuid.UUID] = mapped_column(Uuid)
    evidence_version: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(32))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
