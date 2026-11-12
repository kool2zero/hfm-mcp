package com.hfmmcp.daemon.service;

import com.hfmmcp.daemon.DaemonConfig;
import com.hfmmcp.daemon.backend.DimensionInfo;
import com.hfmmcp.daemon.backend.HfmBackend;
import com.hfmmcp.daemon.backend.ReportDetail;
import com.hfmmcp.daemon.backend.ReportInfo;
import com.hfmmcp.daemon.backend.ReportQuery;
import com.hfmmcp.daemon.backend.ReportRunRequest;
import com.hfmmcp.daemon.backend.TaskProgress;
import com.hfmmcp.daemon.http.ApiException;
import com.hfmmcp.daemon.pov.Pov;
import com.hfmmcp.daemon.pov.PovResolver;
import com.hfmmcp.daemon.session.UserSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * HFM Reports: listing, reading metadata & definition code, executing/viewing rendered reports
 * (always available as read-only operations), and creating or updating report definitions
 * (preview, then confirm via {@code hfm_execute_action}, gated by {@code REPORT_SAVE} in
 * {@code actions.allowed}).
 */
public final class ReportService {
    static final String SAVE = "REPORT_SAVE";

    private static final Set<String> REPORT_TYPES = new HashSet<>(Arrays.asList(
            "JOURNAL", "INTERCOMPANY", "IC_MATCHING_TEMPLATE", "IC_TRANSACTION", "IC_MONITOR",
            "IC_MATCH_BY_ACCOUNT", "IC_MATCH_BY_TRANS_ID", "DATA_EXPLORER", "HYP"));

    private static final Set<String> FILE_TYPES = new HashSet<>(Arrays.asList(
            "RPTDEF", "RPTXML", "RPTHTML", "XML", "CUSTOM"));

    private static final Set<String> REPORT_FORMATS = new HashSet<>(Arrays.asList(
            "HTML", "HFM", "PDF", "RTF", "XLS", "XLSX"));

    private static final int MAX_ROWS = 500;
    private static final int MAX_CODE_CHARS = 250_000;

    static final class Plan {
        final String id;
        final long createdAt = System.currentTimeMillis();
        final String action;
        final ReportDetail report;
        final boolean replacesExisting;

        Plan(String id, String action, ReportDetail report, boolean replacesExisting) {
            this.id = id;
            this.action = action;
            this.report = report;
            this.replacesExisting = replacesExisting;
        }
    }

    private final HfmBackend backend;
    private final HfmService hfm;
    private final DaemonConfig config;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public ReportService(HfmBackend backend, HfmService hfm, DaemonConfig config, AuditLog audit) {
        this.backend = backend;
        this.hfm = hfm;
        this.config = config;
        this.audit = audit;
    }

    static boolean owns(Object plan) {
        return plan instanceof Plan;
    }

    // ---------------------------------------------------------------- read-only

