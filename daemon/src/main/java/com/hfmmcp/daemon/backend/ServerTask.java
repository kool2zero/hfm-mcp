package com.hfmmcp.daemon.backend;

/** Calculation tasks HFM runs on the server, as from a data grid's right-click menu. */
public enum ServerTask {
    /** Impacted consolidation: only what needs it. */
    CONSOLIDATE,
    CONSOLIDATE_ALL_WITH_DATA,
    CONSOLIDATE_ALL,
    CALCULATE,
    FORCE_CALCULATE,
    TRANSLATE,
    FORCE_TRANSLATE
}
