package com.hfmmcp.daemon.service;

import com.hfmmcp.daemon.http.Json;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Append-only JSON-lines record of every action the daemon runs against HFM. */
public final class AuditLog {
    private static final Logger LOG = Logger.getLogger(AuditLog.class.getName());

    private final Path file;

    public AuditLog(Path file) {
        this.file = file;
    }

    public synchronized void record(String user, String application, String action, String target,
                                    Integer phase, String outcome, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", Instant.now().toString());
        e.put("user", user);
        e.put("application", application);
        e.put("action", action);
        e.put("target", target);
        if (phase != null) {
            e.put("phase", phase);
        }
        e.put("outcome", outcome);
        if (message != null) {
            e.put("message", message);
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(Json.MAPPER.writeValueAsString(e));
                w.write('\n');
            }
        } catch (IOException ex) {
            // Never lose the record silently: it also goes to the daemon log.
            LOG.log(Level.SEVERE, "Cannot write audit log " + file + "; entry: " + e, ex);
        }
    }
}
