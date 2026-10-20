from copy import deepcopy

from tests.test_api import client, database, migrate_test_database  # noqa: F401
from tests.test_sync import auth, push, update  # noqa: F401


def test_feedback_is_separate_bounded_and_repeatable(client, auth):
    item = update(b"photo")
    prediction = deepcopy(item["data"]["prediction"])
    feedback = {"photo_sha256": item["data"]["photos"][0]["sha256"],
        "report_sha256": "b" * 64, "analysis_version": None, "verdict": "unknown",
        "correction": "", "updated_at": 1792450000000, "revision": 1, "source": "user"}
    item["data"]["frame_feedback"] = [feedback]
    reply = push(client, auth, item)
    assert reply.status_code == 200
    result = reply.json()["results"][0]
    assert result["inspection"]["data"]["prediction"] == prediction
    assert result["inspection"]["data"]["frame_feedback"] == [feedback]
    assert push(client, auth, item).json()["results"][0]["status"] == "unchanged"
    feedback["report_sha256"] = ""
    assert push(client, auth, item).status_code == 200
    feedback["correction"] = "Cannot correct an unknown verdict"
    assert push(client, auth, item).status_code == 422
    feedback["verdict"] = "incorrect"
    feedback["correction"] = "x" * 501
    assert push(client, auth, item).status_code == 422
    feedback["correction"] = ""
    feedback["photo_sha256"] = "c" * 64
    assert push(client, auth, item).status_code == 422


def test_old_payload_repeat_remains_unchanged(client, database, auth):
    from sqlalchemy import select
    from app.models import Inspection

    item = update()
    push(client, auth, item)
    record = database.scalar(select(Inspection))
    payload = dict(record.payload)
    payload.pop("frame_feedback", None)
    record.payload = payload
    database.flush()
    assert push(client, auth, item).json()["results"][0]["status"] == "unchanged"
