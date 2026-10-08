"""The MCP tools. All read-only: they never change HFM data or run calculations."""

from __future__ import annotations

from typing import Annotated, Any, Literal

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import ToolAnnotations
from pydantic import Field

from .daemon_client import AuthError, DaemonClient, DaemonError

INSTRUCTIONS = """\
Read-only access to Oracle Hyperion Financial Management (HFM), with the signed-in user's own
HFM security applied.

How to work with it:
1. Call hfm_get_dimensions first. It lists the dimensions (Scenario, Year, Period, View, Value,
   Entity, Account, ICP and the custom dimensions) and the application's default POV.
2. Find exact member names with hfm_search_members or hfm_get_members. Member names must match
   HFM exactly; do not guess them.
3. Read numbers with hfm_get_data. Any dimension you leave out of `pov` takes its default-POV
   member, so always set Scenario, Year, Period, View and Value deliberately, and report the full
   POV you used ("pov" in the result) alongside the numbers.

Reading results:
- value null means the cell has no data (flag NODATA), which is different from 0.
- status is HFM's calc status. OK means current. CN (needs consolidation), CH (needs calculation)
  and TR (needs translation) mean the stored number may be stale: always tell the user when any
  cell you report is not OK. NOACCESS means the user's HFM security hides the cell.
- status ERROR means HFM could not read that one cell (usually an invalid member); its `error`
  says why. The other cells are still valid.
- status INVALID means the intersection does not exist in HFM's metadata, usually because the
  account is not valid with the ICP or Custom members used: a Custom left at its [None] default
  where the account needs a Custom total, or the reverse. It is neither zero nor "no data"; do
  not report it as such. Find the Custom dimensions' top members (hfm_get_members with no
  member) and retry with them set explicitly in `pov`.
- View: YTD is year-to-date, Periodic is the period alone.
- Value: <Entity Currency> is the entity's local currency; a currency code (e.g. USD) or
  <Parent Currency> gives translated amounts.

Process control: hfm_get_process_status shows each process unit's (Scenario, Year, Period, Value,
Entity) state per phase - Not Started, First Pass, Review Level n, Submitted, Approved, Published -
and who acted last.
"""

ACTIONS_INSTRUCTIONS = """
Actions (process control and consolidation) change HFM and run under the user's own HFM rights.
Never act on your own initiative. For every action:
1. Call hfm_preview_action. It changes nothing; it lists every target and its current state.
2. Show the user the action, POV, targets and current states, and ask them to confirm.
3. Only after an explicit yes for that preview, call hfm_execute_action with its planId. Plans are
   single-use and expire; if the user changes anything, preview again.
4. Report completed / skipped (already at or past that level) / failed per target. A long
   consolidation or extract may still be running: check it with hfm_get_task_status.
Data extracts (hfm_preview_extract), copies (hfm_preview_copy) and file loads (hfm_preview_load) follow the same
preview → confirm → hfm_execute_action steps. Copies and loads change data; say so plainly,
including the mode (MERGE / REPLACE / ACCUMULATE), when asking for confirmation. Suggest a
scan_only load first for a file that has not been loaded before, and summarise the errors in its log.
"""

READ_ONLY = ToolAnnotations(read_only_hint=True, destructive_hint=False, idempotent_hint=True, open_world_hint=False)


PREVIEW = ToolAnnotations(read_only_hint=True, destructive_hint=False, idempotent_hint=False, open_world_hint=False)
EXECUTE = ToolAnnotations(read_only_hint=False, destructive_hint=True, idempotent_hint=False, open_world_hint=False)

# Process-control actions and server tasks hfm_preview_action can prepare, with what each does.
ACTIONS: dict[str, str] = {
    "START": "START",
    "PROMOTE": "PROMOTE (needs level)",
    "SUBMIT": "SUBMIT",
    "REJECT": "REJECT",
    "APPROVE": "APPROVE",
    "PUBLISH": "PUBLISH",
    "CONSOLIDATE": "CONSOLIDATE (impacted)",
    "CONSOLIDATE_ALL_WITH_DATA": "CONSOLIDATE_ALL_WITH_DATA",
    "CONSOLIDATE_ALL": "CONSOLIDATE_ALL",
    "CALCULATE": "CALCULATE",
    "FORCE_CALCULATE": "FORCE_CALCULATE",
    "TRANSLATE": "TRANSLATE",
    "FORCE_TRANSLATE": "FORCE_TRANSLATE",
}

UNIT_POV = Field(description="Scenario, Year, Period and Value of the process units, e.g. "
                             "{'Scenario': 'Actual', 'Year': '2025', 'Period': 'Dec'}. Omitted ones use the "
                             "default POV; Value defaults to it too (usually <Entity Currency>).")
