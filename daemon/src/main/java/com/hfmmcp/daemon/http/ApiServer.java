package com.hfmmcp.daemon.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.hfmmcp.daemon.DaemonConfig;
import com.hfmmcp.daemon.backend.CopyRequest;
import com.hfmmcp.daemon.backend.ExtractRequest;
import com.hfmmcp.daemon.backend.LoadRequest;
import com.hfmmcp.daemon.service.ActionService;
import com.hfmmcp.daemon.service.HfmService;
import com.hfmmcp.daemon.session.SessionManager;
import com.hfmmcp.daemon.session.UserSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * JSON-over-HTTP(S) API used by the MCP server.
 *
 * <p>Every {@code /api/} call must carry {@code X-Api-Key} (the secret shared with the MCP server).
 * Every call except login must also carry {@code X-Session-Id} from the login response.
 */
public final class ApiServer {
    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());
    private static final int MAX_BODY_BYTES = 1 << 20;
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<Map<String, String>>() { };
    private static final TypeReference<LinkedHashMap<String, List<String>>> VARY_MAP =
            new TypeReference<LinkedHashMap<String, List<String>>>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<List<String>>() { };
    private static final TypeReference<List<Integer>> INT_LIST = new TypeReference<List<Integer>>() { };

    private final DaemonConfig config;
    private final HfmService service;
    private final ActionService actions;
    private final SessionManager sessions;
    private final byte[] apiKey;
    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(DaemonConfig config, HfmService service, ActionService actions, SessionManager sessions) {
        this.config = config;
        this.service = service;
        this.actions = actions;
        this.sessions = sessions;
        String key = config.apiKey();
        if (key == null || key.length() < 16) {
            throw new IllegalStateException("server.apiKey (or HFM_DAEMON_API_KEY) must be set to at least 16 characters");
        }
        this.apiKey = key.getBytes(StandardCharsets.UTF_8);
    }

    public void start() throws Exception {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(config.host()), config.port());
        if (config.keystorePath() != null) {
            HttpsServer https = HttpsServer.create(addr, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext()));
            server = https;
        } else {
            if (!addr.getAddress().isLoopbackAddress() && !config.allowInsecureHttp()) {
                throw new IllegalStateException("Refusing plain HTTP on non-loopback address " + config.host()
                        + ": passwords would cross the network unencrypted. Configure server.tls.keystore,"
                        + " or set server.allowInsecureHttp=true if TLS is terminated in front of the daemon.");
            }
            server = HttpServer.create(addr, 0);
        }
        executor = Executors.newFixedThreadPool(config.threads());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        LOG.info("HFM daemon " + com.hfmmcp.daemon.DaemonVersion.get() + " listening on "
                + (config.keystorePath() != null ? "https" : "http") + "://"
                + config.host() + ":" + port());
    }

    /** The bound port (useful when configured as 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private SSLContext sslContext() throws Exception {
        KeyStore ks = KeyStore.getInstance(config.keystoreType());
        char[] pwd = config.keystorePassword();
        try (InputStream in = Files.newInputStream(Paths.get(config.keystorePath()))) {
            ks.load(in, pwd);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pwd);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private void handle(HttpExchange ex) {
        try {
            Object body = route(ex);
            send(ex, 200, body);
        } catch (ApiException e) {
            sendError(ex, e.status(), e.code(), e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unhandled error on " + ex.getRequestURI().getPath(), e);
            sendError(ex, 500, "internal", "Internal daemon error; see the daemon log.");
        } finally {
            ex.close();
        }
    }

    private Object route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if ("/health".equals(path) && "GET".equals(method)) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("status", "ok");
            h.put("version", com.hfmmcp.daemon.DaemonVersion.get());
            h.put("backend", config.backend());
            return h;
        }
        if (!path.startsWith("/api/v1/")) {
            throw new ApiException(404, "not_found", "No such endpoint: " + path);
        }
        requireApiKey(ex);

        if ("/api/v1/sessions".equals(path) && "POST".equals(method)) {
            JsonNode b = readBody(ex);
            String password = text(b, "password");
            return service.login(text(b, "username"), password == null ? null : password.toCharArray(),
                    text(b, "application"));
        }

        // The daemon's own settings, so the MCP client can offer only allowed actions before logging in.
        if ("/api/v1/capabilities".equals(path) && "GET".equals(method)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("version", com.hfmmcp.daemon.DaemonVersion.get());
            c.put("actionsEnabled", config.actionsEnabled());
            c.put("actionsAllowed", config.actionsEnabled() ? config.actionsAllowed() : new java.util.HashSet<String>());
            return c;
        }

        UserSession s = sessions.get(ex.getRequestHeaders().getFirst("X-Session-Id"));
        if (s == null) {
            throw new ApiException(401, "session_expired", "No active session; log in first.");
        }
        Map<String, String> q = query(ex);
        switch (method + " " + path) {
            case "DELETE /api/v1/sessions/current":
                service.logout(s);
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("loggedOut", true);
                return ok;
            case "GET /api/v1/dimensions":
                return service.dimensions(s);
            case "GET /api/v1/members":
                return service.members(s, required(q, "dimension"), q.get("member"), q.get("relation"),
                        q.get("expression"), intParam(q, "limit"));
            case "GET /api/v1/members/search":
                return service.search(s, required(q, "dimension"), required(q, "q"), intParam(q, "limit"));
            case "POST /api/v1/cells": {
                JsonNode b = readBody(ex);
                Map<String, String> pov = map(b, "pov");
                Map<String, List<String>> vary = b.hasNonNull("vary") ? convert(b.get("vary"), VARY_MAP, "vary") : null;
                return service.cells(s, pov, vary);
            }
            case "POST /api/v1/pov/validate":
                return service.validatePov(s, text(readBody(ex), "pov"));
            case "POST /api/v1/process/status": {
                JsonNode b = readBody(ex);
                return actions.processStatus(s, map(b, "pov"), list(b, "entities", STRING_LIST), list(b, "phases", INT_LIST));
            }
            case "POST /api/v1/actions/preview": {
                JsonNode b = readBody(ex);
                JsonNode level = b.get("level");
                return actions.preview(s, text(b, "action"), level == null || level.isNull() ? null : level.asInt(),
                        map(b, "pov"), list(b, "entities", STRING_LIST), list(b, "phases", INT_LIST),
                        b.path("includeDescendants").asBoolean(false), text(b, "comment"));
            }
            case "POST /api/v1/actions/preview-extract": {
                JsonNode b = readBody(ex);
                ExtractRequest.Format format = enumValue(ExtractRequest.Format.class, text(b, "format"), ExtractRequest.Format.FLATFILE);
                String dsn = text(b, "dsn");
                if ((dsn == null || dsn.trim().isEmpty()) && format != ExtractRequest.Format.FLATFILE) {
                    dsn = config.extractDefaultDsn();
                }
                return actions.previewExtract(s, new ExtractRequest(format, text(b, "slice"), text(b, "prefix"),
                        format == ExtractRequest.Format.FLATFILE ? null : dsn,
                        b.path("includeCalculated").asBoolean(true), b.path("includeDerived").asBoolean(false),
                        b.path("includeDynamicAccounts").asBoolean(false)));
            }
            case "POST /api/v1/actions/preview-copy": {
                JsonNode b = readBody(ex);
                String view = text(b, "view");
                return actions.previewCopy(s, new CopyRequest(text(b, "source"), text(b, "target"),
                        text(b, "entitiesAndAccounts"), view == null || view.trim().isEmpty() ? "Periodic" : view.trim(),
                        enumValue(CopyRequest.Mode.class, text(b, "mode"), CopyRequest.Mode.MERGE),
                        b.path("copyRatesAndSystemData").asBoolean(true), b.path("copyDerivedData").asBoolean(false),
                        b.path("copyCellText").asBoolean(false), b.path("scale").asDouble(1.0)));
            }
            case "POST /api/v1/actions/preview-load": {
                JsonNode b = readBody(ex);
                String delimiter = text(b, "delimiter");
                return actions.previewLoad(s, new LoadRequest(actions.resolveLoadFile(text(b, "file")),
                        enumValue(LoadRequest.Mode.class, text(b, "mode"), LoadRequest.Mode.MERGE),
                        delimiter == null || delimiter.isEmpty() ? "," : delimiter,
                        b.path("accumulateWithinFile").asBoolean(false), b.path("containsOwnershipData").asBoolean(false),
                        b.path("scanOnly").asBoolean(false)));
            }
            case "POST /api/v1/actions/execute":
                return actions.execute(s, text(readBody(ex), "planId"));
            case "POST /api/v1/tasks/status":
                return actions.taskStatus(s, list(readBody(ex), "taskIds", INT_LIST));
            default:
                throw new ApiException(404, "not_found", "No such endpoint: " + method + " " + path);
        }
    }

    private void requireApiKey(HttpExchange ex) {
        String given = ex.getRequestHeaders().getFirst("X-Api-Key");
        if (given == null || !MessageDigest.isEqual(apiKey, given.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(401, "unauthorized", "Missing or wrong X-Api-Key.");
        }
    }

    private static JsonNode readBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = ex.getRequestBody()) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_BODY_BYTES) {
                    throw new ApiException(413, "too_large", "Request body too large.");
                }
            }
        }
        try {
            JsonNode node = Json.MAPPER.readTree(buf.toByteArray());
            if (node == null || !node.isObject()) {
                throw ApiException.badRequest("bad_request", "Body must be a JSON object.");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.badRequest("bad_request", "Malformed JSON: " + e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_request", "Malformed request: " + e.getMessage());
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_request", "'" + value + "' is not one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    private static Map<String, String> map(JsonNode b, String field) {
        return b.hasNonNull(field) ? convert(b.get(field), STRING_MAP, field) : null;
    }

    private static <T> List<T> list(JsonNode b, String field, TypeReference<List<T>> type) {
        return b.hasNonNull(field) ? convert(b.get(field), type, field) : null;
    }

    private static <T> T convert(JsonNode n, TypeReference<T> type, String field) {
        try {
            return Json.MAPPER.convertValue(n, type);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_request", "Field '" + field + "' has the wrong type.");
        }
    }

    private static String text(JsonNode b, String field) {
        JsonNode n = b.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Map<String, String> query(HttpExchange ex) throws UnsupportedEncodingException {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            out.put(k, v);
        }
        return out;
    }

    private static String required(Map<String, String> q, String name) {
        String v = q.get(name);
        if (v == null || v.trim().isEmpty()) {
            throw ApiException.badRequest("bad_request", "Query parameter '" + name + "' is required.");
        }
        return v;
    }

    private static int intParam(Map<String, String> q, String name) {
        String v = q.get(name);
        if (v == null || v.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("bad_request", "Query parameter '" + name + "' must be an integer.");
        }
    }

    private static void sendError(HttpExchange ex, int status, String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", err);
        try {
            send(ex, status, body);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not send error response", e);
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
