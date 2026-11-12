package com.hfmmcp.daemon.backend;

import java.util.List;

/** Filter for listing report documents in HFM Document Manager. */
public final class ReportQuery {
    /** Report types to include (e.g. JOURNAL, INTERCOMPANY); null or empty for all report types. */
    public final List<String> reportTypes;
    /** Folder path in Document Manager; null or blank for the root folder. */
    public final String folder;
    /** Optional case-insensitive substring matched against report name or description. */
    public final String query;
    /** Whether to include private reports owned by the current user. */
    public final boolean includePrivate;

    public ReportQuery(List<String> reportTypes, String folder, String query, boolean includePrivate) {
        this.reportTypes = reportTypes;
        this.folder = folder;
        this.query = query;
        this.includePrivate = includePrivate;
    }
}
