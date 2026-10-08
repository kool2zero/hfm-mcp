# HFM MCP

MCP access to Oracle Hyperion Financial Management 11.2, through the HFM Java API: data,
metadata and process-control status, plus optional, previewed process-control actions and
consolidations. Users sign in with their own LDAP credentials, so HFM security applies to everything.

```
 Antigravity / any MCP client          (user's machine)
        │ MCP over stdio
        ▼
 hfm-mcp  (Python, mcp/)               (user's machine)
   - 4 read-only tools
   - password kept in the OS keychain
        │ HTTPS + X-Api-Key + X-Session-Id
        ▼
 hfm-daemon  (Java 8, daemon/)         (an EPM server)
   - logs in through Shared Services (CSS → LDAP); keeps the SSO token, never the password
   - one HFM session per user, re-opened automatically on timeout
   - per-user metadata cache, cell/member limits, login throttling
        │ HFM Java API (Thrift)
        ▼
 HFM cluster
```

Why two processes? EPM 11.2 runs on JDK 8, and the HFM API relies on JAXB and Oracle's
Thrift and logging libraries, all certified only on that JDK. The official MCP SDKs need newer
runtimes. Keeping HFM in a small JDK 8 daemon keeps it on a supported runtime.

## Tools

| Tool | Purpose |
|---|---|
| `hfm_get_dimensions` | Dimensions, their POV prefixes, and the default POV. |
| `hfm_get_members` | Children / descendants / base / parents / ancestors of a member, or any HFM member list (`{Group.[Base]}`). |
| `hfm_search_members` | Find exact member names from a user's wording (name or description). |
| `hfm_get_data` | Read cells: a fixed `pov` plus `vary` lists, cross-joined. Returns value, calc status (OK/CN/CH/TR/…) and flags. |
| `hfm_get_process_status` | Process-control state per entity and phase (Not Started … Published), with the last action, user, time and comment. |
| `hfm_validate_pov` | Check every member of an HFM POV string (`;` lists, `{Parent.[List]}`, `Parent.Child`), with reasons and "did you mean" suggestions. |

With actions enabled (see below), three more:

| Tool | Purpose |
|---|---|
| `hfm_preview_action` | Resolve an action's targets and show their current state. Changes nothing; returns a single-use `planId`. |
| `hfm_execute_action` | Run a previewed plan: START / PROMOTE / SUBMIT / REJECT / APPROVE / PUBLISH, or CONSOLIDATE / CALCULATE / TRANSLATE (and their ALL / FORCE variants). |
| `hfm_preview_extract` | Prepare an Extended Analytics extract: a `;`-delimited flat file (unpacked to `extract.exportDir`, with a preview of its first lines), or a star schema / metadata refresh in a DSN. |
| `hfm_preview_copy` | Prepare a data copy between slices (`ManageDataOM.copyData`; MERGE / REPLACE / ACCUMULATE). |
| `hfm_preview_load` | Prepare loading a native-format data file from `load.allowedDir` (MERGE / ACCUMULATE / REPLACE / REPLACE_WITH_SECURITY), or a `scan_only` dry run. Returns HFM's load log. |
| `hfm_get_task_status` | Progress of a consolidation or extract that was still running when `hfm_execute_action` returned. |

Only `COPY_DATA` and `LOAD_DATA` write cell data, and both are off unless added to `actions.allowed`.

## Actions (process control and consolidation)

Off unless switched on in **both** places: `actions.enabled=true` in the daemon config, and
`HFM_ENABLE_ACTIONS=true` in the user's MCP config. Safeguards:

- **Preview, then confirm.** `hfm_preview_action` lists every process unit or entity it would
  touch, with its current state. The model is instructed to show this and get the user's explicit
  yes. `hfm_execute_action` runs only that plan. Plans are single-use, expire after
  `actions.planTtlMinutes`, and belong to the session that made them.
- **The user's own HFM rights.** Actions run in the user's HFM session, so someone who cannot
  submit in HFM cannot submit here either. There is no shared admin account.
- **Allow-list and limits.** `actions.allowed` names the permitted actions (APPROVE, PUBLISH and the
  ALL/FORCE consolidations are off by default). `actions.maxUnitsPerRequest` caps the targets.
- **Audit log.** One JSON line per target in `actions.auditLog`, recording user, application,
  action, POV, phase, outcome and HFM's message.

