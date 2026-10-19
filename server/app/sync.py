"""Versioned owner-scoped sync; clocks cannot silently overwrite conflicts."""
from datetime import timezone
import hashlib
import json
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, JsonValue, model_validator
from sqlalchemy import select
from sqlalchemy.orm import Session
from starlette.concurrency import run_in_threadpool
from starlette.requests import ClientDisconnect

from app.db import session
from app.models import Inspection, Photo, User
from app.security import current_user

router = APIRouter()
MAX_PHOTO_BYTES = 10 * 1024 * 1024
Sha256 = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class PhotoReference(StrictModel):
    id: UUID
    sha256: Sha256
    bytes: int = Field(gt=0, le=MAX_PHOTO_BYTES)


class InspectionData(StrictModel):
    # Preserve legacy Android IDs separately: they are not guaranteed UUIDs.
    legacy_id: str | None = Field(default=None, max_length=256)
    created_at: AwareDatetime
    plot: str | None = Field(default=None, max_length=256)
    row: str | None = Field(default=None, max_length=256)
    lat: float | None = Field(default=None, ge=-90, le=90)
    lon: float | None = Field(default=None, ge=-180, le=180)
    accuracy_m: float | None = Field(default=None, ge=0)
    note: str = Field(default="", max_length=10000)
    user_review: str = Field(default="", max_length=256)
    model_version: str = Field(max_length=256)
    model_sha256: Sha256
    prediction: dict[str, JsonValue]
    photos: list[PhotoReference] = Field(default_factory=list, max_length=10)

    @model_validator(mode="after")
    def bounded_payload(self):
        if len({photo.id for photo in self.photos}) != len(self.photos):
            raise ValueError("Duplicate photo UUID")
        if (self.lat is None) != (self.lon is None):
            raise ValueError("Coordinates require both latitude and longitude")
        if len(json.dumps(self.prediction, allow_nan=False).encode()) > 128 * 1024:
            raise ValueError("Prediction exceeds 128 KiB")
        self.created_at = self.created_at.astimezone(timezone.utc)
        return self


class InspectionUpdate(StrictModel):
    id: UUID
    base_version: int = Field(ge=0)
    updated_at: AwareDatetime
    data: InspectionData


class Batch(StrictModel):
    inspections: list[InspectionUpdate] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def unique_ids(self):
        if len({item.id for item in self.inspections}) != len(self.inspections):
            raise ValueError("Duplicate inspection UUID in batch")
        return self


def view(record: Inspection):
    return {"id": record.client_id, "version": record.version, "revision": record.revision,
            "updated_at": record.updated_at, "data": record.payload, "expert_status": record.expert_status}


def lock_owner(database: Session, owner_id: UUID):
    # Serialize all writes for one account, including revision allocation and uploads.
    return database.scalar(select(User).where(User.id == owner_id).with_for_update()
                           .execution_options(populate_existing=True))


def apply_update(database: Session, owner: User, update: InspectionUpdate):
    record = database.get(Inspection, (owner.id, update.id))
    payload = update.data.model_dump(mode="json")
    updated_at = update.updated_at.astimezone(timezone.utc)
    if record is not None:
        if record.payload == payload and record.updated_at == updated_at:
            return {"status": "unchanged", "inspection": view(record)}
        if update.base_version != record.version or updated_at <= record.updated_at:
            return {"status": "conflict", "inspection": view(record)}
    elif update.base_version != 0:
        return {"status": "conflict", "id": update.id, "inspection": None}

    existing_photos = {}
    for reference in update.data.photos:
        photo = database.get(Photo, (owner.id, reference.id))
        if photo is not None:
            if (photo.inspection_id, photo.sha256, photo.size) != (update.id, reference.sha256, reference.bytes):
                return {"status": "conflict", "id": update.id, "reason": "photo_identity",
                        "inspection": view(record) if record else None}
            existing_photos[reference.id] = photo

    owner.sync_revision += 1
    if record is None:
        record = Inspection(owner_id=owner.id, client_id=update.id, version=1,
                            revision=owner.sync_revision, updated_at=updated_at, payload=payload)
        database.add(record)
    else:
        evidence_fields = ("prediction", "photos", "model_version", "model_sha256")
        if any(record.payload[key] != payload[key] for key in evidence_fields):
            record.expert_status = "unreviewed"
        record.version += 1
        record.revision = owner.sync_revision
        record.updated_at = updated_at
        record.payload = payload
    database.flush()
    for reference in update.data.photos:
        if reference.id not in existing_photos:
            database.add(Photo(owner_id=owner.id, client_id=reference.id, inspection_id=record.client_id,
                               sha256=reference.sha256, size=reference.bytes))
    database.flush()
    return {"status": "accepted", "inspection": view(record)}


