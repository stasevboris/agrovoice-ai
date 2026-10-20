"""Authenticated local releases. The feed directory is administrator-managed."""
import hashlib
import os
from pathlib import Path
import tempfile
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, model_validator
from starlette.background import BackgroundTask
from starlette.responses import StreamingResponse

from app.models import User
from app.security import current_user

router = APIRouter()
MAX_INDEX_BYTES = 1024 * 1024
MAX_MODEL_BYTES = 512 * 1024 * 1024
MAX_KB_BYTES = 5 * 1024 * 1024


class Release(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$")
    kind: Literal["model", "kb"]
    version: str = Field(min_length=1, max_length=128)
    file_name: str = Field(pattern=r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,159}$")
    bytes: int = Field(gt=0, le=MAX_MODEL_BYTES)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    metadata: dict = Field(default_factory=dict)

    @model_validator(mode="after")
    def size_by_kind(self):
        if self.kind == "kb" and self.bytes > MAX_KB_BYTES:
            raise ValueError("KB exceeds 5 MiB")
        if self.kind == "model":
            model = self.metadata.get("manifest_entry", {})
            if (not isinstance(model, dict) or self.metadata.get("prepared_by") != "prepare_model_release.py"
                    or model.get("family") != "yolo" or model.get("dataset") != "allcrops_v1"
                    or model.get("sha256") != self.sha256 or not model.get("classes")
                    or not isinstance(model.get("classes"), list)
                    or not isinstance(model.get("labels_ru"), list)
                    or len(model["classes"]) != len(model["labels_ru"])):
                raise ValueError("Model requires prepared release metadata")
        return self


class Feed(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal[1] = 1
    releases: list[Release] = Field(default_factory=list, max_length=100)

    @model_validator(mode="after")
    def unique_ids(self):
        if len({entry.id for entry in self.releases}) != len(self.releases):
            raise ValueError("Duplicate release id")
        return self


def load_feed():
    configured = os.environ.get("RELEASES_DIR")
    if not configured:
        return None, Feed()
    root = Path(configured).resolve()
    try:
        with (root / "index.json").open("rb") as stream:
            raw = stream.read(MAX_INDEX_BYTES + 1)
        if len(raw) > MAX_INDEX_BYTES:
            raise ValueError("Index too large")
        return root, Feed.model_validate_json(raw)
    except (OSError, ValueError):
        raise HTTPException(503, "Release feed unavailable") from None


@router.get("/updates")
def releases(user: User = Depends(current_user)):
    _, feed = load_feed()
    return {"schema_version": 1, "releases": [
        {**entry.model_dump(), "download_path": f"/updates/{entry.id}/download"}
        for entry in feed.releases
    ]}


@router.get("/updates/{release_id}/download")
def download(release_id: str, user: User = Depends(current_user)):
    root, feed = load_feed()
    entry = next((item for item in feed.releases if item.id == release_id), None)
    if entry is None:
        raise HTTPException(404, "Release not found")
    # Verify into a private spool first: replacing a feed file during download cannot
    # change the verified response, and a 512 MiB model does not occupy 512 MiB RAM.
    spool = tempfile.TemporaryFile()
    try:
        path = root / entry.file_name
        if path.is_symlink() or path.resolve().parent != root:
            raise ValueError("Invalid release path")
        digest = hashlib.sha256()
        size = 0
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                size += len(chunk)
                if size > entry.bytes:
                    raise ValueError("Release size mismatch")
                digest.update(chunk)
                spool.write(chunk)
        if size != entry.bytes or digest.hexdigest() != entry.sha256:
            raise ValueError("Release hash mismatch")
        spool.seek(0)
    except (OSError, ValueError):
        spool.close()
        raise HTTPException(503, "Release file unavailable or corrupt") from None

    def chunks():
        try:
            while chunk := spool.read(64 * 1024):
                yield chunk
        finally:
            spool.close()

    return StreamingResponse(chunks(), media_type="application/octet-stream",
        background=BackgroundTask(spool.close), headers={
            "Content-Length": str(entry.bytes), "X-Content-SHA256": entry.sha256,
            "Content-Disposition": f'attachment; filename="{entry.file_name}"',
            "Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff",
        })
