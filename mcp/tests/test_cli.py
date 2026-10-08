"""CLI: login stores the password in the keychain; check uses it."""

from __future__ import annotations

import keyring
import pytest
from keyring.backend import KeyringBackend

from hfm_mcp import cli


class MemoryKeyring(KeyringBackend):
    priority = 1

    def __init__(self):
        super().__init__()
        self.store: dict[tuple[str, str], str] = {}

    def get_password(self, service, username):
        return self.store.get((service, username))

    def set_password(self, service, username, password):
        self.store[(service, username)] = password

    def delete_password(self, service, username):
        self.store.pop((service, username), None)


@pytest.fixture
def ring(monkeypatch):
    backend = MemoryKeyring()
    keyring.set_keyring(backend)
    monkeypatch.setenv("HFM_DAEMON_URL", "http://daemon.test")
    monkeypatch.setenv("HFM_DAEMON_API_KEY", "k" * 20)
    monkeypatch.setenv("HFM_USERNAME", "jdoe")
    monkeypatch.delenv("HFM_PASSWORD", raising=False)
    return backend


def test_login_password_stdin_stores_after_successful_check(ring, monkeypatch, capsys):
    import io

    seen = {}

    async def fake_login(self, password=None):
        seen["password"] = password
        return {"sessionId": "s", "user": "jdoe", "application": "DEMO"}

    monkeypatch.setattr(cli.DaemonClient, "login", fake_login)
    monkeypatch.setattr("sys.stdin", _BytesStdin(b"s3cret\r\n"))
    assert cli.main(["login", "--password-stdin"]) == 0
    assert seen["password"] == "s3cret"
    assert ring.store[("hfm-mcp", "jdoe@http://daemon.test")] == "s3cret"
    assert "Logged in as jdoe on DEMO" in capsys.readouterr().out


@pytest.mark.parametrize("raw", [
    b"\xef\xbb\xbfs3cret\r\n",   # Windows PowerShell 5.1 pipes UTF-8 with a byte-order mark
    b"s3cret\n",
    "s3cr\u00e9t\n".encode("utf-8"),
])
def test_password_stdin_decoding(raw, monkeypatch):
    monkeypatch.setattr("sys.stdin", _BytesStdin(raw))
    expected = "s3cr\u00e9t" if b"\xc3" in raw else "s3cret"
    assert cli._read_stdin_line() == expected


class _BytesStdin:
    def __init__(self, data: bytes):
        import io

        self.buffer = io.BytesIO(data)


def test_version(capsys):
    with pytest.raises(SystemExit) as e:
        cli.main(["--version"])
    assert e.value.code == 0
    assert "hfm-mcp" in capsys.readouterr().out


def test_settings_file_fills_missing_env(tmp_path):
    from hfm_mcp.config import Settings

    f = tmp_path / "settings.json"
    f.write_bytes(b'\xef\xbb\xbf{"HFM_DAEMON_URL": "https://srv:8765", "HFM_DAEMON_API_KEY": "kfile", '
                  b'"HFM_USERNAME": "jdoe", "HFM_CA_BUNDLE": "C:/ca.pem", "HFM_ENABLE_ACTIONS": "true"}')
    s = Settings.from_env({"HFM_MCP_SETTINGS": str(f)})
    assert (s.daemon_url, s.api_key, s.username, s.ca_bundle, s.enable_actions) == \
        ("https://srv:8765", "kfile", "jdoe", "C:/ca.pem", True)
    # the environment (e.g. an MCP client's config) still wins
    s = Settings.from_env({"HFM_MCP_SETTINGS": str(f), "HFM_USERNAME": "other", "HFM_DAEMON_API_KEY": ""})
    assert (s.username, s.api_key) == ("other", "kfile")


def test_no_settings_file_still_needs_env(tmp_path):
    from hfm_mcp.config import ConfigError, Settings

    with pytest.raises(ConfigError):
        Settings.from_env({"HFM_MCP_SETTINGS": str(tmp_path / "missing.json")})


def test_check_reports_both_versions_and_a_mismatch(ring, monkeypatch, capsys):
    async def fake_health(self):
        return {"status": "ok", "version": "0.1.0"}

    async def fake_get(self, path, params=None):
        return {"user": "jdoe", "application": "DEMO", "dimensions": [{"name": "Entity"}]}

    monkeypatch.setattr(cli, "__version__", "0.2.3")
    monkeypatch.setattr(cli.DaemonClient, "health", fake_health)
    monkeypatch.setattr(cli.DaemonClient, "get", fake_get)
    assert cli.main(["check"]) == 0
    out = capsys.readouterr().out
    assert "hfm-mcp 0.2.3; HFM daemon 0.1.0" in out
    assert "WARNING" in out and "install.ps1 on the HFM server" in out
    assert "OK: jdoe on DEMO" in out
