package com.hfmmcp.daemon.backend;

/** A problem HFM found when scanning a journal; {@code line} is 0 for the journal as a whole. */
public final class JournalError {
    public final int line;
    public final String message;

    public JournalError(int line, String message) {
        this.line = line;
        this.message = message;
    }
}
