package com.hfmmcp.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Daemon settings, read from a properties file. Secrets may instead come from environment
 * variables so they need not sit in the file: {@code HFM_DAEMON_API_KEY} and
 * {@code HFM_DAEMON_KEYSTORE_PASSWORD}.
 */
public final class DaemonConfig {
    private final Properties props;

    public DaemonConfig(Properties props) {
        this.props = props;
    }

    public static DaemonConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return new DaemonConfig(p);
    }

    public String get(String key, String defaultValue) {
        String v = props.getProperty(key);
        return v == null || v.trim().isEmpty() ? defaultValue : v.trim();
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key, null);
        return v == null ? defaultValue : Integer.parseInt(v);
    }

    public boolean getBool(String key, boolean defaultValue) {
        String v = get(key, null);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }

    public String host() {
        return get("server.host", "127.0.0.1");
    }

    public int port() {
        return getInt("server.port", 8765);
    }

    public int threads() {
        return getInt("server.threads", 16);
    }

    public String apiKey() {
        String env = System.getenv("HFM_DAEMON_API_KEY");
        return env != null && !env.isEmpty() ? env : get("server.apiKey", null);
    }

    public String keystorePath() {
        return get("server.tls.keystore", null);
    }

    public char[] keystorePassword() {
        String env = System.getenv("HFM_DAEMON_KEYSTORE_PASSWORD");
        String v = env != null && !env.isEmpty() ? env : get("server.tls.keystorePassword", "");
        return v.toCharArray();
    }

    public String keystoreType() {
        return get("server.tls.keystoreType", "PKCS12");
    }

    /** Plain HTTP is only allowed on loopback unless this is set, because passwords cross the wire. */
    public boolean allowInsecureHttp() {
        return getBool("server.allowInsecureHttp", false);
    }

    public String backend() {
        return get("backend", "mock");
    }

    public String defaultApplication() {
        return get("hfm.defaultApplication", null);
    }

    public int sessionIdleTimeoutMinutes() {
        return getInt("session.idleTimeoutMinutes", 30);
    }

    public int maxSessions() {
        return getInt("session.maxSessions", 100);
    }

    public int metadataCacheTtlMinutes() {
        return getInt("metadata.cacheTtlMinutes", 30);
    }

    public int maxCellsPerRequest() {
        return getInt("limits.maxCellsPerRequest", 2000);
    }

    public int maxMembersPerRequest() {
        return getInt("limits.maxMembersPerRequest", 2000);
    }

    public int authMaxFailures() {
        return getInt("auth.maxFailures", 5);
    }

    public int authFailureWindowMinutes() {
        return getInt("auth.failureWindowMinutes", 15);
    }

    /** Process-control actions and consolidations. Off unless explicitly enabled. */
    public boolean actionsEnabled() {
        return getBool("actions.enabled", false);
    }

    /** Which of START, PROMOTE, SUBMIT, REJECT, APPROVE, PUBLISH and the server tasks may run. */
    public java.util.Set<String> actionsAllowed() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String a : get("actions.allowed", "START,PROMOTE,SUBMIT,REJECT,CONSOLIDATE,CALCULATE,TRANSLATE").split(",")) {
            if (!a.trim().isEmpty()) {
                out.add(a.trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }

    /** Most process units (entities × phases) one action may touch. */
    public int maxActionUnits() {
        return getInt("actions.maxUnitsPerRequest", 100);
    }

    public int actionPlanTtlMinutes() {
        return getInt("actions.planTtlMinutes", 10);
    }

    /** How long an action call waits for a consolidation before returning its task ids. */
    public int actionWaitSeconds() {
        return getInt("actions.waitSeconds", 60);
    }

    public String auditLogPath() {
        return get("actions.auditLog", "logs/hfm-actions-audit.log");
    }

    /** Most process units one status request may read. */
    public int maxStatusUnits() {
        return getInt("limits.maxProcessUnitsPerRequest", 500);
    }

    /** Where flat-file extracts are unpacked; ideally a share users can open. */
    public String extractExportDir() {
        return get("extract.exportDir", "exports");
    }

    /** DSN for database extracts when the request names none. */
    public String extractDefaultDsn() {
        return get("extract.defaultDsn", null);
    }

    public int extractPreviewLines() {
        return getInt("extract.previewLines", 10);
    }

    /** Folder data files must be in to be loaded; loads are refused when unset. */
    public String loadAllowedDir() {
        return get("load.allowedDir", null);
    }

    /** Most characters of an HFM task log returned. */
    public int maxLogChars() {
        return getInt("load.maxLogChars", 8000);
    }

    /** Include every raw HFM field in responses; useful once, to confirm field mappings. */
    public boolean includeRaw() {
        return getBool("debug.includeRaw", false);
    }
}