Process actions follow proven usage from existing HFM batch jobs:
- one HFM call per phase, because a multi-phase call needs every phase at the same level
- `include_descendants` for "selected entity and descendants"
- HFM's "not at the proper review level / already …" answers reported as **skipped**, not failed

Consolidations use `DataOM.executeServerTask`, and the daemon waits up to `actions.waitSeconds`
for them, polling `AdministrationOM.getCurrentTaskProgress`.

Extracts and copies follow the same proven usage:
- extracts use EA with line-item detail; flat files are `;`-delimited without a header and fetched
  with `getRunningTaskFile`; databases get `STARSCHEMA_CREATE` with the DSN from `getDSNDetails`
- copies use `CopyDataOptions` with Periodic view and MERGE mode by default

Loads use native format with standard `DataLoadOptions`, and
HFM's task log (`getRunningTaskLog`) returned with the result. Files must be inside
`load.allowedDir`. Calculate and translate finish within the call; only consolidations, extracts
and loads are polled.

All of these are off by default. A WAREHOUSE extract followed by your SQL MCP gives fast bulk analysis on
a freshly refreshed star schema.

## How login works (LDAP)

1. Each user runs `hfm-mcp login` once. It asks for their HFM (LDAP) password, checks it
   against the daemon, and stores it in the OS keychain (Windows Credential Manager or macOS
   Keychain).
2. On the first tool call, `hfm-mcp` sends username and password to the daemon over HTTPS.
3. The daemon calls `Security.authenticateUser` (Shared Services). Shared Services checks the
   password against LDAP and returns an SSO token. The daemon opens an HFM session with
   `SessionOM.createSession(token, …)`, and gives `hfm-mcp` only an opaque session id. The daemon
   never stores the password, and the token never leaves the daemon.
4. If the HFM session times out, the daemon re-opens it with the token. If the token has expired
   too, `hfm-mcp` logs in again with the stored password.
5. A rejected password is never re-sent automatically, and the daemon refuses further logins
   for a user after repeated failures (`auth.maxFailures`). A broken setup therefore can't lock
   the LDAP account.

## Server setup (EPM server, Windows)

### With the installer (recommended)

On the EPM server (any HFM web or app server), open an **elevated** Windows PowerShell and run:

```powershell
# The repository is private: use a GitHub token that can read it (fine-grained, Contents: read-only)
$env:HFM_MCP_GITHUB_TOKEN = '<token>'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12   # GitHub needs TLS 1.2
irm 'https://api.github.com/repos/kool2zero/hfm-mcp/contents/install.ps1' -Headers @{
    Authorization = "Bearer $env:HFM_MCP_GITHUB_TOKEN"; Accept = 'application/vnd.github.raw' } | iex
```

The installer walks through every step and explains each one:
1. It finds EPM: `EPM_ORACLE_HOME`, the instance, and EPM's JDK 8.
2. It downloads the latest release package and checks its SHA-256.
3. It installs into `<EPM drive>:\hfm-mcp`.
4. It checks the prebuilt Oracle backend against this server's EPM jars (see below).
5. It asks for the HFM application, whether users connect over the network, and which actions to
   allow. For network access it creates a TLS certificate.
6. It generates the API key and restricts the config to Administrators and the service account.
7. It opens the firewall port, then installs and starts the Windows service.
8. It checks the daemon answers, and prints the settings users need: URL, API key, and the
   certificate for `HFM_CA_BUNDLE`.

**Upgrades:** run the same command again. Files are replaced and your configuration is kept.

**Options:** pass them as `HFM_MCP_*` environment variables before the `irm` line, or as
parameters when running the script file:

| Variable | Parameter | Purpose |
|---|---|---|
| `HFM_MCP_VERSION` | `-Version v0.2.0` | Install a specific release instead of the latest. |
| `HFM_MCP_INSTALL_DIR` | `-InstallDir` | Install folder. Default: the existing service's folder on an upgrade. |
| `HFM_MCP_PACKAGE` | `-Package <zip>` | Offline: install a downloaded release zip; no GitHub access needed. |
| `HFM_MCP_APPLICATION` | `-Application` | HFM application name. |
| `HFM_MCP_COMPILE_ON_SERVER=1` | `-CompileOnServer` | Compile the Oracle backend on this server instead of using the prebuilt one. |
| `HFM_MCP_UNATTENDED=1` | `-Unattended` | No prompts; defaults and the values above. |

