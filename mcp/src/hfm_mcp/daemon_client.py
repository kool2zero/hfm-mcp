"""Async client for the HFM daemon's HTTP API, with transparent (re-)login."""

from __future__ import annotations

import asyncio
import hashlib
from collections.abc import Callable
from typing import Any

import httpx

from .config import Settings


class DaemonError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


class AuthError(DaemonError):
    """Login refused (bad or missing password, or throttled). Never retried automatically."""


PasswordSource = Callable[[], "str | None"]


class DaemonClient:
    def __init__(
        self,
        settings: Settings,
        password_source: PasswordSource,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self._settings = settings
        self._password_source = password_source
        self._session_id: str | None = None
        self._login_lock = asyncio.Lock()
        # Fingerprint of a password the daemon rejected, and the error it gave: the same password
        # is not sent again (it would only count towards an LDAP lockout), but a new one is.
        self._rejected: tuple[str, AuthError] | None = None
        verify: Any = settings.ca_bundle if settings.ca_bundle else True
        self._http = httpx.AsyncClient(
            base_url=settings.daemon_url,
            headers={"X-Api-Key": settings.api_key},
            timeout=settings.timeout_seconds,
            verify=verify,
            transport=transport,
        )

    async def aclose(self) -> None:
        if self._session_id:
            try:
                await self._http.delete("/api/v1/sessions/current", headers={"X-Session-Id": self._session_id})
            except httpx.HTTPError:
                pass
            self._session_id = None
        await self._http.aclose()

    async def login(self, password: str | None = None) -> dict[str, Any]:
        """Logs in with the given password, or the stored one. Raises AuthError on refusal."""
        pwd = password if password is not None else self._password_source()
        if not pwd:
            self._session_id = None
            raise AuthError(401, "no_password",
                            f"No HFM password stored for {self._settings.username}. "
                            "Run `hfm-mcp login` in a terminal, then retry.")
        body: dict[str, Any] = {"username": self._settings.username, "password": pwd}
        if self._settings.application:
            body["application"] = self._settings.application
        try:
            resp = await self._http.post("/api/v1/sessions", json=body)
        except httpx.HTTPError as e:
            raise DaemonError(503, "daemon_unreachable", _unreachable(self._settings.daemon_url, e)) from e
        data = _decode(resp)
        if resp.status_code != 200:
            err = data.get("error", {})
            code = err.get("code", "error")
            message = err.get("message", resp.text)
            if code in ("auth_failed", "too_many_attempts"):
                raise AuthError(resp.status_code, code, message)
            raise DaemonError(resp.status_code, code, message)
        self._session_id = data["sessionId"]
        return data

    async def health(self) -> dict[str, Any]:
        """The daemon's /health (status and, from 0.2.3, version); needs no key or login."""
        try:
            resp = await self._http.get("/health")
        except httpx.HTTPError as e:
            raise DaemonError(503, "daemon_unreachable", _unreachable(self._settings.daemon_url, e)) from e
        if resp.status_code != 200:
            raise DaemonError(resp.status_code, "unhealthy", f"HFM daemon /health returned {resp.status_code}")
        return _decode(resp)

    async def capabilities(self) -> dict[str, Any]:
        """The daemon's settings (which actions it allows); needs the API key but no login."""
        try:
            resp = await self._http.get("/api/v1/capabilities")
        except httpx.HTTPError as e:
            raise DaemonError(503, "daemon_unreachable", _unreachable(self._settings.daemon_url, e)) from e
        data = _decode(resp)
        if resp.status_code != 200:
            err = data.get("error", {})
            raise DaemonError(resp.status_code, err.get("code", "error"), err.get("message", resp.text))
        return data

    async def get(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        clean = {k: v for k, v in (params or {}).items() if v is not None}
        return await self._call("GET", path, params=clean)

    async def post(self, path: str, body: dict[str, Any], timeout: float | None = None) -> dict[str, Any]:
        if timeout is not None:
            return await self._call("POST", path, json=body, timeout=timeout)
        return await self._call("POST", path, json=body)

    async def _ensure_session(self, stale: str | None = None) -> str:
        async with self._login_lock:
            # Another task may already have replaced the stale session.
            if self._session_id and self._session_id != stale:
                return self._session_id
            self._session_id = None
            password = self._password_source()
            fingerprint = _fingerprint(password)
            if self._rejected is not None and self._rejected[0] == fingerprint:
                raise self._rejected[1]
            try:
                await self.login(password or "")
            except AuthError as e:
                if e.code == "auth_failed":
                    self._rejected = (fingerprint, e)
                raise
            self._rejected = None
            assert self._session_id is not None
            return self._session_id

    async def _call(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        sid = await self._ensure_session()
        for attempt in range(2):
            try:
                resp = await self._http.request(method, path, headers={"X-Session-Id": sid}, **kwargs)
            except httpx.HTTPError as e:
                raise DaemonError(503, "daemon_unreachable", _unreachable(self._settings.daemon_url, e)) from e
            data = _decode(resp)
            if resp.status_code == 200:
                return data
            err = data.get("error", {})
            code = err.get("code", "error")
            if code == "session_expired" and attempt == 0:
                sid = await self._ensure_session(stale=sid)
                continue
            raise DaemonError(resp.status_code, code, err.get("message", resp.text))
        raise AssertionError("unreachable")


def _fingerprint(password: str | None) -> str:
    return hashlib.sha256((password or "").encode()).hexdigest()


def _decode(resp: httpx.Response) -> dict[str, Any]:
    try:
        data = resp.json()
    except ValueError:
        return {"error": {"code": "bad_response", "message": f"Daemon returned non-JSON (HTTP {resp.status_code})"}}
    return data if isinstance(data, dict) else {}


def _unreachable(url: str, e: Exception) -> str:
    return f"Cannot reach the HFM daemon at {url}: {e.__class__.__name__}: {e}"