@router.post("/sync/inspections")
def push(batch: Batch, owner: User = Depends(current_user), database: Session = Depends(session)):
    owner = lock_owner(database, owner.id)
    results = [apply_update(database, owner, update) for update in batch.inspections]
    database.commit()
    return {"results": results}


@router.get("/sync/inspections")
def pull(after: int = Query(default=0, ge=0), limit: int = Query(default=50, ge=1, le=100),
         owner: User = Depends(current_user), database: Session = Depends(session)):
    records = database.scalars(select(Inspection).where(
        Inspection.owner_id == owner.id, Inspection.revision > after
    ).order_by(Inspection.revision).limit(limit + 1)).all()
    visible = records[:limit]
    return {"inspections": [view(record) for record in visible],
            "next_after": visible[-1].revision if visible else after, "has_more": len(records) > limit}


def declared_photo(database: Session, owner_id: UUID, photo_id: UUID):
    photo = database.get(Photo, (owner_id, photo_id), populate_existing=True)
    if photo is None:
        raise HTTPException(404, "Photo not declared by this account")
    return photo


async def receive_photo(request: Request, expected_size: int, expected_sha: str):
    body = bytearray()
    digest = hashlib.sha256()
    try:
        async for chunk in request.stream():
            if len(body) + len(chunk) > expected_size:
                raise HTTPException(413, "Photo exceeds declared size")
            body.extend(chunk)
            digest.update(chunk)
    except ClientDisconnect:
        raise HTTPException(400, "Incomplete upload; retry the full body") from None
    if len(body) != expected_size or digest.hexdigest() != expected_sha:
        raise HTTPException(422, "Photo size or SHA-256 mismatch")
    return bytes(body)


def store_photo(database: Session, owner_id: UUID, photo_id: UUID, body: bytes):
    lock_owner(database, owner_id)
    photo = declared_photo(database, owner_id, photo_id)
    if photo.content is not None:
        return {"id": photo.client_id, "status": "unchanged", "sha256": photo.sha256}
    photo.content = body
    database.commit()
    return {"id": photo.client_id, "status": "stored", "sha256": photo.sha256}


@router.put("/photos/{photo_id}")
async def upload(photo_id: UUID, request: Request, x_content_sha256: Sha256 = Header(),
                 owner: User = Depends(current_user), database: Session = Depends(session)):
    photo = await run_in_threadpool(declared_photo, database, owner.id, photo_id)
    if x_content_sha256 != photo.sha256:
        raise HTTPException(409, "SHA-256 differs from declared photo")
    body = await receive_photo(request, photo.size, photo.sha256)
    return await run_in_threadpool(store_photo, database, owner.id, photo_id, body)


@router.get("/photos/{photo_id}")
def download(photo_id: UUID, owner: User = Depends(current_user), database: Session = Depends(session)):
    photo = declared_photo(database, owner.id, photo_id)
    if photo.content is None:
        raise HTTPException(404, "Photo upload is incomplete")
    return Response(photo.content, media_type="application/octet-stream",
                    headers={"X-Content-SHA256": photo.sha256,
                             "Content-Disposition": f'attachment; filename="{photo.client_id}.bin"'})
