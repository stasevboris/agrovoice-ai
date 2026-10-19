"""Bound HTTP bodies before JSON/form parsing, including chunked requests."""
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send


MAX_REQUEST_BYTES = 12 * 1024 * 1024


class BodySizeLimitMiddleware:
    def __init__(self, app: ASGIApp, max_bytes: int = MAX_REQUEST_BYTES):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] != "http" or scope["method"] not in {"POST", "PUT", "PATCH"}:
            await self.app(scope, receive, send)
            return

        for name, value in scope["headers"]:
            if name.lower() == b"content-length":
                try:
                    length = int(value)
                    if length < 0:
                        raise ValueError
                except ValueError:
                    await JSONResponse({"detail": "Invalid Content-Length"}, 400)(scope, receive, send)
                    return
                if length > self.max_bytes:
                    await self.reject(scope, receive, send)
                    return

        # Retain at most one bounded body; the application can consume it normally.
        # Counting actual bytes also covers absent or misleading Content-Length.
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > self.max_bytes:
                await self.reject(scope, receive, send)
                return
            body.extend(chunk)
            if not message.get("more_body", False):
                break

        pending = True

        async def bounded_receive():
            nonlocal pending
            if pending:
                pending = False
                content = bytes(body)
                body.clear()
                return {"type": "http.request", "body": content, "more_body": False}
            return await receive()

        await self.app(scope, bounded_receive, send)

    async def reject(self, scope: Scope, receive: Receive, send: Send):
        await JSONResponse({"detail": "Request body exceeds 12 MiB"}, 413)(scope, receive, send)
