"""Local farm dashboard. Ordinary accounts see their own records; experts see the farm."""
from datetime import date, datetime, time, timedelta, timezone
import hmac
import io
import math
from pathlib import Path
import secrets
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Form, HTTPException, Query, Request
from fastapi.responses import RedirectResponse, Response
from fastapi.templating import Jinja2Templates
from openpyxl import Workbook
from openpyxl.cell.cell import ILLEGAL_CHARACTERS_RE
from PIL import Image, ImageOps, UnidentifiedImageError
from pydantic import BaseModel, Field, model_validator
from sqlalchemy import DateTime, cast, func, select
from sqlalchemy.orm import Session

from app.db import session
from app.models import Inspection, Photo, ReviewEvent, User
from app.security import dummy_hash, passwords
from app.sync import lock_owner

router = APIRouter(include_in_schema=False)
MAX_PREVIEW_PIXELS = 24_000_000
templates = Jinja2Templates(directory=Path(__file__).resolve().parents[1] / "templates")
CROPS = {"potato": "Картофель", "tomato": "Томат", "cucumber": "Огурец", "apple": "Яблоня",
         "cherry": "Вишня", "strawberry": "Земляника", "raspberry": "Малина", "pepper": "Перец",
         "squash": "Тыква / кабачок", "corn": "Кукуруза", "soybean": "Соя", "grape": "Виноград",
         "blueberry": "Голубика", "peach": "Персик", "orange": "Апельсин"}


def csrf(request: Request):
    if "csrf" not in request.session:
        request.session["csrf"] = secrets.token_urlsafe(32)
    return request.session["csrf"]


def check_csrf(request: Request, supplied: str):
    expected = request.session.get("csrf", "")
    if not expected or not hmac.compare_digest(expected.encode("utf-8"), supplied.encode("utf-8")):
        raise HTTPException(403, "Invalid form token")


def web_user(request: Request, database: Session = Depends(session)):
    try:
        user_id = UUID(request.session.get("user", ""))
    except ValueError:
        raise HTTPException(303, headers={"Location": "/login"}) from None
    user = database.get(User, user_id)
    if user is None or not user.active:
        request.session.clear()
        raise HTTPException(303, headers={"Location": "/login"})
    return user


@router.get("/login")
def login_page(request: Request):
    return templates.TemplateResponse(request=request, name="login.html", context={"csrf": csrf(request), "error": None})


@router.post("/login")
def login(request: Request, username: str = Form(max_length=80), password: str = Form(max_length=1024),
          token: str = Form(max_length=256), database: Session = Depends(session)):
    check_csrf(request, token)
    user = database.scalar(select(User).where(User.username == username))
    valid = passwords.verify(password, user.password_hash if user else dummy_hash)
    if not valid or user is None or not user.active:
        return templates.TemplateResponse(request=request, name="login.html", status_code=401,
                                          context={"csrf": csrf(request), "error": "Не удалось войти. Проверьте логин и пароль."})
    request.session.clear()
    request.session["user"] = str(user.id)
    csrf(request)
    return RedirectResponse("/", status_code=303)


@router.post("/logout")
def logout(request: Request, token: str = Form(max_length=256)):
    check_csrf(request, token)
    request.session.clear()
    return RedirectResponse("/login", status_code=303)


class Filters(BaseModel):
    crop: str = Field(default="", max_length=100)
    disease: str = Field(default="", max_length=150)
    plot: str = Field(default="", max_length=256)
    start: date | None = None
    end: date | None = None
    page: int = Field(default=1, ge=1, le=10000)

    @model_validator(mode="before")
    @classmethod
    def empty_dates(cls, values):
        return {key: None if key in ("start", "end") and value == "" else value for key, value in values.items()}

    @model_validator(mode="after")
    def ordered_dates(self):
        if self.start and self.end and self.start > self.end:
            raise ValueError("Start date follows end date")
        return self


