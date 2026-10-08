"""Settings, read from environment variables (set them in the MCP client's server config)."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path


class ConfigError(Exception):
    pass


def settings_path(env: dict[str, str]) -> Path:
    """HFM_MCP_SETTINGS, else %LOCALAPPDATA%\\hfm-mcp\\settings.json, else ~/.config/hfm-mcp/settings.json."""
    if env.get("HFM_MCP_SETTINGS"):
        return Path(env["HFM_MCP_SETTINGS"])
    if env.get("LOCALAPPDATA"):
        return Path(env["LOCALAPPDATA"]) / "hfm-mcp" / "settings.json"
    return Path.home() / ".config" / "hfm-mcp" / "settings.json"


def load_settings_file(env: dict[str, str]) -> dict[str, str]:
    """HFM_* values saved by install-client.ps1; empty when there is no (readable) file."""
    path = settings_path(env)
    try:
        # utf-8-sig: Windows PowerShell may write a byte-order mark
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return {}
    if not isinstance(data, dict):
        return {}
    return {k: str(v) for k, v in data.items() if k.startswith("HFM_") and v not in (None, "")}


@dataclass(frozen=True)
class Settings:
    daemon_url: str
    api_key: str
    username: str
    application: str | None
    ca_bundle: str | None
    timeout_seconds: float
    enable_actions: bool = False

    @staticmethod
    def from_env(env: dict[str, str] | None = None) -> Settings:
        """Settings from environment variables, falling back to the settings file the Windows
        installer writes, so `hfm-mcp login` / `check` work in any terminal."""
        env = dict(os.environ) if env is None else env
        env = {**load_settings_file(env), **{k: v for k, v in env.items() if v}}

        def required(name: str) -> str:
            value = env.get(name, "").strip()
            if not value:
                raise ConfigError(f"{name} is not set")
            return value

        url = env.get("HFM_DAEMON_URL", "http://127.0.0.1:8765").strip().rstrip("/")
        return Settings(
            daemon_url=url,
            api_key=required("HFM_DAEMON_API_KEY"),
            username=required("HFM_USERNAME"),
            application=env.get("HFM_APPLICATION", "").strip() or None,
            ca_bundle=env.get("HFM_CA_BUNDLE", "").strip() or None,
            timeout_seconds=float(env.get("HFM_TIMEOUT_SECONDS", "120")),
            enable_actions=env.get("HFM_ENABLE_ACTIONS", "").strip().lower() in ("1", "true", "yes"),
        )
