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
            "IC_MATCH_BY_ACCOUNT", "IC_MATCH_BY_TRANS_ID", "DATA_EXPLORER", "HYP",
            "FR_REPORT", "FR_BOOK", "FR_SNAPSHOT_BOOK", "FR_SNAPSHOT_REPORT",
            "FR_BATCH", "FR_GRID", "FR_ROW_COLUMN", "FR_TEXT", "FOLDER"));

    private static final Set<String> FILE_TYPES = new HashSet<>(Arrays.asList(
            "RPTDEF", "RPTXML", "RPTHTML", "XML", "CUSTOM",
            "DES", "KBK", "KBT", "RPT", "BCH", "ROG", "ROS", "ROT", "FOLDER"));

    private static final Set<String> REPORT_FORMATS = new HashSet<>(Arrays.asList(
            "HTML", "HFM", "PDF", "RTF", "XLS", "XLSX"));

    private static final int MAX_ROWS = 500;
    private static final int MAX_CODE_CHARS = 5_000_000;

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
        List<ReportInfo> found = new ArrayList<>(hfm.withSession(s, bs -> backend.listReports(bs, rq)));
        Map<String, Integer> byType = new TreeMap<>();
        Set<String> folders = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportInfo r : found) {
            byType.merge(r.reportType == null ? "UNKNOWN" : r.reportType, 1, Integer::sum);
            if (!blank(r.folder)) {
                folders.add(r.folder);
            }
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
        out.put("folders", new ArrayList<>(folders));
        out.put("reports", rows);
        return out;
    }

    public Map<String, Object> get(UserSession s, String name, String reportType, String folder) {
        if (blank(name)) {
            throw ApiException.badRequest("bad_request", "name is required");
        }
        String[] split = splitFolderAndName(folder, name.trim());
        String effectiveFolder = split[0];
        String effectiveName = split[1];
        String rType = checkOptionalChoice("reportType", reportType, REPORT_TYPES);
        ReportDetail d = hfm.withSession(s, bs -> backend.getReport(bs, effectiveFolder, effectiveName, rType));
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
        String[] split = splitFolderAndName(folder, name.trim());
        String effectiveFolder = split[0];
        String effectiveName = split[1];
        String rType = checkOptionalChoice("reportType", reportType, REPORT_TYPES);
        String fmt = blank(format) ? "HTML" : checkChoice("format", format, REPORT_FORMATS);
        ReportDetail detail = hfm.withSession(s, bs -> backend.getReport(bs, effectiveFolder, effectiveName, rType));

        String povSlice = buildPovSlice(s, pov, slice);
        if (!blank(entity)) {
            hfm.expand(s, hfm.resolver(s).resolve("Entity"), entity.trim());
        }
        if (isFrObject(detail)) {
            return viewFrObject(detail, fmt, povSlice, entity, partner);
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
        String rawName = name.trim();
        if (rawName.contains("..") || (folder != null && folder.contains(".."))) {
            throw ApiException.badRequest("bad_request", "name and folder must not contain '..'");
        }
        if (rawName.contains("/") || rawName.contains("\\")) {
            if (!blank(folder) && !"Root".equalsIgnoreCase(normalizeFolder(folder))) {
                throw ApiException.badRequest("bad_request", "name must not contain path separators when folder is specified");
            }
        }
        String[] split = splitFolderAndName(folder, rawName);
        String splitFolder = split[0];
        String trimmedName = split[1];
        if (trimmedName.contains("/") || trimmedName.contains("\\") || trimmedName.contains("..")) {
            throw ApiException.badRequest("bad_request", "name must not contain path separators or '..'");
        }
        String rType = checkOptionalChoice("reportType", reportType, REPORT_TYPES);
        String fType = checkOptionalChoice("fileType", fileType, FILE_TYPES);

        ReportDetail existing = findExisting(s, splitFolder, trimmedName, rType);
        if (existing == null && rType == null) {
            throw ApiException.badRequest("bad_request",
                    "reportType is required when creating a new report (one of "
                            + String.join(", ", REPORT_TYPES) + ")");
        }
        String effectiveType = rType != null ? rType : existing.info.reportType;
        String effectiveFileType = fType != null ? fType : (existing != null ? existing.info.fileType : defaultFileTypeFor(effectiveType));
        String effectiveFolder = existing != null && (blank(splitFolder) || "Root".equalsIgnoreCase(normalizeFolder(splitFolder)))
                ? existing.info.folder
                : (!blank(splitFolder) ? normalizeFolder(splitFolder) : "Root");
        String effectiveDesc = description != null
                ? description
                : (existing != null && existing.info.description != null ? existing.info.description : "");
        String effectiveSec = !blank(securityClass)
                ? securityClass.trim()
                : (existing != null && !blank(existing.info.securityClass) ? existing.info.securityClass : "[Default]");
        boolean effectivePriv = isPrivate != null
                ? isPrivate
                : (existing != null && existing.info.isPrivate);
        String effectiveEncoding = existing != null && !blank(existing.encoding)
                ? existing.encoding
                : defaultEncodingFor(effectiveType);

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
        List<String> warnings = validateSectionPov(s, parsedSections, isXmlReportCode(finalCode));

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

    private List<String> validateSectionPov(UserSession s, Map<String, Map<String, String>> sections, boolean isXml) {
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
            String val = e.getValue().trim();
            // Skip FR prompt/function expressions like "$Prompt for Entity$", "Same As", "Descendants(...)"
            if (isXml && (val.contains("$") || val.contains("(") || val.contains(" ") || val.contains(","))) {
                continue;
            }
            try {
                DimensionInfo d = r.resolve(e.getKey());
                toCheck.put(d.prefix(), val);
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

    private static boolean isXmlReportCode(String code) {
        return code != null && code.trim().startsWith("<");
    }

    private static boolean isFrObject(ReportDetail detail) {
        if (detail == null) {
            return false;
        }
        String rt = detail.info != null && detail.info.reportType != null
                ? detail.info.reportType.toUpperCase(Locale.ROOT)
                : "";
        return rt.startsWith("FR_") || isXmlReportCode(detail.code);
    }

    /**
     * Parses an INI/section-formatted HFM report script or a Financial Reporting XML definition
     * ({@code <DESIGN>} / {@code <BOOK>}) into section -> key -> value.
     */
    static Map<String, Map<String, String>> parseSections(String code) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        if (blank(code)) {
            return sections;
        }
        if (isXmlReportCode(code)) {
            Map<String, Map<String, String>> xmlSections = parseFrXmlSections(code);
            if (!xmlSections.isEmpty()) {
                return xmlSections;
            }
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

    private static Map<String, Map<String, String>> parseFrXmlSections(String xml) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(
                    new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            org.w3c.dom.Element root = doc.getDocumentElement();
            if (root == null) {
                return sections;
            }
            String rootTag = root.getTagName().toUpperCase(Locale.ROOT);
            Map<String, String> general = new LinkedHashMap<>();
            sections.put("General", general);

            if ("BOOK".equals(rootTag)) {
                putAttrIfNotEmpty(general, "Name", root, "NAME");
                putAttrIfNotEmpty(general, "ReportDescription", root, "REPORT_DESCRIPTION");
                putAttrIfNotEmpty(general, "Version", root, "VERSION");

                Map<String, String> pov = new LinkedHashMap<>();
                org.w3c.dom.NodeList bookPovs = root.getElementsByTagName("BOOK_POV");
                for (int i = 0; i < bookPovs.getLength(); i++) {
                    org.w3c.dom.Element bp = (org.w3c.dom.Element) bookPovs.item(i);
                    org.w3c.dom.NodeList dsList = bp.getElementsByTagName("DATASOURCE");
                    if (dsList.getLength() > 0) {
                        org.w3c.dom.Element ds = (org.w3c.dom.Element) dsList.item(0);
                        putAttrIfNotEmpty(general, "DatasourceName", ds, "DATASOURCE_NAME");
                        putAttrIfNotEmpty(general, "DatasourceType", ds, "DATASOURCE_TYPE");
                    }
                    org.w3c.dom.NodeList dims = bp.getElementsByTagName("DIMENSION");
                    for (int d = 0; d < dims.getLength(); d++) {
                        org.w3c.dom.Element dim = (org.w3c.dom.Element) dims.item(d);
                        String dimName = dim.getAttribute("NAME");
                        String expr = firstMetadataExpression(dim);
                        if (!blank(dimName) && !blank(expr)) {
                            pov.put(dimName, expr);
                        }
                    }
                }
                if (!pov.isEmpty()) {
                    sections.put("PointOfView", pov);
                }

                Map<String, String> chapters = new LinkedHashMap<>();
                org.w3c.dom.NodeList chapList = root.getElementsByTagName("CHAPTER");
                general.put("ChapterCount", Integer.toString(chapList.getLength()));
                for (int c = 0; c < chapList.getLength(); c++) {
                    org.w3c.dom.Element ch = (org.w3c.dom.Element) chapList.item(c);
                    String repName = ch.getAttribute("REPORTNAME");
                    if (blank(repName)) {
                        repName = ch.getAttribute("NAME");
                    }
                    List<String> memOverrides = new ArrayList<>();
                    org.w3c.dom.NodeList dims = ch.getElementsByTagName("DIMENSION");
                    for (int d = 0; d < dims.getLength(); d++) {
                        org.w3c.dom.Element dim = (org.w3c.dom.Element) dims.item(d);
                        String dimName = dim.getAttribute("NAME");
                        String expr = firstMetadataExpression(dim);
                        if (!blank(dimName) && !blank(expr)) {
                            memOverrides.add(dimName + "=" + expr);
                        }
                    }
                    String val = repName + (memOverrides.isEmpty() ? "" : " [" + String.join(", ", memOverrides) + "]");
                    chapters.put("Chapter " + (c + 1), val);
                }
                if (!chapters.isEmpty()) {
                    sections.put("Chapters", chapters);
                }
                return sections;
            }

            // Default: <DESIGN> or <GRIDOBJECT> / <ROWANDCOLUMNTEMPLATE> / <TEXTOBJECT>
            putAttrIfNotEmpty(general, "Name", root, "DESIGNNAME");
            putAttrIfNotEmpty(general, "ReportDescription", root, "REPORT_DESCRIPTION");
            putAttrIfNotEmpty(general, "Version", root, "VERSION");

            // Extract Header / Footer text views
            Map<String, String> headerText = new LinkedHashMap<>();
            org.w3c.dom.NodeList textViews = root.getElementsByTagName("TEXTVIEW");
            for (int i = 0; i < textViews.getLength(); i++) {
                org.w3c.dom.Element tv = (org.w3c.dom.Element) textViews.item(i);
                String txt = tv.getAttribute("DESIGN_NON_RTF_TEXT");
                if (!blank(txt)) {
                    headerText.put("Text " + (i + 1), txt.replaceAll("\\r?\\n", " | ").trim());
                }
            }
            if (!headerText.isEmpty()) {
                sections.put("Header", headerText);
            }

            // Extract POV
            Map<String, String> pov = new LinkedHashMap<>();
            org.w3c.dom.NodeList povNodes = root.getElementsByTagName("POV");
            for (int i = 0; i < povNodes.getLength(); i++) {
                org.w3c.dom.Element povEl = (org.w3c.dom.Element) povNodes.item(i);
                org.w3c.dom.NodeList dims = povEl.getElementsByTagName("DIMENSION");
                for (int d = 0; d < dims.getLength(); d++) {
                    org.w3c.dom.Element dim = (org.w3c.dom.Element) dims.item(d);
                    String dimName = dim.getAttribute("NAME");
                    String expr = firstMetadataExpression(dim);
                    if (!blank(dimName) && !blank(expr) && !pov.containsKey(dimName)) {
                        pov.put(dimName, expr);
                    }
                }
            }
            if (!pov.isEmpty()) {
                sections.put("PointOfView", pov);
            }

            // Extract Grids (Row & Column axes and formulas)
            org.w3c.dom.NodeList grids = root.getElementsByTagName("GRIDOBJECT");
            if (grids.getLength() == 0 && "GRIDOBJECT".equals(rootTag)) {
                grids = doc.getElementsByTagName("GRIDOBJECT");
            }
            general.put("GridCount", Integer.toString(grids.getLength()));
            for (int g = 0; g < grids.getLength(); g++) {
                org.w3c.dom.Element grid = (org.w3c.dom.Element) grids.item(g);
                String gridPrefix = grids.getLength() > 1 ? "Grid" + (g + 1) + "_" : "";
                putAttrIfNotEmpty(general, gridPrefix + "AliasTable", grid, "ALIASTABLE");
                putAttrIfNotEmpty(general, gridPrefix + "RowCount", grid, "ROW_COUNT");
                putAttrIfNotEmpty(general, gridPrefix + "ColumnCount", grid, "COLUMN_COUNT");
                String dsDetails = grid.getAttribute("DATASOURCE_DETAILS");
                if (!blank(dsDetails)) {
                    int c1 = dsDetails.indexOf(':');
                    if (c1 > 0) {
                        String after1 = dsDetails.substring(c1 + 1);
                        int c2 = after1.indexOf(':');
                        if (c2 > 0) {
                            general.put(gridPrefix + "DatasourceName", after1.substring(0, c2));
                        }
                    }
                }
                extractGridAxisSections(grid, gridPrefix, sections);
            }
        } catch (Exception ignored) {
        }
        return sections;
    }

    private static void extractGridAxisSections(org.w3c.dom.Element grid, String gridPrefix,
                                                Map<String, Map<String, String>> sections) {
        Map<Integer, String> rowFormulas = new LinkedHashMap<>();
        Map<Integer, String> colFormulas = new LinkedHashMap<>();
        Map<Integer, String> rowCustomHeadings = new LinkedHashMap<>();
        Map<Integer, String> colCustomHeadings = new LinkedHashMap<>();

        org.w3c.dom.NodeList cells = grid.getElementsByTagName("GRIDCELL");
        for (int i = 0; i < cells.getLength(); i++) {
            org.w3c.dom.Element cell = (org.w3c.dom.Element) cells.item(i);
            int rowIdx = parseIntSafe(cell.getAttribute("ROW"), -1);
            int colIdx = parseIntSafe(cell.getAttribute("COLUMN"), -1);
            String cellType = cell.getAttribute("TYPE");
            org.w3c.dom.NodeList derived = cell.getElementsByTagName("DERIVEDEXPRESSION");
            String formula = derived.getLength() > 0
                    ? ((org.w3c.dom.Element) derived.item(0)).getAttribute("VALUE")
                    : "";
            String heading = extractCustomHeading(cell);
            if ("row".equalsIgnoreCase(cellType) && rowIdx >= 0) {
                if (!blank(formula)) {
                    rowFormulas.put(rowIdx, formula);
                }
                if (!blank(heading)) {
                    rowCustomHeadings.put(rowIdx, heading);
                }
            } else if ("column".equalsIgnoreCase(cellType) && colIdx >= 0) {
                if (!blank(formula)) {
                    colFormulas.put(colIdx, formula);
                }
                if (!blank(heading)) {
                    colCustomHeadings.put(colIdx, heading);
                }
            }
        }

        org.w3c.dom.NodeList axes = grid.getElementsByTagName("GRIDAXIS");
        for (int a = 0; a < axes.getLength(); a++) {
            org.w3c.dom.Element axis = (org.w3c.dom.Element) axes.item(a);
            String axisName = axis.getAttribute("AXISNAME");
            boolean isRow = "Row".equalsIgnoreCase(axisName);
            boolean isCol = "Column".equalsIgnoreCase(axisName);
            if (!isRow && !isCol) {
                continue;
            }
            List<String> dimNames = new ArrayList<>();
            org.w3c.dom.NodeList layers = axis.getElementsByTagName("GRIDLAYER");
            for (int l = 0; l < layers.getLength(); l++) {
                String dn = ((org.w3c.dom.Element) layers.item(l)).getAttribute("DIMENSIONNAME");
                if (!blank(dn)) {
                    dimNames.add(dn);
                }
            }
            Map<Integer, List<String>> axisSpecs = new TreeMap<>();
            org.w3c.dom.NodeList segs = axis.getElementsByTagName("GRIDSEGMENT");
            for (int s = 0; s < segs.getLength(); s++) {
                org.w3c.dom.Element seg = (org.w3c.dom.Element) segs.item(s);
                int idx = parseIntSafe(seg.getAttribute("INDEX"), s);
                org.w3c.dom.NodeList segLayers = seg.getElementsByTagName("SEGMENTLAYER");
                List<String> parts = new ArrayList<>();
                for (int sl = 0; sl < segLayers.getLength(); sl++) {
                    org.w3c.dom.Element segLayer = (org.w3c.dom.Element) segLayers.item(sl);
                    int layerIdx = parseIntSafe(segLayer.getAttribute("LAYERINDEX"), sl);
                    String dim = layerIdx >= 0 && layerIdx < dimNames.size() ? dimNames.get(layerIdx) : ("Layer" + layerIdx);
                    List<String> exprs = allMetadataExpressions(segLayer);
                    if (!exprs.isEmpty()) {
                        parts.add(dim + "=" + String.join(", ", exprs));
                    }
                }
                axisSpecs.put(idx, parts);
            }
            int maxIdx = isRow
                    ? parseIntSafe(grid.getAttribute("ROW_COUNT"), axisSpecs.size())
                    : parseIntSafe(grid.getAttribute("COLUMN_COUNT"), axisSpecs.size());
            for (int idx : (isRow ? rowFormulas.keySet() : colFormulas.keySet())) {
                if (idx + 1 > maxIdx) {
                    maxIdx = idx + 1;
                }
            }
            Map<String, String> axisMap = new LinkedHashMap<>();
            for (int idx = 0; idx < maxIdx; idx++) {
                List<String> parts = axisSpecs.getOrDefault(idx, Collections.emptyList());
                String formula = isRow ? rowFormulas.get(idx) : colFormulas.get(idx);
                String heading = isRow ? rowCustomHeadings.get(idx) : colCustomHeadings.get(idx);
                StringBuilder summary = new StringBuilder();
                if (!blank(heading)) {
                    summary.append("\"").append(heading).append("\" ");
                }
                if (!blank(formula)) {
                    summary.append("Formula(").append(formula).append(")");
                }
                if (!parts.isEmpty()) {
                    if (summary.length() > 0) {
                        summary.append(" | ");
                    }
                    summary.append(String.join("; ", parts));
                }
                if (summary.length() == 0) {
                    summary.append("(blank/separator)");
                }
                String key = isRow ? ("Row " + (idx + 1)) : ("Col " + columnLabel(idx));
                axisMap.put(key, summary.toString());
            }
            if (!axisMap.isEmpty()) {
                String baseKey = isRow ? "Rows" : "Columns";
                if (gridPrefix.isEmpty() || !sections.containsKey(baseKey)) {
                    sections.put(baseKey, axisMap);
                }
                if (!gridPrefix.isEmpty()) {
                    sections.put(gridPrefix + baseKey, axisMap);
                }
            }
        }
    }

    private static String columnLabel(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = (n / 26) - 1;
        }
        return sb.toString();
    }

    private static void putAttrIfNotEmpty(Map<String, String> target, String key, org.w3c.dom.Element el, String attr) {
        String v = el.getAttribute(attr);
        if (!blank(v)) {
            target.put(key, v);
        }
    }

    private static String firstMetadataExpression(org.w3c.dom.Element parent) {
        List<String> all = allMetadataExpressions(parent);
        return all.isEmpty() ? "" : String.join(", ", all);
    }

    private static final java.util.regex.Pattern SQL_MEMBER_EXPR = java.util.regex.Pattern.compile(
            "(?i)WHERE\\s+([A-Za-z0-9_]+)\\(\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern XML_FILTER_OP = java.util.regex.Pattern.compile(
            "<COMPOSITEOPERATION\\s+TYPE=\"Filter\">(.*?)</COMPOSITEOPERATION>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern XML_OP = java.util.regex.Pattern.compile(
            "<OPERATION\\s+TYPE=\"([^\"]+)\">(.*?)</OPERATION>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern XML_STRING = java.util.regex.Pattern.compile(
            "<STRING>(.*?)</STRING>", java.util.regex.Pattern.DOTALL);

    private static List<String> allMetadataExpressions(org.w3c.dom.Element parent) {
        List<String> out = new ArrayList<>();
        org.w3c.dom.NodeList nl = parent.getElementsByTagName("METADATAEXPRESSION");
        for (int i = 0; i < nl.getLength(); i++) {
            String v = ((org.w3c.dom.Element) nl.item(i)).getAttribute("VALUE");
            if (!blank(v)) {
                out.add(cleanMetadataExpression(v));
            }
        }
        return out;
    }

    private static String cleanMetadataExpression(String raw) {
        String t = raw.trim();
        if (t.startsWith("<?xml") || t.startsWith("<COMPOSITEOPERATION")) {
            java.util.regex.Matcher fm = XML_FILTER_OP.matcher(t);
            String scope = fm.find() ? fm.group(1) : t;
            java.util.regex.Matcher om = XML_OP.matcher(scope);
            List<String> parts = new ArrayList<>();
            while (om.find()) {
                String opType = om.group(1);
                if ("Select".equalsIgnoreCase(opType)) {
                    continue;
                }
                List<String> args = new ArrayList<>();
                java.util.regex.Matcher sm = XML_STRING.matcher(om.group(2));
                while (sm.find()) {
                    args.add(sm.group(1).trim());
                }
                if (args.size() > 1) {
                    // Last STRING in FR MemberQuery Filter operations is the dimension name
                    args.remove(args.size() - 1);
                }
                if ("Member".equalsIgnoreCase(opType) && args.size() == 1) {
                    parts.add(args.get(0));
                } else if (!args.isEmpty()) {
                    parts.add(opType + "(" + String.join(", ", args) + ")");
                }
            }
            if (!parts.isEmpty()) {
                return String.join(", ", parts);
            }
        }
        if (t.toUpperCase(Locale.ROOT).startsWith("SELECT ")) {
            java.util.regex.Matcher m = SQL_MEMBER_EXPR.matcher(t);
            if (m.find()) {
                String fn = m.group(1);
                String member = m.group(2);
                return "Member".equalsIgnoreCase(fn) ? member : (fn + "(" + member + ")");
            }
        }
        return t;
    }

    private static String extractCustomHeading(org.w3c.dom.Element cell) {
        org.w3c.dom.NodeList nl = cell.getElementsByTagName("CUSTOMHEADING");
        for (int i = 0; i < nl.getLength(); i++) {
            String v = nl.item(i).getTextContent();
            if (!blank(v)) {
                return v.replaceAll("\\s+", " ").trim();
            }
        }
        return "";
    }

    private static int parseIntSafe(String val, int fallback) {
        if (blank(val)) {
            return fallback;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Map<String, Object> viewFrObject(ReportDetail detail, String fmt, String povSlice,
                                             String entity, String partner) {
        String code = detail.code == null ? "" : detail.code;
        Map<String, Map<String, String>> sections = parseSections(code);
        boolean html = "HTML".equalsIgnoreCase(fmt) || "HFM".equalsIgnoreCase(fmt);
        String rendered = renderFrSpecification(detail, sections, fmt, povSlice, entity, partner, html);

        String safeName = detail.info.name.replaceAll("[^A-Za-z0-9._-]", "_");
        String fileName = safeName + "_spec." + (html ? "html" : "txt");
        Path dir = Paths.get(config.extractExportDir()).resolve("reports");
        Path written = dir.resolve(fileName);
        try {
            Files.createDirectories(dir);
            Files.write(written, rendered.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(500, "hfm_error", "Could not write rendered FR report view: " + e.getMessage());
        }

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
        out.put("finished", true);
        out.put("status", TaskProgress.Status.COMPLETED.name());
        out.put("sections", sections);
        Map<String, Object> fileInfo = describeFile(written, true);
        out.put("file", fileInfo);
        Object content = fileInfo.remove("_content");
        if (content != null) {
            out.put("content", content);
        }
        return out;
    }

    private static String renderFrSpecification(ReportDetail detail, Map<String, Map<String, String>> sections,
                                                String fmt, String povSlice, String entity, String partner,
                                                boolean html) {
        String desc = detail.info.description != null && !detail.info.description.isEmpty()
                ? detail.info.description
                : detail.info.name;
        StringBuilder sb = new StringBuilder();
        if (html) {
            sb.append("<html><head><meta charset=\"UTF-8\"/><title>")
                    .append(escapeHtml(detail.info.name)).append("</title></head><body>\n");
            sb.append("<h1>").append(escapeHtml(detail.info.name)).append(" - ")
                    .append(escapeHtml(desc)).append("</h1>\n");
            sb.append("<p><strong>Type:</strong> ").append(escapeHtml(detail.info.reportType))
                    .append(" | <strong>Folder:</strong> ").append(escapeHtml(detail.info.folder));
            if (!blank(povSlice)) {
                sb.append(" | <strong>POV:</strong> ").append(escapeHtml(povSlice));
            }
            if (!blank(entity)) {
                sb.append(" | <strong>Entity:</strong> ").append(escapeHtml(entity));
            }
            sb.append("</p>\n");
            for (Map.Entry<String, Map<String, String>> sec : sections.entrySet()) {
                sb.append("<h2>").append(escapeHtml(sec.getKey())).append("</h2>\n");
                sb.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\"><thead><tr><th>Item</th><th>Definition</th></tr></thead><tbody>\n");
                for (Map.Entry<String, String> kv : sec.getValue().entrySet()) {
                    sb.append("<tr><td>").append(escapeHtml(kv.getKey())).append("</td><td>")
                            .append(escapeHtml(kv.getValue())).append("</td></tr>\n");
                }
                sb.append("</tbody></table>\n");
            }
            sb.append("</body></html>\n");
        } else {
            sb.append("Report: ").append(detail.info.name).append(" - ").append(desc).append("\n");
            sb.append("Type: ").append(detail.info.reportType).append(" | Folder: ").append(detail.info.folder)
                    .append(" | Format: ").append(fmt).append("\n");
            if (!blank(povSlice)) {
                sb.append("POV: ").append(povSlice).append("\n");
            }
            for (Map.Entry<String, Map<String, String>> sec : sections.entrySet()) {
                sb.append("\n[").append(sec.getKey()).append("]\n");
                for (Map.Entry<String, String> kv : sec.getValue().entrySet()) {
                    sb.append(kv.getKey()).append("=").append(kv.getValue()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Updates or adds section key=value pairs in {@code code} while preserving existing formatting and order. */
    static String applySections(String code, Map<String, Map<String, String>> updates) {
        if (isXmlReportCode(code)) {
            return applyFrXmlSections(code, updates);
        }
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

    private static String applyFrXmlSections(String xml, Map<String, Map<String, String>> updates) {
        if (updates == null || updates.isEmpty()) {
            return xml;
        }
        String result = xml;
        Map<String, String> general = findSectionIgnoreCase(updates, "General");
        if (general != null) {
            String desc = general.get("ReportDescription");
            if (desc == null) {
                desc = general.get("REPORT_DESCRIPTION");
            }
            if (desc != null) {
                result = result.replaceFirst("(REPORT_DESCRIPTION=\")([^\"]*)(\")",
                        "$1" + java.util.regex.Matcher.quoteReplacement(escapeHtml(desc)) + "$3");
            }
        }
        Map<String, String> pov = findSectionIgnoreCase(updates, "PointOfView");
        if (pov == null) {
            pov = findSectionIgnoreCase(updates, "POV");
        }
        if (pov != null) {
            for (Map.Entry<String, String> e : pov.entrySet()) {
                if (blank(e.getKey()) || e.getValue() == null) {
                    continue;
                }
                String dimQ = java.util.regex.Pattern.quote(e.getKey().trim());
                String pattern = "(<DIMENSION[^>]*\\bNAME=\"" + dimQ + "\"[^>]*>\\s*<METADATAEXPRESSION[^>]*\\bVALUE=\")([^\"]*)(\")";
                result = result.replaceAll(pattern,
                        "$1" + java.util.regex.Matcher.quoteReplacement(escapeHtml(e.getValue().trim())) + "$3");
            }
        }
        return result;
    }

    private static String[] splitFolderAndName(String folder, String rawName) {
        if (rawName != null && (rawName.contains("/") || rawName.contains("\\"))) {
            String norm = rawName.replace('\\', '/');
            while (norm.startsWith("/")) {
                norm = norm.substring(1);
            }
            while (norm.endsWith("/")) {
                norm = norm.substring(0, norm.length() - 1);
            }
            int lastSlash = norm.lastIndexOf('/');
            if (lastSlash > 0 && lastSlash < norm.length() - 1) {
                String pathPart = norm.substring(0, lastSlash);
                String namePart = norm.substring(lastSlash + 1);
                String effectiveFolder = (!blank(folder) && !"Root".equalsIgnoreCase(normalizeFolder(folder)))
                        ? folder
                        : pathPart;
                return new String[] {effectiveFolder, namePart};
            }
        }
        return new String[] {folder, rawName};
    }

    private static String defaultFileTypeFor(String reportType) {
        if (reportType == null) {
            return "RPTDEF";
        }
        switch (reportType.toUpperCase(Locale.ROOT)) {
            case "FR_REPORT":
                return "DES";
            case "FR_BOOK":
                return "KBK";
            case "FR_SNAPSHOT_BOOK":
                return "KBT";
            case "FR_SNAPSHOT_REPORT":
                return "RPT";
            case "FR_BATCH":
                return "BCH";
            case "FR_GRID":
                return "ROG";
            case "FR_ROW_COLUMN":
                return "ROS";
            case "FR_TEXT":
                return "ROT";
            case "FOLDER":
                return "FOLDER";
            default:
                return "RPTDEF";
        }
    }

    private static String defaultEncodingFor(String reportType) {
        if (reportType == null) {
            return "UTF-8";
        }
        switch (reportType.toUpperCase(Locale.ROOT)) {
            case "FR_REPORT":
            case "FR_SNAPSHOT_REPORT":
            case "FR_GRID":
            case "FR_ROW_COLUMN":
            case "FR_TEXT":
                return "ZIP:UTF-8";
            default:
                return "UTF-8";
        }
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
