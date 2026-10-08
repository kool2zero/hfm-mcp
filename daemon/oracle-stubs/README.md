# Compile-only stubs

Minimal stand-ins for the HFM 11.2 Java API and Thrift classes that `src/oracle/java` calls,
written from the public Javadoc (*Java API Reference for Oracle Hyperion Financial Management*).
They hold signatures only, no Oracle code, and exist so CI can compile the Oracle backend.
`tools/api_manifest.py` then lists, from the compiled bytecode, exactly what the backend
references; ApiCheck verifies that list against the real EPM jars on the server. They are never packaged or run; on the EPM server the backend is compiled against
the real jars by `scripts/build-oracle-backend.ps1`.

When the backend starts using another API method, add its signature here.
