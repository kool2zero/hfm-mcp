"""`hfm-mcp` (run the MCP server over stdio), `hfm-mcp login`, `hfm-mcp logout`, `hfm-mcp check`."""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import getpass
import locale
import logging
import sys
from typing import Any

from . import __version__, credentials
from .compat import version_warning
from .config import ConfigError, Settings
from .daemon_client import DaemonClient, DaemonError


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="hfm-mcp", description="MCP server for Oracle HFM")
    parser.add_argument("--version", action="version", version=f"hfm-mcp {__version__}")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("serve", help="run the MCP server over stdio (default)")
    login = sub.add_parser("login", help="store your HFM password in the OS keychain and test it")
    login.add_argument("--password-stdin", action="store_true",
                       help="read the password from standard input instead of prompting")
    sub.add_parser("logout", help="remove your stored HFM password")
    sub.add_parser("check", help="log in with the stored password and read the application's dimensions")
    args = parser.parse_args(argv)

    try:
        settings = Settings.from_env()
    except ConfigError as e:
        print(f"hfm-mcp: {e}. Run install-client.ps1, or set HFM_DAEMON_URL, HFM_DAEMON_API_KEY and "
              "HFM_USERNAME.", file=sys.stderr)
        return 2

    if args.command == "login":
        return asyncio.run(_login(settings, args.password_stdin))
    if args.command == "check":
        return asyncio.run(_check(settings))
    if args.command == "logout":
        removed = credentials.delete_password(settings.daemon_url, settings.username)
        print("Stored password removed." if removed else "No stored password.")
        return 0
    return _serve(settings)


def _serve(settings: Settings) -> int:
    from .server import build_server

    # httpx logs every request at INFO; that is noise in the MCP client's server log.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    password = lambda: credentials.get_password(settings.daemon_url, settings.username)  # noqa: E731
    allowed, warning = asyncio.run(_startup_checks(settings, password))
    if warning:
        logging.getLogger(__name__).warning(warning)
    client = DaemonClient(settings, password)
    server = build_server(client, enable_actions=settings.enable_actions,
                          allowed_actions=allowed if settings.enable_actions else None, version_warning=warning)
    try:
        server.run("stdio")
    finally:
        with contextlib.suppress(Exception):
            asyncio.run(client.aclose())
    return 0


async def _startup_checks(settings: Settings, password: Any) -> tuple[set[str] | None, str | None]:
    """The daemon's action allowlist (None: offer every action, e.g. the daemon is down or too old
    to say) and a version-mismatch warning, if any. Never stops the server from starting."""
    client = DaemonClient(settings, password)
    allowed: set[str] | None = None
    warning: str | None = None
    try:
        health = await asyncio.wait_for(client.health(), timeout=10)
        warning = version_warning(__version__, health.get("version"))
        caps = await asyncio.wait_for(client.capabilities(), timeout=10)
        allowed = set(caps.get("actionsAllowed") or []) if caps.get("actionsEnabled") else set()
    except Exception as e:  # noqa: BLE001
        logging.getLogger(__name__).warning("Could not read the daemon's version or allowed actions: %s", e)
    finally:
        with contextlib.suppress(Exception):
            await client.aclose()
    return allowed, warning


def _read_stdin_line() -> str:
    """One line from stdin as text. Windows PowerShell 5.1 pipes text to programs with a UTF-8
    byte-order mark, which must not become part of the password."""
    raw = sys.stdin.buffer.readline()
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = raw.decode(locale.getpreferredencoding(False), errors="replace")
    return text.rstrip("\r\n")


async def _check(settings: Settings) -> int:
    client = DaemonClient(settings, lambda: credentials.get_password(settings.daemon_url, settings.username))
    try:
        health = await client.health()
        server_version = health.get("version")
        print(f"hfm-mcp {__version__}; HFM daemon {server_version or 'older than 0.2.3'} at {settings.daemon_url}")
        warning = version_warning(__version__, server_version)
        if warning:
            print(f"WARNING: {warning}")
        dims = await client.get("/api/v1/dimensions")
    except DaemonError as e:
        print(f"Check failed: {e.message}", file=sys.stderr)
        return 1
    finally:
        await client.aclose()
    names = ", ".join(d["name"] for d in dims.get("dimensions", []))
    print(f"OK: {dims.get('user')} on {dims.get('application')} via {settings.daemon_url}")
    print(f"Dimensions: {names}")
    return 0


async def _login(settings: Settings, from_stdin: bool = False) -> int:
    if from_stdin:
        password = _read_stdin_line()
    else:
        password = getpass.getpass(f"HFM password for {settings.username}: ")
    if not password:
        print("No password entered.", file=sys.stderr)
        return 1
    client = DaemonClient(settings, lambda: None)
    try:
        info = await client.login(password)
    except DaemonError as e:
        print(f"Login failed: {e.message}", file=sys.stderr)
        return 1
    finally:
        await client.aclose()
    credentials.set_password(settings.daemon_url, settings.username, password)
    print(f"Logged in as {info.get('user')} on {info.get('application')}. Password saved to the OS keychain.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
