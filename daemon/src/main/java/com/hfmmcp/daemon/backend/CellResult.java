package com.hfmmcp.daemon.backend;

import java.util.Map;

/** One cell read from HFM: its stored value plus the HFM cell status bits. */
public final class CellResult {
    private final String pov;
    private final double value;
    private final long statusBits;
    private final String error;
    private final Map<String, Object> raw;

    public CellResult(String pov, double value, long statusBits, Map<String, Object> raw) {
        this(pov, value, statusBits, null, raw);
    }

    public CellResult(String pov, double value, long statusBits, String error, Map<String, Object> raw) {
        this.pov = pov;
        this.value = value;
        this.statusBits = statusBits;
        this.error = error;
        this.raw = raw;
    }

    /** The POV string the cell was read with. */
    public String pov() {
        return pov;
    }

    public double value() {
        return value;
    }

    /** HFM cell status bit mask; decode with {@link com.hfmmcp.daemon.service.CalcStatus}. */
    public long statusBits() {
        return statusBits;
    }

    /** HFM's message when this one cell could not be read (e.g. an invalid POV); null when it was. */
    public String error() {
        return error;
    }

    /** Every field HFM returned, for diagnostics; may be null. */
    public Map<String, Object> raw() {
        return raw;
    }
}