    public Map<String, Object> list(UserSession s, List<String> reportTypes, String folder, String query,
                                    Boolean includePrivate) {
        List<String> types = checkChoices("reportType", reportTypes, REPORT_TYPES);
        boolean incPriv = includePrivate == null || includePrivate;
        ReportQuery rq = new ReportQuery(types, folder, query, incPriv);
        List<ReportInfo> found = hfm.withSession(s, bs -> backend.listReports(bs, rq));
        Map<String, Integer> byType = new TreeMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportInfo r : found) {
            byType.merge(r.reportType == null ? "UNKNOWN" : r.reportType, 1, Integer::sum);
            if (rows.size() < MAX_ROWS) {
                rows.add(summaryMap(r));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", found.size());
        if (found.size() > rows.size()) {
            out.put("returned", rows.size());
            out.put("truncated", true);
        }
        out.put("byType", byType);
        out.put("reports", rows);
        return out;
    }

    public Map<String, Object> get(UserSession s, String name, String reportType, String folder) {
        if (blank(name)) {
            throw ApiException.badRequest("bad_request", "name is required");
        }
        String rType = checkOptionalChoice("reportType", reportType, REPORT_TYPES);
        ReportDetail d = hfm.withSession(s, bs -> backend.getReport(bs, folder, name.trim(), rType));
        Map<String, Object> out = summaryMap(d.info);
        String code = d.code == null ? "" : d.code;
        out.put("encoding", d.encoding == null ? "UTF-8" : d.encoding);
        out.put("lineCount", countLines(code));
        out.put("sections", parseSections(code));
        out.put("code", code);
        return out;
    }

    public Map<String, Object> view(UserSession s, String name, String reportType, String folder, String format,
                                    Map<String, String> pov, String slice, String entity, String partner,
                                    String stylesheet) {
        if (blank(name)) {
            throw ApiException.badRequest("bad_request", "name is required");
        }
        String rType = checkOptionalChoice("reportType", reportType, REPORT_TYPES);
        String fmt = blank(format) ? "HTML" : checkChoice("format", format, REPORT_FORMATS);
        ReportDetail detail = hfm.withSession(s, bs -> backend.getReport(bs, folder, name.trim(), rType));

        String povSlice = buildPovSlice(s, pov, slice);
        if (!blank(entity)) {
            hfm.expand(s, hfm.resolver(s).resolve("Entity"), entity.trim());
        }
        ReportRunRequest req = new ReportRunRequest(fmt, povSlice, blank(entity) ? null : entity.trim(),
                blank(partner) ? null : partner.trim(), blank(stylesheet) ? null : stylesheet.trim());
        int taskId = hfm.withSession(s, bs -> backend.startReport(bs, detail, req));
        List<Integer> ids = Collections.singletonList(taskId);
        List<TaskProgress> progress = waitFor(s, ids);
        boolean finished = allFinished(progress);
        TaskProgress tp = progress.isEmpty()
                ? new TaskProgress(taskId, TaskProgress.Status.UNKNOWN, 0, "")
                : progress.get(0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", detail.info.name);
        out.put("reportType", detail.info.reportType);
        out.put("folder", detail.info.folder);
        out.put("format", fmt);
        if (povSlice != null) {
            out.put("povSlice", povSlice);
        }
        if (!blank(entity)) {
            out.put("entity", entity.trim());
        }
        if (!blank(partner)) {
            out.put("partner", partner.trim());
        }
        out.put("taskId", taskId);
        out.put("finished", finished);
        out.put("status", tp.status().name());

        if (finished && tp.status() == TaskProgress.Status.COMPLETED) {
            String safeName = detail.info.name.replaceAll("[^A-Za-z0-9._-]", "_");
            String fileName = safeName + "_" + taskId + "." + extensionFor(fmt);
            Path dir = Paths.get(config.extractExportDir()).resolve("reports");
            Path written = hfm.withSession(s, bs -> backend.fetchReportFile(bs, taskId, dir, fileName));
            Map<String, Object> fileInfo = describeFile(written, isTextFormat(fmt));
            out.put("file", fileInfo);
            Object content = fileInfo.remove("_content");
            if (content != null) {
                out.put("content", content);
            }
        } else if (!finished) {
            out.put("next", "Report task is still running; check later with hfm_get_task_status and taskId " + taskId + ".");
        }
        return out;
    }

    // ---------------------------------------------------------------- preview & execute (writes)

    public Map<String, Object> previewSave(UserSession s, String name, String reportType, String folder,
                                           String code, Map<String, Map<String, String>> sections,
                                           String description, String securityClass, Boolean isPrivate,
                                           String fileType) {
        requireAllowed(SAVE);
        if (blank(name)) {
            throw ApiException.badRequest("bad_request", "name is required");
        }
        String trimmedName = name.trim();
        if (trimmedName.contains("/") || trimmedName.contains("\\") || trimmedName.contains("..")) {
            throw ApiException.badRequest("bad_request", "name must not contain path separators or '..'");
        }
        String rType = checkOptionalChoice("reportType", reportType, REPORT_TYPES);
        String fType = checkOptionalChoice("fileType", fileType, FILE_TYPES);

        ReportDetail existing = findExisting(s, folder, trimmedName, rType);
        if (existing == null && rType == null) {
            throw ApiException.badRequest("bad_request",
                    "reportType is required when creating a new report (one of "
                            + String.join(", ", REPORT_TYPES) + ")");
        }
        String effectiveType = rType != null ? rType : existing.info.reportType;
        String effectiveFileType = fType != null ? fType : (existing != null ? existing.info.fileType : "RPTDEF");
        String effectiveFolder = !blank(folder)
                ? normalizeFolder(folder)
                : (existing != null ? existing.info.folder : "Root");
        String effectiveDesc = description != null
                ? description
                : (existing != null && existing.info.description != null ? existing.info.description : "");
        String effectiveSec = !blank(securityClass)
                ? securityClass.trim()
                : (existing != null && !blank(existing.info.securityClass) ? existing.info.securityClass : "[Default]");
        boolean effectivePriv = isPrivate != null
                ? isPrivate
                : (existing != null && existing.info.isPrivate);
        String effectiveEncoding = existing != null && !blank(existing.encoding) ? existing.encoding : "UTF-8";

        String baseCode;
        if (code != null) {
            baseCode = code;
        } else if (existing != null) {
            baseCode = existing.code == null ? "" : existing.code;
        } else if (sections != null && !sections.isEmpty()) {
            baseCode = "";
        } else {
            throw ApiException.badRequest("bad_request",
                    "code (or sections) is required when creating or updating a report");
        }

        String finalCode = sections != null && !sections.isEmpty()
                ? applySections(baseCode, sections)
                : baseCode;
        if (finalCode.length() > MAX_CODE_CHARS) {
            throw ApiException.badRequest("bad_request",
                    "Report code exceeds limit of " + MAX_CODE_CHARS + " characters");
        }

        Map<String, Map<String, String>> parsedSections = parseSections(finalCode);
        List<String> warnings = validateSectionPov(s, parsedSections);

        ReportInfo info = new ReportInfo(trimmedName, effectiveType, effectiveFileType, effectiveFolder,
                effectiveDesc, effectiveSec, effectivePriv, s.userName(), System.currentTimeMillis());
        ReportDetail target = new ReportDetail(info, finalCode, effectiveEncoding);

        Plan plan = newPlan(s, SAVE, target, existing != null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", plan.id);
        out.put("expiresInMinutes", config.actionPlanTtlMinutes());
        out.put("action", SAVE);
        out.putAll(summaryMap(info));
        out.put("replacesExisting", existing != null);
        out.put("encoding", effectiveEncoding);
        out.put("lineCount", countLines(finalCode));
        out.put("sections", parsedSections);
        out.put("code", finalCode);
        if (existing != null && existing.code != null) {
            out.put("previousCode", existing.code);
        }
        if (!warnings.isEmpty()) {
            out.put("warnings", warnings);
        }
        out.put("effect", (existing != null
                ? "Replaces existing " + effectiveType + " report '" + trimmedName + "' in " + effectiveFolder
                : "Creates new " + effectiveType + " report '" + trimmedName + "' in " + effectiveFolder)
                + ". Nothing is saved until execute is called.");
        out.put("next", "Show the report metadata and code changes to the user. Only if they confirm, call execute with this planId.");
        return out;
    }

    Map<String, Object> execute(UserSession s, Object raw) {
        Plan plan = (Plan) raw;
        if (expired(plan)) {
            throw new ApiException(404, "plan_not_found",
                    "No such plan (plans are single-use and expire after " + config.actionPlanTtlMinutes()
                            + " minutes); preview the report save again.");
        }
        requireAllowed(plan.action);
        ReportInfo ri = plan.report.info;
        String target = ri.folder + "/" + ri.name + " (" + ri.reportType + ")";
        try {
            hfm.withSession(s, bs -> {
                backend.saveReport(bs, plan.report, true);
                return null;
            });
        } catch (ApiException e) {
            audit.record(s.userName(), s.application(), plan.action, target, null, "failed", e.getMessage());
            throw e;
        }
        audit.record(s.userName(), s.application(), plan.action, target, null, "completed",
                (plan.replacesExisting ? "updated" : "created") + " (" + countLines(plan.report.code) + " lines)");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", plan.action);
        out.putAll(summaryMap(ri));
        out.put("replacesExisting", plan.replacesExisting);
        out.put("lineCount", countLines(plan.report.code));
        out.put("completed", true);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private ReportDetail findExisting(UserSession s, String folder, String name, String reportType) {
        try {
            return hfm.withSession(s, bs -> backend.getReport(bs, folder, name, reportType));
        } catch (ApiException e) {
            if ("session_expired".equals(e.code())) {
                throw e;
            }
            return null;
        }
    }

    private String buildPovSlice(UserSession s, Map<String, String> pov, String slice) {
        if (!blank(slice)) {
            String normalized = slice.trim().replaceAll("(^|\\.)([A-Za-z0-9]+)\\{", "$1$2#{");
            Map<String, Object> check = hfm.validatePov(s, normalized);
            if (!Boolean.TRUE.equals(check.get("valid"))) {
                throw ApiException.badRequest("bad_pov", "Invalid POV override '" + slice + "': " + check);
            }
            return normalized;
        }
        if (pov != null && !pov.isEmpty()) {
            PovResolver r = hfm.resolver(s);
            LinkedHashMap<String, String> byPrefix = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : pov.entrySet()) {
                if (!blank(e.getValue())) {
                    DimensionInfo d = r.resolve(e.getKey());
                    byPrefix.put(d.prefix(), e.getValue().trim());
                }
            }
            if (!byPrefix.isEmpty()) {
                String formatted = Pov.format(byPrefix);
                Map<String, Object> check = hfm.validatePov(s, formatted);
                if (!Boolean.TRUE.equals(check.get("valid"))) {
                    throw ApiException.badRequest("bad_pov", "Invalid POV override: " + check);
                }
                return formatted;
            }
        }
        return null;
    }

    private List<String> validateSectionPov(UserSession s, Map<String, Map<String, String>> sections) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> povSection = null;
        for (Map.Entry<String, Map<String, String>> e : sections.entrySet()) {
            if ("PointOfView".equalsIgnoreCase(e.getKey()) || "POV".equalsIgnoreCase(e.getKey())) {
                povSection = e.getValue();
                break;
            }
        }
        if (povSection == null || povSection.isEmpty()) {
            return warnings;
        }
        PovResolver r = hfm.resolver(s);
        LinkedHashMap<String, String> toCheck = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : povSection.entrySet()) {
            if (blank(e.getValue())) {
                continue;
            }
            try {
                DimensionInfo d = r.resolve(e.getKey());
                toCheck.put(d.prefix(), e.getValue().trim());
            } catch (ApiException ignored) {
            }
        }
        if (!toCheck.isEmpty()) {
            String slice = Pov.format(toCheck);
            Map<String, Object> check = hfm.validatePov(s, slice);
            if (!Boolean.TRUE.equals(check.get("valid"))) {
                throw ApiException.badRequest("bad_pov",
                        "Report [PointOfView] contains invalid HFM member(s): " + check.get("dimensions"));
            }
        }
        return warnings;
    }

    /** Parses an INI/section-formatted HFM report script into section -> key -> value. */
    static Map<String, Map<String, String>> parseSections(String code) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        if (blank(code)) {
            return sections;
        }
        String current = "General";
        for (String raw : code.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("'") || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]") && line.length() > 2) {
                current = line.substring(1, line.length() - 1).trim();
                sections.computeIfAbsent(current, k -> new LinkedHashMap<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                sections.computeIfAbsent(current, s -> new LinkedHashMap<>()).put(k, v);
            }
        }
        return sections;
    }

    /** Updates or adds section key=value pairs in {@code code} while preserving existing formatting and order. */
    static String applySections(String code, Map<String, Map<String, String>> updates) {
        Map<String, Map<String, String>> remaining = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : updates.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                remaining.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
            }
        }
        if (blank(code)) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Map<String, String>> sec : remaining.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("[").append(sec.getKey()).append("]\n");
                for (Map.Entry<String, String> kv : sec.getValue().entrySet()) {
                    sb.append(kv.getKey()).append("=").append(kv.getValue() == null ? "" : kv.getValue()).append("\n");
                }
            }
            return sb.toString();
        }
        String[] lines = code.split("\\r?\\n", -1);
        List<String> out = new ArrayList<>();
        String currentSection = "General";
        Map<String, String> currentUpdates = findSectionIgnoreCase(remaining, currentSection);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
                flushRemainingKeys(out, currentUpdates);
                currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
                currentUpdates = findSectionIgnoreCase(remaining, currentSection);
                out.add(line);
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0 && !trimmed.startsWith("'") && !trimmed.startsWith("#") && currentUpdates != null) {
                String key = trimmed.substring(0, eq).trim();
                String matchKey = findKeyIgnoreCase(currentUpdates, key);
                if (matchKey != null) {
                    String newVal = currentUpdates.remove(matchKey);
                    out.add(key + "=" + (newVal == null ? "" : newVal));
                    continue;
                }
            }
            out.add(line);
        }
        flushRemainingKeys(out, currentUpdates);
        for (Map.Entry<String, Map<String, String>> sec : remaining.entrySet()) {
            if (!sec.getValue().isEmpty()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).trim().isEmpty()) {
                    out.add("");
                }
                out.add("[" + sec.getKey() + "]");
                for (Map.Entry<String, String> kv : sec.getValue().entrySet()) {
                    out.add(kv.getKey() + "=" + (kv.getValue() == null ? "" : kv.getValue()));
                }
            }
        }
        return String.join("\n", out);
    }

    private static void flushRemainingKeys(List<String> out, Map<String, String> currentUpdates) {
        if (currentUpdates == null || currentUpdates.isEmpty()) {
            return;
        }
        int insertAt = out.size();
        while (insertAt > 0 && out.get(insertAt - 1).trim().isEmpty()) {
            insertAt--;
        }
        for (Map.Entry<String, String> kv : currentUpdates.entrySet()) {
            out.add(insertAt++, kv.getKey() + "=" + (kv.getValue() == null ? "" : kv.getValue()));
        }
        currentUpdates.clear();
    }

    private static Map<String, String> findSectionIgnoreCase(Map<String, Map<String, String>> map, String name) {
        for (Map.Entry<String, Map<String, String>> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String findKeyIgnoreCase(Map<String, String> map, String key) {
        for (String k : map.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                return k;
            }
        }
        return null;
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        if (text.endsWith("\n")) {
            lines--;
        }
        return lines;
    }

    private static Map<String, Object> summaryMap(ReportInfo r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name);
        m.put("reportType", r.reportType);
        m.put("fileType", r.fileType);
        m.put("folder", r.folder);
        if (r.description != null && !r.description.isEmpty()) {
            m.put("description", r.description);
        }
        if (r.securityClass != null && !r.securityClass.isEmpty()) {
            m.put("securityClass", r.securityClass);
        }
        m.put("private", r.isPrivate);
        if (r.owner != null && !r.owner.isEmpty()) {
            m.put("owner", r.owner);
        }
        if (r.lastModified > 0) {
            m.put("lastModified", time(r.lastModified));
        }
        return m;
    }

    private Map<String, Object> describeFile(Path p, boolean includeTextContent) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("path", p.toAbsolutePath().toString());
        List<String> preview = new ArrayList<>();
        long lines = 0;
        try {
            f.put("bytes", Files.size(p));
            if (includeTextContent) {
                StringBuilder full = new StringBuilder();
                int max = config.maxLogChars();
                try (BufferedReader in = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (lines++ < config.extractPreviewLines()) {
                            preview.add(line);
                        }
                        if (full.length() <= max) {
                            if (full.length() > 0) {
                                full.append('\n');
                            }
                            full.append(line);
                        }
                    }
                }
                f.put("lines", lines);
                f.put("preview", preview);
                String content = full.length() > max ? full.substring(0, max) : full.toString();
                f.put("_content", content);
                if (full.length() > max) {
                    f.put("contentTruncated", true);
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            f.put("error", "Cannot read report file: " + e.getMessage());
        }
        return f;
    }

    private static boolean isTextFormat(String format) {
        return "HTML".equalsIgnoreCase(format) || "HFM".equalsIgnoreCase(format)
                || "RTF".equalsIgnoreCase(format) || "XLS".equalsIgnoreCase(format);
    }

    private static String extensionFor(String format) {
        switch (format.toUpperCase(Locale.ROOT)) {
            case "PDF":
                return "pdf";
            case "RTF":
                return "rtf";
            case "XLS":
                return "xls";
            case "XLSX":
                return "xlsx";
            default:
                return "html";
        }
    }

    private List<TaskProgress> waitFor(UserSession s, List<Integer> taskIds) {
        List<TaskProgress> progress = Collections.emptyList();
        long deadline = System.currentTimeMillis() + config.actionWaitSeconds() * 1000L;
        while (!taskIds.isEmpty()) {
            progress = hfm.withSession(s, bs -> backend.getTaskProgress(bs, taskIds));
            if (allFinished(progress) || System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return progress;
    }

    private static boolean allFinished(List<TaskProgress> progress) {
        if (progress.isEmpty()) {
            return false;
        }
        for (TaskProgress t : progress) {
            if (!t.finished()) {
                return false;
            }
        }
        return true;
    }

    private Plan newPlan(UserSession s, String action, ReportDetail report, boolean replacesExisting) {
        for (Iterator<Object> it = s.actionPlans().values().iterator(); it.hasNext(); ) {
            Object o = it.next();
            if (o instanceof Plan && expired((Plan) o)) {
                it.remove();
            }
        }
        byte[] b = new byte[16];
        random.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        Plan plan = new Plan(id, action, report, replacesExisting);
        s.actionPlans().put(id, plan);
        return plan;
    }

    private boolean expired(Plan p) {
        return System.currentTimeMillis() - p.createdAt > config.actionPlanTtlMinutes() * 60_000L;
    }

    private void requireAllowed(String action) {
        if (!config.actionsEnabled()) {
            throw new ApiException(403, "actions_disabled",
                    "Actions are disabled on this daemon (actions.enabled=false).");
        }
        if (!config.actionsAllowed().contains(action)) {
            throw new ApiException(403, "action_not_allowed",
                    action + " is not enabled on this daemon (allowed: " + config.actionsAllowed() + ")");
        }
    }

    private static List<String> checkChoices(String field, List<String> values, Set<String> allowed) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (!blank(v)) {
                out.add(checkChoice(field, v, allowed));
            }
        }
        return out;
    }

    private static String checkOptionalChoice(String field, String value, Set<String> allowed) {
        return blank(value) ? null : checkChoice(field, value, allowed);
    }

    private static String checkChoice(String field, String value, Set<String> allowed) {
        String u = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) {
            throw ApiException.badRequest("bad_request",
                    field + " must be one of " + String.join(", ", new java.util.TreeSet<>(allowed)));
        }
        return u;
    }

    private static String normalizeFolder(String folder) {
        if (blank(folder) || "/".equals(folder.trim())) {
            return "Root";
        }
        String f = folder.trim().replace('\\', '/');
        while (f.startsWith("/")) {
            f = f.substring(1);
        }
        while (f.endsWith("/")) {
            f = f.substring(0, f.length() - 1);
        }
        return f.isEmpty() || "root".equalsIgnoreCase(f) ? "Root" : f;
    }

    private static boolean blank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private static String time(long t) {
        if (t <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(t < 100_000_000_000L ? t * 1000 : t).toString();
    }
}
