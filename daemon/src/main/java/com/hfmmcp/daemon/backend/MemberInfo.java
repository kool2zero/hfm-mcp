package com.hfmmcp.daemon.backend;

import java.util.Map;

/** A dimension member as returned by a member query. */
public final class MemberInfo {
    private final String name;
    private final String description;
    private final String parent;
    private final Map<String, Object> raw;

    public MemberInfo(String name, String description, String parent, Map<String, Object> raw) {
        this.name = name;
        this.description = description;
        this.parent = parent;
        this.raw = raw;
    }

    /**
     * A member as HFM lists it in a hierarchy: there the name is parent-qualified
     * ({@code GROUP.UK01}, HFM labels cannot contain '.') and the parent field may be empty.
     * The name is split so callers always see the bare member and its parent at this location.
     */
    public static MemberInfo fromHfm(String name, String description, String parent, Map<String, Object> raw) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String prefix = name.substring(0, dot);
            String qualifier = prefix.substring(prefix.lastIndexOf('.') + 1);
            return new MemberInfo(name.substring(dot + 1), description, qualifier, raw);
        }
        return new MemberInfo(name, description, parent, raw);
    }

    public String name() {
        return name;
    }

    /** May be null when HFM does not return one. */
    public String description() {
        return description;
    }

    /** May be null when HFM does not return one. */
    public String parent() {
        return parent;
    }

    /** Every field HFM returned, for diagnostics; may be null. */
    public Map<String, Object> raw() {
        return raw;
    }
}