def filtered(user: User, filters: Filters):
    query = select(Inspection)
    if not user.is_expert:
        query = query.where(Inspection.owner_id == user.id)
    label = Inspection.payload["prediction"]["detections"][0]["class_name"].astext
    if filters.crop:
        query = query.where(func.split_part(label, "__", 1) == filters.crop)
    if filters.disease:
        query = query.where(label == filters.disease)
    if filters.plot:
        query = query.where(Inspection.payload["plot"].astext == filters.plot)
    created = cast(Inspection.payload["created_at"].astext, DateTime(timezone=True))
    if filters.start:
        query = query.where(created >= datetime.combine(filters.start, time.min, timezone.utc))
    if filters.end:
        query = query.where(created < datetime.combine(filters.end + timedelta(days=1), time.min, timezone.utc))
    return query.order_by(Inspection.updated_at.desc(), Inspection.owner_id, Inspection.client_id)


def display(record: Inspection):
    payload = record.payload
    detections = payload.get("prediction", {}).get("detections", [])
    top = detections[0] if isinstance(detections, list) and detections and isinstance(detections[0], dict) else {}
    return {"id": str(record.client_id), "owner": str(record.owner_id), "version": record.version,
            "date": payload.get("created_at", "")[:16].replace("T", " "), "plot": payload.get("plot") or "—",
            "crop": CROPS.get(str(top.get("class_name", "")).split("__")[0], "Не определена"),
            "label": top.get("label_ru") or top.get("class_name") or "Нет результата",
            "status": record.expert_status, "lat": payload.get("lat"), "lon": payload.get("lon"),
            "note": payload.get("note", ""), "path": f"/inspections/{record.owner_id}/{record.client_id}"}


@router.get("/")
def dashboard(request: Request, filters: Annotated[Filters, Query()],
              user: User = Depends(web_user), database: Session = Depends(session)):
    records = database.scalars(filtered(user, filters).offset((filters.page - 1) * 50).limit(51)).all()
    condition = Inspection.payload["prediction"]["detections"][0]["class_name"].astext
    label = Inspection.payload["prediction"]["detections"][0]["label_ru"].astext
    options = select(condition, label).where(condition.is_not(None)).distinct().limit(250)
    if not user.is_expert:
        options = options.where(Inspection.owner_id == user.id)
    context = {"user": user, "csrf": csrf(request), "filters": filters, "rows": [display(row) for row in records[:50]],
               "more": len(records) > 50, "crops": CROPS, "conditions": database.execute(options).all()}
    name = "rows.html" if request.headers.get("HX-Request") == "true" else "dashboard.html"
    return templates.TemplateResponse(request=request, name=name, context=context)


def owned_inspection(database: Session, user: User, owner_id: UUID, inspection_id: UUID):
    if not user.is_expert and user.id != owner_id:
        raise HTTPException(404, "Inspection not found")
    record = database.get(Inspection, (owner_id, inspection_id), populate_existing=True)
    if record is None:
        raise HTTPException(404, "Inspection not found")
    return record


def boxes(payload):
    prediction = payload.get("prediction", {})
    width, height = prediction.get("decoded_width"), prediction.get("decoded_height")
    if not isinstance(width, (int, float)) or not isinstance(height, (int, float)) or not 0 < width <= 2048 or not 0 < height <= 2048:
        return 1, 1, []
    detections = prediction.get("detections", [])
    if not isinstance(detections, list):
        return width, height, []
    result = []
    for box in detections[:300]:
        coords = box.get("xyxy_pixels") if isinstance(box, dict) else None
        if not isinstance(coords, list) or len(coords) != 4 or not all(isinstance(v, (int, float)) and math.isfinite(v) for v in coords):
            continue
        left, top, right, bottom = coords
        if 0 <= left < right <= width and 0 <= top < bottom <= height:
            result.append({"left": left, "top": top, "width": right - left, "height": bottom - top,
                           "label": str(box.get("label_ru") or box.get("class_name") or "Лист")})
    return width, height, result


