package com.hfmmcp.daemon.session;

import com.hfmmcp.daemon.backend.AuthResult;
import com.hfmmcp.daemon.backend.BackendSession;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One logged-in user on one application. Holds the CSS token and the open HFM session; never the
 * password. Callers hold {@link #lock()} while they use the HFM session.
 */
public final class UserSession {
    private final String id;
    private final AuthResult auth;
    private final String application;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile BackendSession backendSession;
    private volatile long lastUsedMillis;
    private volatile Object metadataCache;
    private final java.util.Map<String, Object> actionPlans = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, Object> pendingExtracts = new java.util.concurrent.ConcurrentHashMap<>();

    UserSession(String id, AuthResult auth, String application, BackendSession backendSession) {
        this.id = id;
        this.auth = auth;
        this.application = application;
        this.backendSession = backendSession;
        touch();
    }

    public String id() {
        return id;
    }

    public AuthResult auth() {
        return auth;
    }

    public String userName() {
        return auth.userName();
    }

    public String application() {
        return application;
    }

    public ReentrantLock lock() {
        return lock;
    }

    public BackendSession backendSession() {
        return backendSession;
    }

    public void replaceBackendSession(BackendSession s) {
        this.backendSession = s;
    }

    public long lastUsedMillis() {
        return lastUsedMillis;
    }

    public void touch() {
        lastUsedMillis = System.currentTimeMillis();
    }

    /** Previewed actions awaiting execution, by plan id; owned by the action service. */
    public java.util.Map<String, Object> actionPlans() {
        return actionPlans;
    }

    /** Flat-file extracts still running, by task id; their file is fetched once they finish. */
    public java.util.Map<Integer, Object> pendingExtracts() {
        return pendingExtracts;
    }

    /** Per-session metadata cache slot, owned by the service layer. */
    public Object metadataCache() {
        return metadataCache;
    }

    public void metadataCache(Object cache) {
        this.metadataCache = cache;
    }
}