**Offline servers:** copy `install.ps1` and `hfm-mcp-server-<version>.zip` from a release, then
run `.\install.ps1 -Package .\hfm-mcp-server-<version>.zip`.

If the repository is ever made public, the token is no longer needed:
`irm https://github.com/kool2zero/hfm-mcp/releases/latest/download/install.ps1 | iex`.

#### Prebuilt Oracle backend

Release packages contain `hfm-daemon-oracle.jar` already compiled, so the server needs no build
step:
- CI compiles it against `daemon/oracle-stubs`, signature-only stand-ins for the HFM API. Each of
  them was checked against Oracle's 11.2 Javadoc.
- The jar carries a manifest of every HFM and Thrift class and method it uses.
- `scripts\check-oracle-backend.ps1` (run by the installer) verifies that manifest against the
  server's real EPM jars. The daemon repeats the check at startup.
- If anything differs, for example on another patch set, the installer compiles the backend on
  the server instead, with `scripts\build-oracle-backend.ps1`.

#### Publishing a release

Push a tag: `git tag v0.2.0 && git push origin v0.2.0`. The Release workflow:
- runs the tests
- builds `hfm-mcp-server-<version>.zip` (daemon jar, prebuilt Oracle backend, scripts, config
  template)
- builds and tests `hfm-mcp-client-<version>-windows-x64.zip` on Windows: the end-to-end tests
  run against the `.exe`, and the job also covers Credential Manager and `install-client.ps1`
- publishes both with `install.ps1`, `install-client.ps1` and `SHA256SUMS.txt`

### By hand

1. **Build the daemon jar** (anywhere with JDK 8+ and Maven), then copy `daemon/` to the EPM server:
   ```
   cd daemon
   mvn package            # → target/hfm-daemon.jar (tests run against the mock backend)
   ```
2. **Compile the Oracle backend on the EPM server** against the real EPM jars (or use the
   prebuilt `target\hfm-daemon-oracle.jar` from a release package and check it with
   `scripts\check-oracle-backend.ps1`):
   ```powershell
   $env:EPM_ORACLE_HOME = 'E:\Oracle\Middleware\EPMSystem11R1'           # adjust
   $env:EPM_ORACLE_INSTANCE = 'E:\Oracle\Middleware\user_projects\epmsystem1'
   powershell -ExecutionPolicy Bypass -File scripts\build-oracle-backend.ps1
   ```
   `scripts\epm-env.ps1` finds EPM's bundled `jdk1.8*` and builds the classpath from the
   standard EPM jar folders. If a class is missing, its comments show how to find the jar.
3. **Configure** by copying `config\daemon.example.properties` to `config\daemon.properties`:
   - `hfm.defaultApplication` is your HFM application name.
   - Set `HFM_DAEMON_API_KEY` (a long random string) as an environment variable for the service.
   - For access from users' machines, set `server.host` to the server's address and create a TLS
     keystore (the command is in the example file). The daemon refuses plain HTTP on a
     non-loopback address.
