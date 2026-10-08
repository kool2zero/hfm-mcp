#!/usr/bin/env bash
# Builds target/hfm-daemon-oracle.jar without EPM: compiles the Oracle backend against the
# compile-only stubs (signatures checked against Oracle's 11.2 Javadoc) and embeds the API
# manifest that ApiCheck verifies against the real EPM jars on the server.
# Run after `mvn package`.
set -euo pipefail
daemon="$(cd "$(dirname "$0")/.." && pwd)"
work="$daemon/target/oracle-prebuilt"
rm -rf "$work"
mkdir -p "$work/stubs" "$work/classes"

javac --release 8 -nowarn -d "$work/stubs" $(find "$daemon/oracle-stubs/oracle" "$daemon/oracle-stubs/org" -name '*.java')
javac --release 8 -Xlint:all,-options -Werror -cp "$work/stubs:$daemon/target/hfm-daemon.jar" \
    -d "$work/classes" $(find "$daemon/src/oracle/java" -name '*.java')
# The manifest lists exactly the HFM / Thrift members the compiled backend references.
classes=$(cd "$work/classes" && find . -name '*.class' | sed 's|^\./||; s|\.class$||; s|/|.|g')
javap -c -p -cp "$work/classes" $classes \
    | python3 "$daemon/oracle-stubs/tools/api_manifest.py" > "$work/classes/hfm-api-manifest.txt"
echo "API manifest: $(grep -vc '^#' "$work/classes/hfm-api-manifest.txt") entries"
jar cf "$daemon/target/hfm-daemon-oracle.jar" -C "$work/classes" .
echo "Built $daemon/target/hfm-daemon-oracle.jar"
