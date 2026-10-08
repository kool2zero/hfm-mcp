"""The user's HFM (LDAP) password, kept in the OS keychain rather than in config files.

`hfm-mcp login` stores it; the MCP server reads it whenever it has to log in to the daemon. The
HFM_PASSWORD environment variable overrides the keychain, for headless setups only.
"""

from __future__ import annotations

import os

import keyring
import keyring.errors

SERVICE = "hfm-mcp"


def _account(daemon_url: str, username: str) -> str:
    return f"{username}@{daemon_url}"


def get_password(daemon_url: str, username: str) -> str | None:
    env = os.environ.get("HFM_PASSWORD")
    if env:
        return env
    try:
        return keyring.get_password(SERVICE, _account(daemon_url, username))
    except keyring.errors.KeyringError:
        return None


def set_password(daemon_url: str, username: str, password: str) -> None:
    keyring.set_password(SERVICE, _account(daemon_url, username), password)


def delete_password(daemon_url: str, username: str) -> bool:
    try:
        keyring.delete_password(SERVICE, _account(daemon_url, username))
        return True
    except keyring.errors.PasswordDeleteError:
        return False
