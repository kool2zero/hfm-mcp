package com.hfmmcp.daemon.backend;

/** Failure reported by an {@link HfmBackend}, classified so the daemon can react to it. */
public class BackendException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Wrong username or password. */
        AUTH_FAILED,
        /** The CSS token is no longer accepted; the user has to log in again. */
        AUTH_EXPIRED,
        /** The HFM session is gone (timeout, server restart); it can be re-opened with the token. */
        SESSION_INVALID,
        /** The request referenced something HFM rejected, such as an unknown member. */
        BAD_REQUEST,
        /** Any other HFM or infrastructure failure. */
        HFM_ERROR
    }

    private final Kind kind;

    public BackendException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public BackendException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
