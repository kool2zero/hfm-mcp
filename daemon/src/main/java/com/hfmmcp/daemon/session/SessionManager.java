package com.hfmmcp.daemon.session;

import com.hfmmcp.daemon.backend.AuthResult;
import com.hfmmcp.daemon.backend.BackendSession;
import com.hfmmcp.daemon.backend.HfmBackend;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Tracks daemon sessions by an opaque random id and closes the idle ones. */
public final class SessionManager {
    private static final Logger LOG = Logger.getLogger(SessionManager.class.getName());

    private final HfmBackend backend;
    private final long idleTimeoutMillis;
    private final int maxSessions;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    public SessionManager(HfmBackend backend, long idleTimeoutMillis, int maxSessions) {
        this.backend = backend;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxSessions = maxSessions;
    }

    /** Registers a session; returns null when the daemon is at capacity. */
    public UserSession create(AuthResult auth, String application, BackendSession backendSession) {
        if (sessions.size() >= maxSessions) {
            evictIdle();
            if (sessions.size() >= maxSessions) {
                return null;
            }
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UserSession s = new UserSession(id, auth, application, backendSession);
        sessions.put(id, s);
        LOG.info("Session opened for " + auth.userName() + " on " + application);
        return s;
    }

    /** The live session for {@code id}, or null when unknown or idle too long. */
    public UserSession get(String id) {
        if (id == null) {
            return null;
        }
        UserSession s = sessions.get(id);
        if (s == null) {
            return null;
        }
        if (isIdle(s)) {
            remove(s);
            return null;
        }
        s.touch();
        return s;
    }

    public void remove(UserSession s) {
        if (sessions.remove(s.id(), s)) {
            s.lock().lock();
            try {
                backend.closeSession(s.backendSession());
            } finally {
                s.lock().unlock();
            }
            LOG.info("Session closed for " + s.userName() + " on " + s.application());
        }
    }

    public void evictIdle() {
        for (UserSession s : new ArrayList<>(sessions.values())) {
            if (isIdle(s) && !s.lock().isLocked()) {
                remove(s);
            }
        }
    }

    public void closeAll() {
        List<UserSession> all = new ArrayList<>(sessions.values());
        for (UserSession s : all) {
            remove(s);
        }
    }

    public int size() {
        return sessions.size();
    }

    private boolean isIdle(UserSession s) {
        return System.currentTimeMillis() - s.lastUsedMillis() > idleTimeoutMillis;
    }
}
