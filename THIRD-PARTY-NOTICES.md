# Third-party software

hfm-mcp itself is public domain (see `LICENSE`). The release files also contain, or download,
the following third-party software under its own license. None of these licenses restrict how
hfm-mcp is used or licensed; they ask that their notices travel with redistributed copies.
Full license texts are in each project's repository.

## Server package (`hfm-mcp-server-<version>.zip`)

| Component | Use | License |
|-----------|-----|---------|
| [Jackson](https://github.com/FasterXML/jackson) (core, databind, annotations) | JSON, shaded into `hfm-daemon.jar` | Apache-2.0 |
| [WinSW](https://github.com/winsw/winsw) 2.12.0 | Windows service wrapper; **not included**, downloaded by `install-service.ps1` at install time | MIT |

The Oracle backend uses the HFM Java API on the EPM server itself; no Oracle code is included.
`daemon/oracle-stubs` holds only method signatures written from Oracle's public Javadoc, used to
compile the backend in CI; it is never packaged.

## Client (`hfm-mcp-client-<version>-windows-x64.zip`)

`hfm-mcp.exe` is built with [PyInstaller](https://pyinstaller.org) (GPL-2.0 with an exception
that allows distributing built programs under any license) and contains:

| Component | License |
|-----------|---------|
| Python runtime | PSF-2.0 |
| [MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk) (`mcp`, `mcp-types`) | MIT |
| httpx, httpcore, starlette, sse-starlette, uvicorn, idna, click | BSD-3-Clause |
| pydantic, pydantic-core, anyio, h11, jsonschema, referencing, rpds-py, attrs, keyring, jaraco.*, more-itertools, PyJWT, truststore, annotated-types, typing-inspection | MIT |
| pywin32-ctypes | BSD-3-Clause |
| typing-extensions | PSF-2.0 |
| python-multipart, opentelemetry-api | Apache-2.0 |
| cryptography | Apache-2.0 or BSD-3-Clause |
| cffi | MIT-0 |
| certifi | MPL-2.0 (unmodified; source at https://github.com/certifi/python-certifi) |

The exact set follows `mcp/pyproject.toml` and the versions resolved when the release was built.
