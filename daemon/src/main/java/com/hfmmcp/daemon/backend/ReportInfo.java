package com.hfmmcp.daemon.backend;

/** One report document in HFM Document Manager. */
public final class ReportInfo {
    public final String name;
    /** Report type (e.g. JOURNAL, INTERCOMPANY, IC_MATCH_BY_ACCOUNT, IC_MATCH_BY_TRANS_ID, IC_TRANSACTION, DATA_EXPLORER, HYP). */
    public final String reportType;
    /** File type (e.g. RPTDEF, RPTXML, RPTHTML, XML, CUSTOM). */
    public final String fileType;
    /** Folder path in Document Manager (e.g. Root). */
    public final String folder;
    public final String description;
    public final String securityClass;
    public final boolean isPrivate;
    public final String owner;
    /** Epoch milliseconds or seconds, 0 when unknown. */
    public final long lastModified;

    public ReportInfo(String name, String reportType, String fileType, String folder, String description,
                      String securityClass, boolean isPrivate, String owner, long lastModified) {
        this.name = name;
        this.reportType = reportType;
        this.fileType = fileType;
        this.folder = folder;
        this.description = description;
        this.securityClass = securityClass;
        this.isPrivate = isPrivate;
        this.owner = owner;
        this.lastModified = lastModified;
    }
}
