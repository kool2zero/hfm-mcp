package com.hfmmcp.daemon.backend;

/** An Extended Analytics data extract, as {@code LoadExtractOM.extractData} takes it. */
public final class ExtractRequest {
    public enum Format {
        /** Delimited file (no header) fetched back to the daemon's export folder. */
        FLATFILE,
        /** Star schema in a database (EA warehouse). */
        WAREHOUSE,
        /** Metadata tables only, in a database. */
        METADATA
    }

    public final Format format;
    /** EA metadata slice, e.g. {@code S#Actual.Y#2025.P{[Base]}.E{Group.[Base]}...}. */
    public final String slice;
    /** File or table prefix. */
    public final String prefix;
    /** DSN for database formats; null for FLATFILE. */
    public final String dsn;
    public final boolean includeCalculated;
    public final boolean includeDerived;
    public final boolean includeDynamicAccounts;

    public ExtractRequest(Format format, String slice, String prefix, String dsn, boolean includeCalculated,
                          boolean includeDerived, boolean includeDynamicAccounts) {
        this.format = format;
        this.slice = slice;
        this.prefix = prefix;
        this.dsn = dsn;
        this.includeCalculated = includeCalculated;
        this.includeDerived = includeDerived;
        this.includeDynamicAccounts = includeDynamicAccounts;
    }
}
