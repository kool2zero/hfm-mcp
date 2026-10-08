#!/usr/bin/env bash
# Builds the standalone hfm-mcp executable (a folder: dist/hfm-mcp/hfm-mcp[.exe]) with PyInstaller.
# Run from mcp/ with the package installed in the active Python environment.
set -euo pipefail
cd "$(dirname "$0")/.."
python -m PyInstaller --noconfirm --clean --console --name hfm-mcp \
    --distpath build/exe --workpath build/pyinstaller --specpath build/spec \
    --additional-hooks-dir "$PWD/packaging/hooks" --collect-submodules mcp_types \
    --collect-submodules keyring --copy-metadata keyring \
    --collect-submodules hfm_mcp \
    "$PWD/packaging/hfm_mcp_entry.py"
