"""Client vs HFM daemon version check. A mismatch is a warning, never a refusal: the client
copes with an older daemon, and the daemon ignores what it does not know."""

from __future__ import annotations

import re


def _parts(version: str) -> tuple[int, ...] | None:
    m = re.match(r"^v?(\d+)\.(\d+)\.(\d+)$", version.strip())
    return tuple(int(x) for x in m.groups()) if m else None


def version_warning(client: str, server: str | None) -> str | None:
    """Why the two versions differ and what to re-run, or None when they match (or either is a
    development build). `server` is None for a daemon too old to report its version."""
    mine = _parts(client)
    if mine is None:
        return None  # development build of the client
    if server is None:
        return (f"The HFM daemon is older than this hfm-mcp {client} (it does not report its version). "
                "Re-run install.ps1 on the HFM server to update it.")
    theirs = _parts(server)
    if theirs is None or theirs == mine:
        return None
    if theirs < mine:
        return (f"The HFM daemon is {server} but this hfm-mcp is {client}. "
                "Re-run install.ps1 on the HFM server to update it.")
    return (f"This hfm-mcp is {client} but the HFM daemon is {server}. "
            "Re-run install-client.ps1 on this PC to update it.")
