package com.hfmmcp.daemon.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes HFM cell status bits. Bit values come from
 * {@code oracle.epm.fm.domainobject.data.common.CALCSTATUSBITS}.
 */
public final class CalcStatus {
    private static final long PARENT_LEVEL_INPUT = 1L;
    private static final long NODATA = 2L;
    private static final long HASTRANSACTIONS = 4L;
    private static final long DERIVED = 16L;
    private static final long INVALID = 32L;
    private static final long NOREADACCESS = 256L;
    private static final long NOWRITEACCESS = 512L;
    private static final long HASTEXT = 4096L;
    private static final long NODATAINTABLE = 524288L;
    private static final long OK_BUT_SYSTEM_CHANGED = 2097152L;
    private static final long NEEDSCHARTLOGIC = 4194304L;
    private static final long NEEDSTRANSLATION = 8388608L;
    private static final long NEEDSCONSOLIDATION = 16777216L;
    private static final long VALUEMEMBER_NEEDS_CALC = 33554432L;
    private static final long LOCKED = 67108864L;
    private static final long ERROR = 2147483648L;

    private final String code;
    private final List<String> flags;
    private final boolean noData;

    private CalcStatus(String code, List<String> flags, boolean noData) {
        this.code = code;
        this.flags = flags;
        this.noData = noData;
    }

    public static CalcStatus decode(long rawBits) {
        long bits = rawBits & 0xFFFFFFFFL;
        List<String> flags = new ArrayList<>();
        String code;
        if ((bits & ERROR) != 0) {
            code = "ERROR";
        } else if ((bits & INVALID) != 0) {
            code = "INVALID";
        } else if ((bits & NOREADACCESS) != 0) {
            code = "NOACCESS";
        } else if ((bits & NEEDSCONSOLIDATION) != 0) {
            code = "CN";
        } else if ((bits & NEEDSTRANSLATION) != 0) {
            code = "TR";
        } else if ((bits & (NEEDSCHARTLOGIC | VALUEMEMBER_NEEDS_CALC)) != 0) {
            code = "CH";
        } else if ((bits & OK_BUT_SYSTEM_CHANGED) != 0) {
            code = "OK SC";
        } else {
            code = "OK";
        }
        boolean noData = (bits & (NODATA | NODATAINTABLE)) != 0;
        if (noData) {
            flags.add("NODATA");
        }
        if ((bits & LOCKED) != 0) {
            flags.add("LOCKED");
        }
        if ((bits & NOWRITEACCESS) != 0) {
            flags.add("READ_ONLY");
        }
        if ((bits & DERIVED) != 0) {
            flags.add("DERIVED");
        }
        if ((bits & PARENT_LEVEL_INPUT) != 0) {
            flags.add("PARENT_INPUT");
        }
        if ((bits & HASTRANSACTIONS) != 0) {
            flags.add("HAS_TRANSACTIONS");
        }
        if ((bits & HASTEXT) != 0) {
            flags.add("HAS_CELL_TEXT");
        }
        return new CalcStatus(code, flags, noData);
    }

    /** Summary in HFM's own vocabulary: OK, OK SC, CN, CH, TR, NOACCESS, INVALID or ERROR. */
    public String code() {
        return code;
    }

    public List<String> flags() {
        return flags;
    }

    public boolean noData() {
        return noData;
    }

    /** True when the stored value may not reflect current inputs (needs consolidation, calc or translation). */
    public boolean stale() {
        return "CN".equals(code) || "CH".equals(code) || "TR".equals(code);
    }
}
