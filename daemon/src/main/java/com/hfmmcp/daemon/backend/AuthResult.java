package com.hfmmcp.daemon.backend;

/**
 * Outcome of a successful Shared Services (CSS) authentication.
 *
 * <p>The SSO token is held in memory only, for as long as the daemon session lives. It lets the
 * daemon re-open an HFM session that timed out without asking the user for their password again.
 */
public final class AuthResult {
    private final String userName;
    private final String ssoToken;

    public AuthResult(String userName, String ssoToken) {
        this.userName = userName;
        this.ssoToken = ssoToken;
    }

    public String userName() {
        return userName;
    }

    public String ssoToken() {
        return ssoToken;
    }

    @Override
    public String toString() {
        return "AuthResult{userName=" + userName + ", ssoToken=<redacted>}";
    }
}
