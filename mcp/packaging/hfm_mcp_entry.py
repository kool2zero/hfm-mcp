"""Entry point for the standalone hfm-mcp executable (PyInstaller)."""

import sys

from hfm_mcp.cli import main

if __name__ == "__main__":
    sys.exit(main())
