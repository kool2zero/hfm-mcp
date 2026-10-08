#!/usr/bin/env bash
# Builds the server package that install.ps1 installs: dist/hfm-mcp-server-<version>.zip
# (the release workflow adds the client, the installers and SHA256SUMS.txt).
# Usage: daemon/scripts/make-package.sh <version>      (after: cd daemon && mvn package)
set -euo pipefail
version="${1:?usage: make-package.sh <version>}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
daemon="$root/daemon"
jar="$daemon/target/hfm-daemon.jar"
[[ -f "$jar" ]] || { echo "missing $jar - run mvn package first" >&2; exit 1; }
"$daemon/scripts/build-oracle-prebuilt.sh"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
pkg="$stage/hfm-mcp-server"
mkdir -p "$pkg/target" "$pkg/scripts" "$pkg/config" "$pkg/src"
cp "$jar" "$daemon/target/hfm-daemon-oracle.jar" "$pkg/target/"
cp "$daemon"/scripts/*.ps1 "$pkg/scripts/"
cp "$daemon"/config/daemon.example.properties "$pkg/config/"
cp -r "$daemon/src/oracle" "$pkg/src/"
echo "$version" > "$pkg/VERSION"
cp "$root/LICENSE" "$root/THIRD-PARTY-NOTICES.md" "$pkg/"

mkdir -p "$root/dist"
zip_name="hfm-mcp-server-$version.zip"
rm -f "$root/dist/$zip_name"
(cd "$pkg" && zip -qr "$root/dist/$zip_name" .)
echo "Built dist/$zip_name ($(sha256sum "$root/dist/$zip_name" | cut -d' ' -f1))"
