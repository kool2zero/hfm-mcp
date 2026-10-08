"""DaemonClient login/retry behaviour against a fake daemon."""

from __future__ import annotations

import httpx
import pytest

from hfm_mcp.config import Settings
from hfm_mcp.daemon_client import AuthError, DaemonClient, DaemonError

SETTINGS = Settings("http://daemon", "k" * 20, "jdoe", "DEMO", None, 5)


class FakeDaemon:
    def __init__(self, good_password: str = "secret"):
        self.good_password = good_password
        self.logins: list[str] = []
        self.live: set[str] = set()

    def handler(self, request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Api-Key"] == "k" * 20
        if request.url.path == "/api/v1/sessions":
            import json

            body = json.loads(request.content)
            self.logins.append(body["password"])
            assert body["application"] == "DEMO"
            if body["password"] != self.good_password:
                return httpx.Response(401, json={"error": {"code": "auth_failed", "message": "bad"}})
            sid = f"s{len(self.logins)}"
            self.live.add(sid)
            return httpx.Response(200, json={"sessionId": sid, "user": "jdoe", "application": "DEMO"})
        if request.headers.get("X-Session-Id") not in self.live:
            return httpx.Response(401, json={"error": {"code": "session_expired", "message": "gone"}})
        if request.url.path == "/api/v1/cells":
            return httpx.Response(400, json={"error": {"code": "too_many_cells", "message": "too many"}})
        return httpx.Response(200, json={"ok": True, "sid": request.headers["X-Session-Id"]})


def client(fake: FakeDaemon, passwords: list[str | None]) -> DaemonClient:
    it = iter(passwords)
    last: list[str | None] = [None]

    def source() -> str | None:
        last[0] = next(it, last[0])
        return last[0]

    return DaemonClient(SETTINGS, source, transport=httpx.MockTransport(fake.handler))


async def test_logs_in_lazily_and_reuses_session():
    fake = FakeDaemon()
    c = client(fake, ["secret"])
    assert (await c.get("/api/v1/dimensions"))["sid"] == "s1"
    assert (await c.get("/api/v1/dimensions"))["sid"] == "s1"
    assert fake.logins == ["secret"]


async def test_relogs_in_once_when_daemon_session_expires():
    fake = FakeDaemon()
    c = client(fake, ["secret"])
    await c.get("/api/v1/dimensions")
    fake.live.clear()
    assert (await c.get("/api/v1/dimensions"))["sid"] == "s2"
    assert fake.logins == ["secret", "secret"]


async def test_rejected_password_is_not_resent_but_a_new_one_is():
    fake = FakeDaemon()
    c = client(fake, ["wrong", "wrong", "secret"])
    with pytest.raises(AuthError):
        await c.get("/api/v1/dimensions")
    with pytest.raises(AuthError):
        await c.get("/api/v1/dimensions")
    assert fake.logins == ["wrong"], "same bad password must not be sent twice"
    assert (await c.get("/api/v1/dimensions"))["ok"]
    assert fake.logins == ["wrong", "secret"]


async def test_missing_password_explains_how_to_fix():
    c = client(FakeDaemon(), [None])
    with pytest.raises(AuthError, match="hfm-mcp login"):
        await c.get("/api/v1/dimensions")


async def test_other_errors_carry_code():
    c = client(FakeDaemon(), ["secret"])
    with pytest.raises(DaemonError) as e:
        await c.post("/api/v1/cells", {})
    assert e.value.code == "too_many_cells"