@router.get("/inspections/{owner_id}/{inspection_id}")
def inspection(request: Request, owner_id: UUID, inspection_id: UUID,
               user: User = Depends(web_user), database: Session = Depends(session)):
    record = owned_inspection(database, user, owner_id, inspection_id)
    width, height, annotations = boxes(record.payload)
    events = database.execute(select(ReviewEvent, User.username).join(User, ReviewEvent.reviewer_id == User.id)
                              .where(ReviewEvent.owner_id == owner_id, ReviewEvent.inspection_id == inspection_id)
                              .order_by(ReviewEvent.created_at.desc()).limit(10)).all()
    reviews = [{"status": event.status, "evidence_version": event.evidence_version,
                "created_at": event.created_at, "reviewer": username} for event, username in events]
    return templates.TemplateResponse(request=request, name="inspection.html", context={
        "user": user, "csrf": csrf(request), "record": record, "row": display(record),
        "width": width, "height": height, "boxes": annotations, "reviews": reviews})


@router.get("/preview/{owner_id}/{photo_id}")
def preview(owner_id: UUID, photo_id: UUID, user: User = Depends(web_user), database: Session = Depends(session)):
    photo = database.get(Photo, (owner_id, photo_id))
    if photo is None or photo.content is None:
        raise HTTPException(404, "Photo not uploaded")
    owned_inspection(database, user, owner_id, photo.inspection_id)
    try:
        with Image.open(io.BytesIO(photo.content)) as source:
            if source.width * source.height > MAX_PREVIEW_PIXELS:
                raise HTTPException(415, "Photo exceeds the 24 megapixel preview limit; original is preserved")
            # JPEG thumbnail uses decoder reduction; keep EXIF until orientation is applied.
            source.thumbnail((2048, 2048))
            image = ImageOps.exif_transpose(source).convert("RGB")
            output = io.BytesIO()
            image.save(output, "JPEG", quality=88)
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError):
        raise HTTPException(415, "Photo cannot be decoded") from None
    return Response(output.getvalue(), media_type="image/jpeg", headers={"Cache-Control": "private, no-store"})


@router.post("/inspections/{owner_id}/{inspection_id}/review")
def review(request: Request, owner_id: UUID, inspection_id: UUID,
           status: Literal["confirmed", "error"] = Form(), version: int = Form(ge=1),
           token: str = Form(max_length=256), user: User = Depends(web_user), database: Session = Depends(session)):
    check_csrf(request, token)
    if not user.is_expert:
        raise HTTPException(403, "Expert role required")
    owner = lock_owner(database, owner_id)
    record = owned_inspection(database, user, owner_id, inspection_id)
    if record.version != version:
        raise HTTPException(409, "Inspection changed; reload before reviewing")
    if record.expert_status != status:
        database.add(ReviewEvent(owner_id=owner_id, inspection_id=inspection_id, reviewer_id=user.id,
                                 evidence_version=record.version, status=status))
        record.expert_status = status
        record.version += 1
        owner.sync_revision += 1
        record.revision = owner.sync_revision
        database.commit()
    return RedirectResponse(f"/inspections/{owner_id}/{inspection_id}", status_code=303)


def spreadsheet_text(value):
    text = ILLEGAL_CHARACTERS_RE.sub("", str(value))
    # A note is text even when Excel would otherwise interpret it as a formula.
    return "'" + text if text.lstrip().startswith(("=", "+", "-", "@")) else text


@router.get("/export.xlsx")
def export(filters: Annotated[Filters, Query()], user: User = Depends(web_user), database: Session = Depends(session)):
    records = database.scalars(filtered(user, filters).limit(10001)).all()
    if len(records) > 10000:
        raise HTTPException(413, "Narrow the filters to at most 10000 inspections")
    book = Workbook(write_only=True)
    sheet = book.create_sheet("Осмотры")
    sheet.append(["UUID", "Дата UTC", "Культура", "Результат", "Участок", "Статус эксперта", "Заметка", "Широта", "Долгота"])
    for record in records:
        row = display(record)
        sheet.append([spreadsheet_text(row[key]) for key in ("id", "date", "crop", "label", "plot", "status", "note")] +
                     [row["lat"], row["lon"]])
    output = io.BytesIO()
    book.save(output)
    return Response(output.getvalue(), media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    headers={"Content-Disposition": 'attachment; filename="agrovoice-inspections.xlsx"'})
