package com.hfmmcp.daemon.backend;

/** A report document with its metadata and its definition code/script. */
public final class ReportDetail {
    public final ReportInfo info;
    /** Report definition script / code stored in HFM. */
    public final String code;
    /** Character encoding of the stored content (UTF-8, UTF-8-BOM, UTF-16LE, UTF-16BE). */
    public final String encoding;
    /** Optional source report metadata when creating a copy of an existing report. */
    public final ReportInfo copyFrom;

    public ReportDetail(ReportInfo info, String code, String encoding) {
        this(info, code, encoding, null);
    }

    public ReportDetail(ReportInfo info, String code, String encoding, ReportInfo copyFrom) {
        this.info = info;
        this.code = code;
        this.encoding = encoding;
        this.copyFrom = copyFrom;
    }
}