4. **Try it in a console** first (Ctrl+C stops it):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\run-daemon.ps1
   ```
5. **Install it as a Windows service** from an elevated PowerShell:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\install-service.ps1 -Account -Start
   ```
   This creates `service\hfm-daemon.exe`, a [WinSW](https://github.com/winsw/winsw) 2.12.0 service
   wrapper (MIT licence, SHA-256 checked), which starts the daemon with the same command line as
   `run-daemon.ps1`. The service:
   - starts automatically, delayed so EPM's own services come up first
   - restarts after 30 s if the daemon stops unexpectedly
   - writes rotating logs to `logs\`
   - on stop, lets the daemon close its HFM sessions cleanly

   Options:
   - `-Account` prompts for the Windows account to run as. Use the account your EPM services run
     as; LocalSystem may not be able to read the EPM instance.
   - `-WinswExe <path>` installs from a WinSW copy you downloaded yourself, for servers without
     internet access. Download `WinSW.NET461.exe` from the v2.12.0 release.
   - `-GenerateOnly` writes `service\hfm-daemon.xml` without installing anything, so you can
     review it first.

   Manage it from `services.msc` or with `service\hfm-daemon.exe start|stop|restart|status`.
   After changing EPM paths, JVM options (`HFM_DAEMON_JAVA_OPTS`) or the config path, re-run
   the installer and restart the service. Remove it with `scripts\uninstall-service.ps1`.

   Secrets: a service doesn't see your console's environment variables. Either put
   `server.apiKey` (and the keystore password) in `daemon.properties` and allow only
   Administrators and the service account to read it, or set `HFM_DAEMON_API_KEY` as a
   machine-level environment variable.

### Verified against HFM

The Oracle backend follows the published 11.2 Javadoc (*Java API Reference for Oracle Hyperion
Financial Management*) and has been run against HFM 11.2.21: login, cells, members and custom
dimensions, process status and actions, consolidation, copy, load and flat-file extract.
- cells are read with `DataOM.getCellsDataAndStatus`, using `rawData`, `cellStatus` and `errorDetail`
- dimensions come from `MetadataOM.getDimensions(ALL)` and are matched to POV prefixes by `shortName`
- hierarchies come from `MetadataOM.getMembers` (`[Hierarchy]`, `RecordSetRange` 0..-1); HFM
  returns members parent-qualified (`PARENT.CHILD`), which the daemon splits. A member's
  children, descendants, base, parents and ancestors are worked out from that hierarchy, because
  HFM ignores the member in a list such as `{Group.[Children]}`
- an Extended Analytics extract needs every dimension in its slice; the preview fills omitted
  ones from the default POV and shows the complete slice
- errors are classified by `ErrorCodeConsts` and `HResultConsts`

`debug.includeRaw=true` adds every raw HFM field to responses, if something looks off.

## Client setup (each user)

### Windows: installer (recommended)

No Python and no administrator rights needed. In a normal (not elevated) PowerShell window:

```powershell
$env:HFM_MCP_GITHUB_TOKEN = '<token>'   # while the repository is private
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12   # GitHub needs TLS 1.2
irm 'https://api.github.com/repos/kool2zero/hfm-mcp/contents/install-client.ps1' -Headers @{
    Authorization = "Bearer $env:HFM_MCP_GITHUB_TOKEN"; Accept = 'application/vnd.github.raw' } | iex
```

The installer:
1. Downloads `hfm-mcp.exe`, a standalone build with Python bundled in, and checks its SHA-256.
2. Installs it in `%LOCALAPPDATA%\hfm-mcp` and adds it to your PATH.
3. Asks for the server URL and API key (from your administrator) and your HFM (LDAP) username.
4. Fetches the server's certificate and shows its SHA-256 fingerprint. Compare it with the one
   the server installer printed for your administrator. A certificate from your company's CA is
   trusted automatically.
5. Stores your HFM password in Windows Credential Manager and tests the connection
   (`hfm-mcp check`).
6. Adds the `hfm` server to the MCP clients it finds: **Antigravity**
   (`~/.gemini/antigravity/mcp_config.json`), **Claude Desktop**, **Claude Code** and **Cursor**.
   Existing entries are kept and a `.bak` copy is saved. For any other client, it prints the
   JSON to paste.

Restart your MCP client afterwards. To upgrade, run the installer again; your previous answers
become the defaults. To remove everything (exe, PATH entry, client entries, stored password),
add `-Uninstall`, or set `HFM_MCP_UNINSTALL=1` before `irm`.

For offline machines, use `.\install-client.ps1 -Package .\hfm-mcp-client-<version>-windows-x64.zip`
from a release. Options can be preset with environment variables (`HFM_DAEMON_URL`,
`HFM_DAEMON_API_KEY`, `HFM_USERNAME`, `HFM_APPLICATION`, `HFM_CA_BUNDLE`, `HFM_MCP_UNATTENDED=1`),
which makes scripted rollouts possible.

Useful commands, in any terminal (they read the installer's
`%LOCALAPPDATA%\hfm-mcp\settings.json`):
- `hfm-mcp login`: change the stored password, e.g. after an LDAP password change
- `hfm-mcp check`: test the connection, and show the client and server versions (with a warning and what to re-run when they differ)
- `hfm-mcp logout`: remove the stored password
- `hfm-mcp --version`

### macOS / Linux, or by hand

Needs Python 3.10+ and [uv](https://docs.astral.sh/uv/):

```
uv tool install "git+https://github.com/kool2zero/hfm-mcp#subdirectory=mcp"
```

Store your password once, and again whenever your LDAP password changes:

```
export HFM_DAEMON_URL=https://epm-server.example.com:8765
export HFM_DAEMON_API_KEY=<from your admin>
export HFM_USERNAME=<your LDAP user>
export HFM_CA_BUNDLE=<path to the server certificate .pem>
hfm-mcp login && hfm-mcp check
```

Then add the server to your MCP client's config. Antigravity, Claude Desktop, Claude Code and
Cursor all use this shape:

```json
{
  "mcpServers": {
    "hfm": {
      "command": "hfm-mcp",
      "env": {
        "HFM_DAEMON_URL": "https://epm-server.example.com:8765",
        "HFM_DAEMON_API_KEY": "<from your admin>",
        "HFM_USERNAME": "<your LDAP user>",
        "HFM_APPLICATION": "<optional; defaults to the daemon's>",
        "HFM_CA_BUNDLE": "<PEM file of the server certificate, for a self-signed one>",
        "HFM_ENABLE_ACTIONS": "<optional: true to add the action tools; the daemon must allow them too>"
      }
    }
  }
}
```

## Development

Everything runs locally against the in-memory mock backend. Any username works, with the
password `password`.

```
cd daemon && mvn package
java -jar target/hfm-daemon.jar config/daemon.mock.properties

cd mcp && uv venv && uv pip install -e ".[dev]"
.venv/bin/pytest                      # includes an end-to-end test over stdio against the daemon
```

Layout:

```
daemon/src/main/java      daemon core: HTTP API, sessions, POV handling, calc status, mock backend
daemon/src/oracle/java    Oracle backend (compiled on the EPM server against EPM jars)
daemon/scripts            PowerShell build/run scripts for the EPM server
mcp/src/hfm_mcp           MCP server, daemon client, keychain handling, CLI
daemon/oracle-stubs       signature-only HFM API stand-ins the prebuilt backend compiles against
```

### Daemon HTTP API

All `/api/v1/*` calls need `X-Api-Key`. All except login also need `X-Session-Id`.

| Method | Path | Body / query |
|---|---|---|
| POST | `/api/v1/sessions` | `{username, password, application?}` → `{sessionId, user, application}` |
| DELETE | `/api/v1/sessions/current` | |
| GET | `/api/v1/dimensions` | |
| GET | `/api/v1/members` | `dimension, member?, relation?, expression?, limit?` |
| GET | `/api/v1/members/search` | `dimension, q, limit?` |
| POST | `/api/v1/cells` | `{pov: {dim: member}, vary: {dim: [members or {lists}]}}` |
| POST | `/api/v1/process/status` | `{pov, entities, phases}` |
| POST | `/api/v1/pov/validate` | `{pov}` (HFM notation) |
| GET | `/api/v1/capabilities` | `{actionsEnabled, actionsAllowed}` |
| POST | `/api/v1/actions/preview` | `{action, pov, entities, phases, level?, includeDescendants?, comment?}` → `{planId, targets…}` |
| POST | `/api/v1/actions/preview-extract` | `{slice, prefix, format?, dsn?, includeCalculated?, includeDerived?, includeDynamicAccounts?}` |
| POST | `/api/v1/actions/preview-copy` | `{source, target, entitiesAndAccounts, mode?, view?, scale?, copyRatesAndSystemData?, copyDerivedData?, copyCellText?}` |
| POST | `/api/v1/actions/preview-load` | `{file, mode?, delimiter?, scanOnly?, accumulateWithinFile?, containsOwnershipData?}` |
| POST | `/api/v1/actions/execute` | `{planId}` |
| POST | `/api/v1/tasks/status` | `{taskIds}` |
| GET | `/health` | no auth |

Errors are returned as `{"error": {"code", "message"}}`. The codes include `auth_failed`,
`session_expired`, `too_many_attempts`, `unknown_dimension`, `hfm_rejected`,
`too_many_cells`, `hfm_error`, `actions_disabled`, `action_not_allowed`, `plan_not_found` and
`too_many_units`.

## License

Public domain ([The Unlicense](LICENSE)): use, change and redistribute it freely, no conditions.
Release files include third-party components under their own permissive licenses; see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
Oracle, Hyperion and HFM are trademarks of Oracle; this project is not affiliated with Oracle.