ENTITIES = Field(description="Entities, or HFM member lists such as '{Group.[Base]}'. Omit for the default-POV entity.")
PHASES = Field(description="Submission phases 1-9 (default [1]).")

# Execution can include a consolidation the daemon waits on (actions.waitSeconds) plus one HFM
# call per process unit.
EXECUTE_TIMEOUT_SECONDS = 900.0


def build_server(client: DaemonClient, enable_actions: bool = False,
                 allowed_actions: set[str] | None = None, version_warning: str | None = None) -> MCPServer:
    """`allowed_actions` is the daemon's allowlist (None if it could not be read): only those
    actions are offered, so the tools never advertise one the daemon would refuse.
    `version_warning` says the client and daemon versions differ; the model is told to pass it on."""
    instructions = INSTRUCTIONS + (ACTIONS_INSTRUCTIONS if enable_actions else "")
    if version_warning:
        instructions += ("\nVERSION MISMATCH: " + version_warning + " Tell the user this once, before your "
                         "first answer, and mention it if a tool behaves unexpectedly.\n")
    server = MCPServer(name="hfm", title="Oracle HFM", instructions=instructions)

    async def call(fn: Any, *args: Any) -> dict[str, Any]:
        try:
            return await fn(*args)
        except AuthError as e:
            if e.code == "auth_failed":
                raise ToolError("HFM rejected the stored password. Ask the user to run `hfm-mcp login` in a "
                                "terminal to update it, then retry.") from e
            raise ToolError(e.message) from e
        except DaemonError as e:
            raise ToolError(f"{e.message} [{e.code}]") from e

    @server.tool(annotations=READ_ONLY)
    async def hfm_get_dimensions() -> dict[str, Any]:
        """List the application's dimensions (name and POV prefix) and its default POV.

        Call this first: it tells you which dimension names hfm_get_data and the member tools
        accept and which member each dimension defaults to.
        """
        return await call(client.get, "/api/v1/dimensions")

    @server.tool(annotations=READ_ONLY)
    async def hfm_get_members(
        dimension: Annotated[str, Field(description="Dimension name or prefix, e.g. 'Entity' or 'E'")],
        member: Annotated[
            str | None, Field(description="Member to navigate from. Omit to list the whole hierarchy.")
        ] = None,
        relation: Annotated[
            Literal["children", "descendants", "base", "parents", "ancestors", "member"],
            Field(description="Which members related to `member` to return; 'member' just validates it."),
        ] = "children",
        expression: Annotated[
            str | None,
            Field(description="Advanced: an HFM member list such as '{Group.[Base]}' or a named list "
                              "'{MyList}'. Overrides member/relation."),
        ] = None,
        limit: Annotated[int, Field(ge=1, le=2000, description="Maximum members to return")] = 200,
    ) -> dict[str, Any]:
        """Navigate a dimension hierarchy: children, descendants, base members, parents or ancestors
        of a member. Returns names, descriptions and parents, plus `total` and `truncated`."""
        params = {"dimension": dimension, "member": member, "relation": relation,
                  "expression": expression, "limit": limit}
        return await call(client.get, "/api/v1/members", params)

    @server.tool(annotations=READ_ONLY)
    async def hfm_search_members(
        dimension: Annotated[str, Field(description="Dimension name or prefix, e.g. 'Account' or 'A'")],
        query: Annotated[str, Field(description="Text to find in member names or descriptions (case-insensitive)")],
        limit: Annotated[int, Field(ge=1, le=500)] = 25,
    ) -> dict[str, Any]:
        """Find members whose name or description contains `query`, exact and prefix matches first.
        Use it to turn a user's wording ("cost of sales", "UK") into exact HFM member names."""
        return await call(client.get, "/api/v1/members/search",
                          {"dimension": dimension, "q": query, "limit": limit})

    @server.tool(annotations=READ_ONLY)
    async def hfm_get_data(
        pov: Annotated[
            dict[str, str] | None,
            Field(description="Fixed members by dimension name or prefix, e.g. "
                              "{'Scenario': 'Actual', 'Year': '2025', 'Period': 'Dec', 'View': 'YTD', "
                              "'Account': 'Sales'}. Omitted dimensions use the default POV."),
        ] = None,
        vary: Annotated[
            dict[str, list[str]] | None,
            Field(description="Dimensions to vary, each with a list of members; the result is every "
                              "combination. Entries may be HFM member lists, e.g. "
                              "{'Entity': ['{Group.[Children]}'], 'Period': ['Q1', 'Q2']}."),
        ] = None,
    ) -> dict[str, Any]:
        """Read HFM cell values with their calc status.

        Returns the shared `pov`, then one entry per cell with the varying `members`, `value`
        (null = no data), `status` (OK, CN, CH, TR, NOACCESS, ERROR, ...), `flags` and, for ERROR,
        `error`; plus `staleCount`, `noDataCount` and `errorCount`. Keep requests modest: the daemon rejects requests over its cell limit
        (2000 by default).
        """
        return await call(client.post, "/api/v1/cells", {"pov": pov or {}, "vary": vary or {}})

    @server.tool(annotations=READ_ONLY)
    async def hfm_validate_pov(
        pov: Annotated[str, Field(description="POV in HFM notation; a dimension may list several members "
                                              "separated by ';' and use member lists, e.g. "
                                              "'S#Actual;Budget.Y#2025.E#{Group.[Base]};UK01.A#Sales'")],
    ) -> dict[str, Any]:
        """Check that every member in an HFM POV string exists, e.g. a POV for a batch file, report
        or load. Lists each invalid member with the reason and close matches (`didYouMean`), plus
        unknown and omitted dimensions."""
        return await call(client.post, "/api/v1/pov/validate", {"pov": pov})

    @server.tool(annotations=READ_ONLY)
    async def hfm_get_process_status(
        pov: Annotated[dict[str, str] | None, UNIT_POV] = None,
        entities: Annotated[list[str] | None, ENTITIES] = None,
        phases: Annotated[list[int] | None, PHASES] = None,
    ) -> dict[str, Any]:
        """Process-control state of process units: per entity and phase, the state (Not Started,
        First Pass, Review Level n, Submitted, Approved, Published) and the last action, user, time
        and comment. `byState` counts units per state, e.g. to answer "who has not submitted yet"."""
        return await call(client.post, "/api/v1/process/status",
                          {"pov": pov or {}, "entities": entities or [], "phases": phases or []})

    if not enable_actions:
        return server

    def allowed(name: str) -> bool:
        return allowed_actions is None or name in allowed_actions

    offered = [a for a in ACTIONS if allowed(a)]
    action_field = Field(..., description="One of: " + ", ".join(ACTIONS[a] for a in offered) + ".",
                         json_schema_extra={"enum": offered})

    @server.tool(annotations=PREVIEW)
    async def hfm_preview_action(
        action: str = action_field,
        pov: Annotated[dict[str, str] | None, UNIT_POV] = None,
        entities: Annotated[list[str] | None, ENTITIES] = None,
        phases: Annotated[list[int] | None, Field(description="Phases 1-9 for process actions (default [1]); "
                                                              "ignored by server tasks.")] = None,
        level: Annotated[int | None, Field(ge=1, le=10, description="Review level for PROMOTE")] = None,
        include_descendants: Annotated[bool, Field(description="Process actions only: also act on each "
                                                               "entity's descendants, like Process Control's "
                                                               "'Selected entity and descendants'.")] = False,
        comment: Annotated[str | None, Field(description="Process-control comment recorded in HFM")] = None,
    ) -> dict[str, Any]:
        """Prepare an HFM action WITHOUT running it: resolves every target, shows its current
        process state, and returns a single-use `planId`. Show the result to the user and get their
        explicit confirmation before calling hfm_execute_action."""
        body = {"action": action, "pov": pov or {}, "entities": entities or [], "phases": phases or [],
                "level": level, "includeDescendants": include_descendants, "comment": comment}
        return await call(client.post, "/api/v1/actions/preview", body)

    @server.tool(annotations=PREVIEW)
    async def hfm_preview_extract(
        slice: Annotated[str, Field(description="Extended Analytics slice in POV syntax, e.g. "
                                                "'S#Actual.Y#2025.P#Jan;Feb;Mar.W#Periodic.E#{Group.[Base]}"
                                                ".A#{[Base]}.C2#{[Base]}'. Several members are joined with ';', "
                                                "lists are written X#{...}. HFM needs every dimension: omitted "
                                                "ones take the default-POV member, and the preview shows the "
                                                "complete slice to confirm.")],
        prefix: Annotated[str, Field(description="File or table prefix: letters, digits, _ (max 20)")],
        format: Annotated[Literal["FLATFILE", "WAREHOUSE", "METADATA"],
                          Field(description="FLATFILE: ';'-delimited file on the daemon's export share (with a "
                                            "preview of its first lines). WAREHOUSE: (re)create the EA star schema "
                                            "in a database. METADATA: metadata tables only.")] = "FLATFILE",
        dsn: Annotated[str | None, Field(description="DSN for WAREHOUSE/METADATA; defaults to the daemon's")] = None,
        include_calculated: bool = True,
        include_derived: bool = False,
        include_dynamic_accounts: bool = False,
    ) -> dict[str, Any]:
        """Prepare an Extended Analytics data extract WITHOUT running it; returns a `planId`.
        WAREHOUSE/METADATA replace existing tables with the same prefix in that DSN. After a
        WAREHOUSE refresh, the star schema can be queried with SQL tools."""
        body = {"slice": slice, "prefix": prefix, "format": format, "dsn": dsn,
                "includeCalculated": include_calculated, "includeDerived": include_derived,
                "includeDynamicAccounts": include_dynamic_accounts}
        return await call(client.post, "/api/v1/actions/preview-extract", body)

    @server.tool(annotations=PREVIEW)
    async def hfm_preview_copy(
        source: Annotated[str, Field(description="Source Scenario/Year/Period slice, e.g. 'S#Actual.Y#2025.P#Dec'")],
        target: Annotated[str, Field(description="Target slice, e.g. 'S#Forecast.Y#2025.P#Dec'")],
        entities_and_accounts: Annotated[str, Field(description="Entities and accounts to copy in HFM copy-data "
                                                                "syntax, e.g. 'E{Group.[Base]}.A{[Base]}', or "
                                                                "single members 'E#UK01.A#Sales'. Braces only "
                                                                "hold lists, never a single member.")],
        mode: Annotated[Literal["MERGE", "REPLACE", "ACCUMULATE"],
                        Field(description="MERGE overwrites target cells that have source data; REPLACE clears "
                                          "the target first; ACCUMULATE adds to the target.")] = "MERGE",
        view: Annotated[Literal["Periodic", "YTD"], Field()] = "Periodic",
        scale: Annotated[float, Field(description="Multiply copied values by this factor")] = 1.0,
        copy_rates_and_system_data: bool = True,
        copy_derived_data: bool = False,
        copy_cell_text: bool = False,
    ) -> dict[str, Any]:
        """Prepare a data copy between slices WITHOUT running it; returns a `planId`. Executing it
        CHANGES DATA in the target slice."""
        body = {"source": source, "target": target, "entitiesAndAccounts": entities_and_accounts, "mode": mode,
                "view": view, "scale": scale, "copyRatesAndSystemData": copy_rates_and_system_data,
                "copyDerivedData": copy_derived_data, "copyCellText": copy_cell_text}
        return await call(client.post, "/api/v1/actions/preview-copy", body)

    @server.tool(annotations=PREVIEW)
    async def hfm_preview_load(
        file: Annotated[str, Field(description="Data file name (or path) inside the daemon's load folder")],
        mode: Annotated[Literal["MERGE", "ACCUMULATE", "REPLACE", "REPLACE_WITH_SECURITY"],
                        Field(description="MERGE overwrites cells in the file; ACCUMULATE adds to existing data; "
                                          "REPLACE clears each Scenario/Year/Period/Entity/Value in the file first")
                        ] = "MERGE",
        delimiter: Annotated[str, Field(min_length=1, max_length=1)] = ",",
        scan_only: Annotated[bool, Field(description="SCAN: validate the file and return HFM's log, load nothing")] = False,
        accumulate_within_file: bool = False,
        contains_ownership_data: bool = False,
    ) -> dict[str, Any]:
        """Prepare loading an HFM data file (native format) WITHOUT running it; shows the file's size
        and first lines and returns a `planId`. Executing it CHANGES DATA unless scan_only. The
        result includes HFM's load log."""
        body = {"file": file, "mode": mode, "delimiter": delimiter, "scanOnly": scan_only,
                "accumulateWithinFile": accumulate_within_file, "containsOwnershipData": contains_ownership_data}
        return await call(client.post, "/api/v1/actions/preview-load", body)

    @server.tool(annotations=EXECUTE)
    async def hfm_execute_action(
        plan_id: Annotated[str, Field(description="planId from hfm_preview_action, after the user confirmed it")],
    ) -> dict[str, Any]:
        """Run a previewed HFM action, extract or copy exactly as previewed. Only call this after the
        user explicitly confirmed that preview. Returns completed / skipped / failed per target, a
        copy's result, or for a consolidation/extract its task status (it may still be running; a
        finished flat-file extract includes its file path and first lines)."""
        return await call(client.post, "/api/v1/actions/execute", {"planId": plan_id}, EXECUTE_TIMEOUT_SECONDS)

    @server.tool(annotations=READ_ONLY)
    async def hfm_get_task_status(
        task_ids: Annotated[list[int], Field(description="taskIds from hfm_execute_action")],
    ) -> dict[str, Any]:
        """Progress of consolidation / calculation / extract tasks started by hfm_execute_action.
        When a flat-file extract has finished, includes its file path and first lines."""
        return await call(client.post, "/api/v1/tasks/status", {"taskIds": task_ids})

    for tool, action in (("hfm_preview_action", None), ("hfm_preview_extract", "EXTRACT_DATA"),
                         ("hfm_preview_copy", "COPY_DATA"), ("hfm_preview_load", "LOAD_DATA")):
        if (not offered if action is None else not allowed(action)):
            server.remove_tool(tool)
    if allowed_actions is not None and not allowed_actions:
        server.remove_tool("hfm_execute_action")
        server.remove_tool("hfm_get_task_status")
    return server
