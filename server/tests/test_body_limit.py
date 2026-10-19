import asyncio

import pytest
from starlette.responses import Response

from app.body_limit import BodySizeLimitMiddleware


def run_request(messages, headers=()):
    replies = []
    consumed = []
    application_bodies = []
    incoming = iter(messages)

    async def receive():
        message = next(incoming)
        consumed.append(message)
        return message

    async def send(message):
        replies.append(message)

    async def application(scope, receive, send):
        message = await receive()
        application_bodies.append(message["body"])
        await Response(message["body"])(scope, receive, send)

    scope = {"type": "http", "method": "POST", "path": "/", "headers": list(headers)}
    asyncio.run(BodySizeLimitMiddleware(application, max_bytes=8)(scope, receive, send))
    return replies, consumed, application_bodies


@pytest.mark.parametrize("headers", [[], [(b"content-length", b"1")]])
def test_chunked_limit_counts_actual_bytes_and_stops_reading(headers):
    messages = [
        {"type": "http.request", "body": b"12345", "more_body": True},
        {"type": "http.request", "body": b"6789", "more_body": True},
        {"type": "http.request", "body": b"not consumed", "more_body": False},
    ]
    replies, consumed, bodies = run_request(messages, headers)
    assert replies[0]["status"] == 413
    assert len(consumed) == 2
    assert bodies == []


def test_exact_limit_preserves_complete_body():
    messages = [
        {"type": "http.request", "body": b"12345", "more_body": True},
        {"type": "http.request", "body": b"678", "more_body": False},
    ]
    replies, consumed, bodies = run_request(messages)
    assert replies[0]["status"] == 200
    assert bodies == [b"12345678"]


@pytest.mark.parametrize("value,status", [(b"9", 413), (b"bad", 400), (b"-1", 400)])
def test_invalid_or_oversized_length_is_rejected_before_receiving(value, status):
    replies, consumed, bodies = run_request([], [(b"content-length", value)])
    assert replies[0]["status"] == status
    assert consumed == []
    assert bodies == []


def test_disconnect_does_not_pass_partial_body_to_application():
    messages = [
        {"type": "http.request", "body": b"123", "more_body": True},
        {"type": "http.disconnect"},
    ]
    replies, consumed, bodies = run_request(messages)
    assert replies == []
    assert bodies == []
