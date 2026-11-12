package com.hfmmcp.daemon.backend;

/** Options for executing/rendering a report via HFM's SystemReportsHelper. */
public final class ReportRunRequest {
    /** Output format: HTML, HFM, PDF, RTF, XLS, XLSX. */
    public final String format;
    /** Optional HFM POV slice string override (e.g. S#Actual.Y#2025.P#Dec). */
    public final String povSlice;
    /** Optional entity override (used by Intercompany reports). */
    public final String entity;
    /** Optional partner / ICP override (used by Intercompany reports). */
    public final String partner;
    /** Optional stylesheet or BI Publisher template override. */
    public final String stylesheet;

    public ReportRunRequest(String format, String povSlice, String entity, String partner, String stylesheet) {
        this.format = format;
        this.povSlice = povSlice;
        this.entity = entity;
        this.partner = partner;
        this.stylesheet = stylesheet;
    }
}
