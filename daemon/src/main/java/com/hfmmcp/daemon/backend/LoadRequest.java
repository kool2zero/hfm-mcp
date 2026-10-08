package com.hfmmcp.daemon.backend;

import java.nio.file.Path;

/** A data-file load, as {@code LoadExtractOM.loadData} takes it. */
public final class LoadRequest {
    /** How loaded values combine with data already in HFM. */
    public enum Mode { MERGE, ACCUMULATE, REPLACE, REPLACE_WITH_SECURITY }

    /** File on the daemon's machine (under {@code load.allowedDir}). */
    public final Path file;
    public final Mode mode;
    public final String delimiter;
    public final boolean accumulateWithinFile;
    public final boolean containsOwnershipData;
    /** SCAN: HFM validates the file and writes its log, but loads nothing. */
    public final boolean scanOnly;

    public LoadRequest(Path file, Mode mode, String delimiter, boolean accumulateWithinFile,
                       boolean containsOwnershipData, boolean scanOnly) {
        this.file = file;
        this.mode = mode;
        this.delimiter = delimiter;
        this.accumulateWithinFile = accumulateWithinFile;
        this.containsOwnershipData = containsOwnershipData;
        this.scanOnly = scanOnly;
    }
}
