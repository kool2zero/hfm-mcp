package com.hfmmcp.daemon.session;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refuses further logins for a username after repeated failures, so a misconfigured client cannot
 * lock the user's LDAP account by retrying a bad password.
 */
public final class LoginThrottle {
    private final int maxFailures;
    private final long windowMillis;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public LoginThrottle(int maxFailures, long windowMillis) {
        this.maxFailures = maxFailures;
        this.windowMillis = windowMillis;
    }

    public boolean isBlocked(String userName) {
        Deque<Long> q = failures.get(key(userName));
        if (q == null) {
            return false;
        }
        synchronized (q) {
            prune(q);
            return q.size() >= maxFailures;
        }
    }

    public void recordFailure(String userName) {
        Deque<Long> q = failures.computeIfAbsent(key(userName), k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(System.currentTimeMillis());
        }
    }

    public void recordSuccess(String userName) {
        failures.remove(key(userName));
    }

    private void prune(Deque<Long> q) {
        long cutoff = System.currentTimeMillis() - windowMillis;
        while (!q.isEmpty() && q.peekFirst() < cutoff) {
            q.removeFirst();
        }
    }

    private static String key(String userName) {
        return userName.trim().toLowerCase(Locale.ROOT);
    }
}
