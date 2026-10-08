package com.hfmmcp.daemon;

/** The daemon's release version, stamped into the jar manifest by the release build. */
public final class DaemonVersion {
    private static final String VERSION;

    static {
        String v = DaemonVersion.class.getPackage().getImplementationVersion();
        VERSION = v == null || v.isEmpty() ? "0.0.0-dev" : v;
    }

    private DaemonVersion() {
    }

    public static String get() {
        return VERSION;
    }
}
