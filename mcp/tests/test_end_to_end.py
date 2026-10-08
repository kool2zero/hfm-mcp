"""MCP client → hfm-mcp (stdio) → Java daemon (mock backend). Skipped when Java or the jar is missing."""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

import httpx
import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

JAR = Path(__file__).resolve().parents[2] / "daemon" / "target" / "hfm-daemon.jar"
API_KEY = "e2e-test-api-key-123456"

pytestmark = pytest.mark.skipif(not JAR.exists() or shutil.which("java") is None,
                                reason="build the daemon first: (cd daemon && mvn package)")


@pytest.fixture(scope="module")
def daemon_url(tmp_path_factory):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    workdir = tmp_path_factory.mktemp("daemon")
    cfg = workdir / "daemon.properties"
    (workdir / "loads").mkdir()
    (workdir / "loads" / "q3.dat").write_text(
        "Actual;2025;Aug;YTD;UK01;<Entity Currency>;COGS;[ICP None];[None];[None];[None];[None];777\n")
    audit = (workdir / "audit.log").as_posix()
    cfg.write_text(f"server.host=127.0.0.1\nserver.port={port}\nserver.apiKey={API_KEY}\n"
                   "backend=mock\nhfm.defaultApplication=DEMO\nlimits.maxCellsPerRequest=50\n"
                   f"actions.enabled=true\nactions.waitSeconds=5\nactions.auditLog={audit}\n"
                   "actions.allowed=START,CONSOLIDATE,EXTRACT_DATA,COPY_DATA,LOAD_DATA\n"
                   f"load.allowedDir={(workdir / 'loads').as_posix()}\n"
                   f"extract.exportDir={(workdir / 'exports').as_posix()}\n")
    proc = subprocess.Popen(["java", "-jar", str(JAR), str(cfg)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    url = f"http://127.0.0.1:{port}"
    for _ in range(100):
        try:
            if httpx.get(f"{url}/health").status_code == 200:
                break
        except httpx.HTTPError:
            time.sleep(0.1)
    else:
        proc.kill()
        pytest.fail("daemon did not start: " + proc.stderr.read().decode(errors="replace"))
    yield url
    proc.terminate()
    proc.wait(10)


def params(url: str, password: str, actions: bool = False) -> StdioServerParameters:
    env = dict(os.environ)
    env.update(HFM_DAEMON_URL=url, HFM_DAEMON_API_KEY=API_KEY, HFM_USERNAME="jdoe", HFM_PASSWORD=password,
               HFM_ENABLE_ACTIONS="true" if actions else "false")
    # HFM_MCP_TEST_COMMAND runs the tests against a built executable instead of this Python.
    exe = os.environ.get("HFM_MCP_TEST_COMMAND")
    if exe:
        return StdioServerParameters(command=exe, args=["serve"], env=env)
    return StdioServerParameters(command=sys.executable, args=["-m", "hfm_mcp.cli", "serve"], env=env)


def payload(result) -> dict:
    assert not result.is_error, result.content
    if getattr(result, "structured_content", None):
        return result.structured_content
    return json.loads(result.content[0].text)


async def test_tools_over_stdio(daemon_url):
    async with stdio_client(params(daemon_url, "password")) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            names = {t.name for t in (await session.list_tools()).tools}
            assert names == {"hfm_get_dimensions", "hfm_get_members", "hfm_search_members", "hfm_get_data",
                             "hfm_get_process_status", "hfm_validate_pov"}, "action tools only appear with HFM_ENABLE_ACTIONS"

            dims = payload(await session.call_tool("hfm_get_dimensions", {}))
            assert dims["user"] == "jdoe" and dims["application"] == "DEMO"

            found = payload(await session.call_tool("hfm_search_members", {"dimension": "Entity", "query": "kingdom"}))
            assert found["members"][0]["name"] == "UK"

            kids = payload(await session.call_tool("hfm_get_members",
                                                   {"dimension": "Account", "member": "NetIncome", "relation": "base"}))
            assert [m["name"] for m in kids["members"]] == ["Sales", "OtherRevenue", "COGS", "Opex"]

            data = payload(await session.call_tool("hfm_get_data", {
                "pov": {"Scenario": "Actual", "Year": "2025", "Period": "Dec", "View": "YTD", "Account": "Revenue"},
                "vary": {"Entity": ["{Group.[Children]}"]},
            }))
            assert data["count"] == 2
            assert data["pov"]["Account"] == "Revenue"
            assert [c["members"]["Entity"] for c in data["cells"]] == ["US", "UK"]
            assert all(c["status"] == "OK" and c["value"] > 0 for c in data["cells"])

            err = await session.call_tool("hfm_get_data", {"vary": {"Entity": ["{[Hierarchy]}"],
                                                                    "Period": ["{[Year].[Descendants]}"]}})
            assert err.is_error and "too_many_cells" in err.content[0].text


async def test_bad_password_gives_actionable_error(daemon_url):
    async with stdio_client(params(daemon_url, "nope")) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            result = await session.call_tool("hfm_get_dimensions", {})
            assert result.is_error
            assert "hfm-mcp login" in result.content[0].text


async def test_preview_then_execute_over_stdio(daemon_url):
    async with stdio_client(params(daemon_url, "password", actions=True)) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            tools = {t.name: t for t in (await session.list_tools()).tools}
            assert {"hfm_preview_action", "hfm_execute_action", "hfm_get_task_status"} <= tools.keys()
            assert tools["hfm_execute_action"].annotations.destructive_hint is True
            assert tools["hfm_preview_action"].annotations.read_only_hint is True
            # Only what the daemon's allowlist (START, CONSOLIDATE, EXTRACT/COPY/LOAD) permits is offered.
            action = tools["hfm_preview_action"].input_schema["properties"]["action"]
            assert action["enum"] == ["START", "CONSOLIDATE"], action
            assert "APPROVE" not in action["description"]
            assert "action" in tools["hfm_preview_action"].input_schema["required"]

            pov = {"Scenario": "Actual", "Year": "2025", "Period": "Nov"}
            before = payload(await session.call_tool("hfm_get_process_status",
                                                     {"pov": pov, "entities": ["{US.[Base]}"], "phases": [2]}))
            assert before["byState"] == {"Not Started": 2}

            plan = payload(await session.call_tool("hfm_preview_action", {
                "action": "START", "pov": pov, "entities": ["{US.[Base]}"], "phases": [2], "comment": "e2e"}))
            assert [t["currentState"] for t in plan["targets"]] == ["Not Started", "Not Started"]

            done = payload(await session.call_tool("hfm_execute_action", {"plan_id": plan["planId"]}))
            assert done["completed"] == 2 and done["failed"] == 0

            after = payload(await session.call_tool("hfm_get_process_status",
                                                    {"pov": pov, "entities": ["{US.[Base]}"], "phases": [2]}))
            assert after["byState"] == {"First Pass": 2}
            assert after["units"][0]["lastUser"] == "jdoe"

            consol = payload(await session.call_tool("hfm_execute_action", {"plan_id": payload(
                await session.call_tool("hfm_preview_action", {"action": "CONSOLIDATE", "pov": {"Scenario": "Budget"},
                                                               "entities": ["Group"]}))["planId"]}))
            assert consol["finished"] is True
            status = payload(await session.call_tool("hfm_get_task_status", {"task_ids": consol["taskIds"]}))
            assert status["tasks"][0]["status"] == "COMPLETED"


async def test_extract_and_copy_over_stdio(daemon_url):
    async with stdio_client(params(daemon_url, "password", actions=True)) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            plan = payload(await session.call_tool("hfm_preview_extract", {
                "slice": "S#Actual.Y#2025.P{[Base]}.E{Group.[Base]}", "prefix": "E2E"}))
            done = payload(await session.call_tool("hfm_execute_action", {"plan_id": plan["planId"]}))
            assert done["finished"] and done["files"][0]["lines"] == 12

            read_cell = {"pov": {"Scenario": "Budget", "Period": "Oct", "Entity": "UK01", "Account": "Opex"}}
            actual = payload(await session.call_tool("hfm_get_data", {
                "pov": {**read_cell["pov"], "Scenario": "Actual"}}))["cells"][0]["value"]
            plan = payload(await session.call_tool("hfm_preview_copy", {
                "source": "S#Actual.Y#2025.P#Oct", "target": "S#Budget.Y#2025.P#Oct",
                "entities_and_accounts": "E{UK.[Base]}.A{[Base]}"}))
            assert "changes data" in plan["effect"].lower()
            payload(await session.call_tool("hfm_execute_action", {"plan_id": plan["planId"]}))
            assert payload(await session.call_tool("hfm_get_data", read_cell))["cells"][0]["value"] == actual


async def test_validate_pov_and_load_over_stdio(daemon_url):
    async with stdio_client(params(daemon_url, "password", actions=True)) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            check = payload(await session.call_tool("hfm_validate_pov",
                                                    {"pov": "S#Actual.Y#2025.E#{Group.[Base]};UK1.A#Sales"}))
            assert not check["valid"]
            bad = check["dimensions"][2]["invalid"][0]
            assert bad["member"] == "UK1" and "UK" in bad["didYouMean"]

            plan = payload(await session.call_tool("hfm_preview_load", {"file": "q3.dat", "delimiter": ";"}))
            assert plan["file"]["lines"] == 1
            done = payload(await session.call_tool("hfm_execute_action", {"plan_id": plan["planId"]}))
            assert "1 cell(s) loaded" in done["logs"][0]["text"]
            cell = payload(await session.call_tool("hfm_get_data", {"pov": {
                "Scenario": "Actual", "Year": "2025", "Period": "Aug", "View": "YTD",
                "Entity": "UK01", "Account": "COGS"}}))["cells"][0]
            assert cell["value"] == 777
