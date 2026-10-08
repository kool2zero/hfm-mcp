package com.hfmmcp.daemon.backend;

/** A dimension of the application, in POV-string order. */
public final class DimensionInfo {
    private final String prefix;
    private final String name;
    private final String defaultMember;

    public DimensionInfo(String prefix, String name, String defaultMember) {
        this.prefix = prefix;
        this.name = name;
        this.defaultMember = defaultMember;
    }

    /** Short name used in POV strings, e.g. {@code E} in {@code E#Corp}. */
    public String prefix() {
        return prefix;
    }

    /** Dimension name, e.g. {@code Entity}. */
    public String name() {
        return name;
    }

    /** Member taken from the application's default POV. */
    public String defaultMember() {
        return defaultMember;
    }
}
