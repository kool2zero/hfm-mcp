package com.hfmmcp.daemon.backend;

/** A data copy between slices, as {@code ManageDataOM.copyData} takes it. */
public final class CopyRequest {
    public enum Mode { MERGE, REPLACE, ACCUMULATE }

    /** Source Scenario/Year/Period slice, e.g. {@code S#Actual.Y#2025.P#Dec}. */
    public final String source;
    public final String target;
    /** Entities and accounts to copy, in HFM's copy-data syntax, e.g. {@code E{Group.[Base]}.A{[Base]}}. */
    public final String entitiesAndAccounts;
    public final String view;
    public final Mode mode;
    public final boolean copyRatesAndSystemData;
    public final boolean copyDerivedData;
    public final boolean copyCellText;
    public final double scale;

    public CopyRequest(String source, String target, String entitiesAndAccounts, String view, Mode mode,
                       boolean copyRatesAndSystemData, boolean copyDerivedData, boolean copyCellText, double scale) {
        this.source = source;
        this.target = target;
        this.entitiesAndAccounts = entitiesAndAccounts;
        this.view = view;
        this.mode = mode;
        this.copyRatesAndSystemData = copyRatesAndSystemData;
        this.copyDerivedData = copyDerivedData;
        this.copyCellText = copyCellText;
        this.scale = scale;
    }
}
