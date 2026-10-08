# PyInstaller hook: bundle the whole MCP SDK (it imports some modules dynamically), except its
# optional developer CLI, which needs extras (typer) the server does not use.
from PyInstaller.utils.hooks import collect_data_files, collect_submodules

hiddenimports = collect_submodules("mcp", filter=lambda name: not name.startswith("mcp.cli"))
datas = collect_data_files("mcp")
