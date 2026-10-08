package com.hfmmcp.daemon.service;

import com.hfmmcp.daemon.DaemonConfig;
import com.hfmmcp.daemon.backend.CopyRequest;
import com.hfmmcp.daemon.backend.DimensionInfo;
import com.hfmmcp.daemon.backend.ExtractRequest;
import com.hfmmcp.daemon.backend.HfmBackend;
import com.hfmmcp.daemon.backend.LoadRequest;
import com.hfmmcp.daemon.backend.ProcessAction;
import com.hfmmcp.daemon.backend.ProcessState;
import com.hfmmcp.daemon.backend.ServerTask;
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
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Process control (status, and START / PROMOTE / SUBMIT / ... actions) and server tasks
 * (consolidate, calculate, translate).
 *
 * <p>Actions run in two steps: {@link #preview} resolves the targets, reads their current state
 * and stores a single-use plan; {@link #execute} runs exactly that plan. They run as the logged-in
 * user, so HFM security decides what each user may do, and every step is audit-logged.
 */
public final class ActionService {
    /** HFM's wording when a unit is already at or past the level an action moves it to. */
    private static final Pattern ALREADY = Pattern.compile("(?i)already|not at the required status"
            + "|status does not allow|cannot be promoted|not at the proper review level");
    private static final String[] UNIT_DIMENSIONS = {"Scenario", "Year", "Period", "Value"};
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,19}");
    static final String EXTRACT_DATA = "EXTRACT_DATA";
    static final String COPY_DATA = "COPY_DATA";
    static final String LOAD_DATA = "LOAD_DATA";

    private final HfmBackend backend;
    private final HfmService hfm;
    private final DaemonConfig config;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public ActionService(HfmBackend backend, HfmService hfm, DaemonConfig config, AuditLog audit) {
        this.backend = backend;
        this.hfm = hfm;
        this.config = config;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- status (read-only)

    public Map<String, Object> processStatus(UserSession s, Map<String, String> pov, List<String> entities,
                                             List<Integer> phases) {
        Units units = units(s, pov, entities, phases, true, config.maxStatusUnits());
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> byState = new TreeMap<>();
        for (Unit u : units.list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", u.entity);
            row.put("phase", u.phase);
            try {
                ProcessState st = hfm.withSession(s, bs -> backend.getProcessState(bs, u.pov, u.phase));
                row.put("state", st.state());
                if (st.lastAction() != null) {
                    row.put("lastAction", st.lastAction());
                    row.put("lastUser", st.lastUser());
                    row.put("lastTime", time(st.lastTime()));
                    if (st.lastComment() != null && !st.lastComment().isEmpty()) {
                        row.put("lastComment", st.lastComment());
                    }
                }
                byState.merge(st.state(), 1, Integer::sum);
            } catch (ApiException e) {
                if ("session_expired".equals(e.code())) {
                    throw e;
                }
                row.put("state", null);
                row.put("error", e.getMessage());
                byState.merge("(error)", 1, Integer::sum);
            }
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pov", units.sharedPov);
        result.put("count", out.size());
        result.put("byState", byState);
        result.put("units", out);
        return result;
    }

    // ---------------------------------------------------------------- actions

    /** What a plan will do, stored until executed or expired. */
    private static final class Plan {
        final String id;
        final long createdAt = System.currentTimeMillis();
        final String action;
        final ProcessAction processAction;
        final ServerTask serverTask;
        final int level;
        final boolean includeDescendants;
        final String comment;
        final List<Unit> units;
        final Map<String, String> sharedPov;
        ExtractRequest extract;
        CopyRequest copy;
        LoadRequest load;

        Plan(String id, String action, ProcessAction pa, ServerTask st, int level, boolean includeDescendants,
             String comment, List<Unit> units, Map<String, String> sharedPov) {
            this.id = id;
            this.action = action;
            this.processAction = pa;
            this.serverTask = st;
            this.level = level;
            this.includeDescendants = includeDescendants;
            this.comment = comment;
            this.units = units;
            this.sharedPov = sharedPov;
        }
    }

    public Map<String, Object> preview(UserSession s, String action, Integer level, Map<String, String> pov,
                                       List<String> entities, List<Integer> phases, boolean includeDescendants,
                                       String comment) {
        requireEnabled();
        String name = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        ProcessAction pa = enumOrNull(ProcessAction.class, name);
        ServerTask st = enumOrNull(ServerTask.class, name);
        if (EXTRACT_DATA.equals(name) || COPY_DATA.equals(name) || LOAD_DATA.equals(name)) {
            throw ApiException.badRequest("bad_action", name + " has its own preview call");
        }
        if (pa == null && st == null) {
            throw ApiException.badRequest("bad_action", "action must be one of " + allActions());
        }
        if (!config.actionsAllowed().contains(name)) {
            throw new ApiException(403, "action_not_allowed",
                    name + " is not enabled on this daemon (allowed: " + config.actionsAllowed() + ")");
        }
        int lvl = 0;
        if (pa == ProcessAction.PROMOTE) {
            if (level == null || level < 1 || level > 10) {
                throw ApiException.badRequest("bad_level", "PROMOTE needs a review level from 1 to 10");
            }
            lvl = level;
        }

        // Server tasks act on a slice, not a phase.
        Units units = units(s, pov, entities, phases, pa != null, config.maxActionUnits());
        String id = newId();
        Plan plan = new Plan(id, name, pa, st, lvl, includeDescendants, comment, units.list, units.sharedPov);
        purgeExpired(s);
        s.actionPlans().put(id, plan);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Unit u : units.list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", u.entity);
            if (pa != null) {
                row.put("phase", u.phase);
                try {
                    row.put("currentState", hfm.withSession(s, bs -> backend.getProcessState(bs, u.pov, u.phase)).state());
                } catch (ApiException e) {
                    if ("session_expired".equals(e.code())) {
                        throw e;
                    }
                    row.put("currentState", null);
                    row.put("error", e.getMessage());
                }
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", id);
        out.put("expiresInMinutes", config.actionPlanTtlMinutes());
        out.put("action", name + (pa == ProcessAction.PROMOTE ? " to Review Level " + lvl : ""));
        out.put("pov", units.sharedPov);
        if (pa != null && includeDescendants) {
            out.put("includeDescendants", true);
        }
        out.put("count", rows.size());
        out.put("targets", rows);
        out.put("next", "Show this to the user. Only if they confirm, call execute with this planId.");
        return out;
    }

    public Map<String, Object> execute(UserSession s, String planId) {
        requireEnabled();
        Object o = planId == null ? null : s.actionPlans().remove(planId);
        if (!(o instanceof Plan) || expired((Plan) o)) {
            throw new ApiException(404, "plan_not_found",
                    "No such plan (plans are single-use and expire after " + config.actionPlanTtlMinutes()
                            + " minutes); preview the action again.");
        }
        Plan plan = (Plan) o;
        if (plan.extract != null) {
            return runExtract(s, plan);
        }
        if (plan.copy != null) {
            return runCopy(s, plan);
        }
        if (plan.load != null) {
            return runLoad(s, plan);
        }
        return plan.processAction != null ? runProcess(s, plan) : runServerTask(s, plan);
    }

    // ---------------------------------------------------------------- data extract and copy

    public Map<String, Object> previewExtract(UserSession s, ExtractRequest r) {
        requireAllowed(EXTRACT_DATA);
        if (r.format == null) {
            throw ApiException.badRequest("bad_request", "format must be FLATFILE, WAREHOUSE or METADATA");
        }
        if (r.slice == null || r.slice.trim().isEmpty()) {
            throw ApiException.badRequest("bad_request", "slice is required, e.g. S#Actual.Y#2025.P{[Base]}.E{Group.[Base]}");
        }
        if (r.prefix == null || !PREFIX.matcher(r.prefix).matches()) {
            throw ApiException.badRequest("bad_request", "prefix must be 1-20 letters, digits or _, starting with a letter");
        }
        if (r.format != ExtractRequest.Format.FLATFILE && (r.dsn == null || r.dsn.trim().isEmpty())) {
            throw ApiException.badRequest("bad_request", "dsn is required for database extracts (no extract.defaultDsn configured)");
        }
        List<String> filled = new ArrayList<>();
        String slice = hfm.completeSlice(s, r.slice, filled);
        requireValidSlice(s, "slice", slice);
        r = new ExtractRequest(r.format, slice, r.prefix, r.dsn, r.includeCalculated, r.includeDerived,
                r.includeDynamicAccounts);
        Plan plan = newPlan(s, EXTRACT_DATA);
        plan.extract = r;
        Map<String, Object> out = planHeader(plan);
        out.put("format", r.format.name());
        out.put("slice", slice);
        if (!filled.isEmpty()) {
            out.put("filledFromDefaultPov", filled); // HFM needs every dimension in an extract slice
        }
        out.put("prefix", r.prefix);
        if (r.format == ExtractRequest.Format.FLATFILE) {
            out.put("effect", "Extracts the slice to a ';'-delimited file in " + config.extractExportDir() + ".");
        } else {
            out.put("dsn", r.dsn);
            out.put("effect", "Re-creates the " + (r.format == ExtractRequest.Format.METADATA ? "metadata" : "star-schema")
                    + " tables prefixed '" + r.prefix + "' in DSN " + r.dsn + " (STARSCHEMA_CREATE): existing tables"
                    + " with that prefix are replaced.");
        }
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("includeCalculated", r.includeCalculated);
        opts.put("includeDerived", r.includeDerived);
        opts.put("includeDynamicAccounts", r.includeDynamicAccounts);
        out.put("options", opts);
        out.put("next", "Show this to the user. Only if they confirm, call execute with this planId.");
        return out;
    }

    public Map<String, Object> previewCopy(UserSession s, CopyRequest r) {
        requireAllowed(COPY_DATA);
        if (blank(r.source) || blank(r.target) || blank(r.entitiesAndAccounts)) {
            throw ApiException.badRequest("bad_request", "source, target and entitiesAndAccounts are required");
        }
        if (r.source.trim().equals(r.target.trim())) {
            throw ApiException.badRequest("bad_request", "source and target are the same slice");
        }
        if (r.mode == null) {
            throw ApiException.badRequest("bad_request", "mode must be MERGE, REPLACE or ACCUMULATE");
        }
        if (Double.isNaN(r.scale) || Double.isInfinite(r.scale)) {
            throw ApiException.badRequest("bad_request", "scale must be a number");
        }
        requireValidSlice(s, "source", r.source);
        requireValidSlice(s, "target", r.target);
        requireValidSlice(s, "entitiesAndAccounts", r.entitiesAndAccounts);
        Plan plan = newPlan(s, COPY_DATA);
        plan.copy = r;
        Map<String, Object> out = planHeader(plan);
        out.put("source", r.source);
        out.put("target", r.target);
        out.put("entitiesAndAccounts", r.entitiesAndAccounts);
        out.put("view", r.view);
        out.put("mode", r.mode.name());
        out.put("scale", r.scale);
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("copyRatesAndSystemData", r.copyRatesAndSystemData);
        opts.put("copyDerivedData", r.copyDerivedData);
        opts.put("copyCellText", r.copyCellText);
        out.put("options", opts);
        String effect;
        switch (r.mode) {
            case REPLACE:
                effect = "Clears the target cells, then writes the source values";
                break;
            case ACCUMULATE:
                effect = "Adds the source values to the target values";
                break;
            default:
                effect = "Overwrites target cells that have source data; leaves the others";
        }
        out.put("effect", effect + (r.scale != 1.0 ? ", scaled by " + r.scale : "") + ". This changes data in "
                + r.target + ".");
        out.put("next", "Show this to the user. Only if they confirm, call execute with this planId.");
        return out;
    }

    /**
     * Checks an HFM slice ({@code S#Actual.Y#2025.E{Group.[Base]}.A{[Base]}}) member by member before
     * a plan is made, so a mistake is reported here with suggestions, not by HFM at execution as
     * "The POV selected for this function is invalid".
     */
    @SuppressWarnings("unchecked")
    private void requireValidSlice(UserSession s, String label, String slice) {
        String pov = slice.trim().replaceAll("(^|\\.)([A-Za-z0-9]+)\\{", "$1$2#{"); // E{...} is E#{...}
        Map<String, Object> v = hfm.validatePov(s, pov);
        if (Boolean.TRUE.equals(v.get("valid"))) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> d : (List<Map<String, Object>>) v.get("dimensions")) {
            for (Map<String, Object> bad : (List<Map<String, Object>>) d.getOrDefault("invalid", new ArrayList<>())) {
                Object hint = bad.get("didYouMean");
                problems.add(d.get("prefix") + "#" + bad.get("member") + ": " + bad.get("reason")
                        + (hint != null ? " (did you mean " + hint + "?)" : ""));
            }
        }
        for (Object u : (List<Object>) v.getOrDefault("unknownDimensions", new ArrayList<>())) {
            problems.add("unknown dimension '" + u + "'");
        }
        throw ApiException.badRequest("bad_pov", label + " '" + slice + "' is not valid: " + String.join("; ", problems));
    }

    /**
     * Resolves {@code file} inside {@code load.allowedDir} (relative names are taken from there),
     * refusing anything outside it.
     */
    public Path resolveLoadFile(String file) {
        requireAllowed(LOAD_DATA);
        String dir = config.loadAllowedDir();
        if (dir == null) {
            throw new ApiException(403, "action_not_allowed",
                    "Data loads need load.allowedDir set on the daemon (the folder load files must be in).");
        }
        if (blank(file)) {
            throw ApiException.badRequest("bad_request", "file is required");
        }
        try {
            Path root = Paths.get(dir).toRealPath();
            Path p = root.resolve(file.trim()).normalize();
            if (!Files.isRegularFile(p)) {
                throw ApiException.badRequest("bad_request", "No such file in the load folder: " + file);
            }
            p = p.toRealPath();
            if (!p.startsWith(root)) {
                throw new ApiException(403, "action_not_allowed", "Load files must be inside " + root);
            }
            return p;
        } catch (IOException e) {
            throw ApiException.badRequest("bad_request", "Cannot open " + file + ": " + e.getMessage());
        }
    }

    public Map<String, Object> previewLoad(UserSession s, LoadRequest r) {
        requireAllowed(LOAD_DATA);
        if (r.mode == null) {
            throw ApiException.badRequest("bad_request", "mode must be MERGE, ACCUMULATE, REPLACE or REPLACE_WITH_SECURITY");
        }
        if (r.delimiter == null || r.delimiter.length() != 1) {
            throw ApiException.badRequest("bad_request", "delimiter must be one character, e.g. ',' or ';'");
        }
        Plan plan = newPlan(s, LOAD_DATA);
        plan.load = r;
        Map<String, Object> out = planHeader(plan);
        out.put("scanOnly", r.scanOnly);
        out.put("file", describe(r.file));
        out.put("mode", r.mode.name());
        out.put("delimiter", r.delimiter);
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("accumulateWithinFile", r.accumulateWithinFile);
        opts.put("containsOwnershipData", r.containsOwnershipData);
        out.put("options", opts);
        String effect;
        if (r.scanOnly) {
            effect = "SCAN only: HFM checks the file and reports errors in its log; no data is loaded.";
        } else {
            switch (r.mode) {
                case REPLACE:
                    effect = "Clears the data for each Scenario/Year/Period/Entity/Value in the file, then loads it";
                    break;
                case REPLACE_WITH_SECURITY:
                    effect = "Clears the data the user may write for each Scenario/Year/Period/Entity/Value in the file, then loads it";
                    break;
                case ACCUMULATE:
                    effect = "Adds the file's values to the data already in HFM";
                    break;
                default:
                    effect = "Overwrites cells that are in the file; leaves the others";
            }
            effect += ". This changes data in HFM.";
        }
        out.put("effect", effect);
        out.put("next", "Show this to the user. Only if they confirm, call execute with this planId.");
        return out;
    }

    private Map<String, Object> runLoad(UserSession s, Plan plan) {
        LoadRequest r = plan.load;
        String action = LOAD_DATA + (r.scanOnly ? " SCAN" : "");
        String target = r.file + " mode=" + r.mode + (r.accumulateWithinFile ? " accumulateWithinFile" : "")
                + (r.containsOwnershipData ? " ownership" : "");
        List<Integer> ids;
        try {
            ids = hfm.withSession(s, bs -> backend.startLoad(bs, r));
        } catch (ApiException e) {
            audit.record(s.userName(), s.application(), action, target, null, "failed", e.getMessage());
            throw e;
        }
        audit.record(s.userName(), s.application(), action, target, null, "started", "tasks " + ids);
        List<TaskProgress> progress = waitFor(s, ids);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("file", r.file.toString());
        out.put("taskIds", ids);
        out.put("finished", allFinished(progress));
        out.put("tasks", tasks(progress));
        for (Integer id : ids) {
            s.pendingExtracts().put(id, r);
        }
        collectFinishedExtracts(s, progress, out);
        if (!allFinished(progress)) {
            out.put("next", "Still running; check later with the task status call and these taskIds.");
        } else {
            for (TaskProgress t : progress) {
                audit.record(s.userName(), s.application(), action, target, null,
                        t.status().name().toLowerCase(Locale.ROOT), t.description());
            }
        }
        return out;
    }

    private Map<String, Object> runExtract(UserSession s, Plan plan) {
        ExtractRequest r = plan.extract;
        String target = r.format + " " + r.slice + " prefix=" + r.prefix + (r.dsn != null ? " dsn=" + r.dsn : "");
        int taskId;
        try {
            taskId = hfm.withSession(s, bs -> backend.startExtract(bs, r));
        } catch (ApiException e) {
            audit.record(s.userName(), s.application(), EXTRACT_DATA, target, null, "failed", e.getMessage());
            throw e;
        }
        audit.record(s.userName(), s.application(), EXTRACT_DATA, target, null, "started", "task " + taskId);
        List<Integer> ids = Collections.singletonList(taskId);
        List<TaskProgress> progress = waitFor(s, ids);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", EXTRACT_DATA);
        out.put("format", r.format.name());
        out.put("slice", r.slice);
        out.put("taskIds", ids);
        out.put("finished", allFinished(progress));
        out.put("tasks", tasks(progress));
        if (r.format == ExtractRequest.Format.FLATFILE) {
            s.pendingExtracts().put(taskId, r);
            collectFinishedExtracts(s, progress, out);
        }
        if (!allFinished(progress)) {
            out.put("next", "Still running; check later with the task status call and these taskIds.");
        } else {
            audit.record(s.userName(), s.application(), EXTRACT_DATA, target, null,
                    progress.get(0).status().name().toLowerCase(Locale.ROOT), progress.get(0).description());
        }
        return out;
    }

    private Map<String, Object> runCopy(UserSession s, Plan plan) {
        CopyRequest r = plan.copy;
        String target = r.source + " -> " + r.target + " [" + r.entitiesAndAccounts + "] " + r.mode
                + " view=" + r.view + (r.scale != 1.0 ? " scale=" + r.scale : "");
        String logPath;
        try {
            logPath = hfm.withSession(s, bs -> backend.copyData(bs, r));
        } catch (ApiException e) {
            audit.record(s.userName(), s.application(), COPY_DATA, target, null, "failed", e.getMessage());
            throw e;
        }
        audit.record(s.userName(), s.application(), COPY_DATA, target, null, "completed", logPath);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", COPY_DATA);
        out.put("source", r.source);
        out.put("target", r.target);
        out.put("mode", r.mode.name());
        out.put("completed", true);
        if (logPath != null) {
            out.put("hfmLog", logPath);
        }
        out.put("note", "Copied data may leave parents needing consolidation; check calc status in the target.");
        return out;
    }

    /** For finished flat-file extracts of this session: fetch the file once and describe it. */
    private void collectFinishedExtracts(UserSession s, List<TaskProgress> progress, Map<String, Object> out) {
        List<Map<String, Object>> files = new ArrayList<>();
        List<Map<String, Object>> logs = new ArrayList<>();
        for (TaskProgress t : progress) {
            Object pending = s.pendingExtracts().get(t.taskId());
            if (pending instanceof LoadRequest && t.finished()) {
                s.pendingExtracts().remove(t.taskId());
                String text = hfm.withSession(s, bs -> backend.fetchTaskLog(bs, t.taskId()));
                if (text != null) {
                    Map<String, Object> log = new LinkedHashMap<>();
                    log.put("taskId", t.taskId());
                    int max = config.maxLogChars();
                    log.put("truncated", text.length() > max);
                    log.put("text", text.length() > max ? text.substring(0, max) : text);
                    logs.add(log);
                }
                continue;
            }
            if (!(pending instanceof ExtractRequest) || !t.finished()) {
                continue;
            }
            s.pendingExtracts().remove(t.taskId());
            if (t.status() != TaskProgress.Status.COMPLETED) {
                continue;
            }
            ExtractRequest r = (ExtractRequest) pending;
            Path dir = Paths.get(config.extractExportDir()).resolve(r.prefix + "_" + t.taskId());
            List<Path> written = hfm.withSession(s, bs -> backend.fetchExtractFile(bs, t.taskId(), dir));
            for (Path p : written) {
                files.add(describe(p));
            }
            audit.record(s.userName(), s.application(), EXTRACT_DATA, "task " + t.taskId(), null, "file",
                    dir.toAbsolutePath().toString());
        }
        if (!files.isEmpty()) {
            out.put("files", files);
        }
        if (!logs.isEmpty()) {
            out.put("logs", logs);
        }
    }

    private Map<String, Object> describe(Path p) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("path", p.toAbsolutePath().toString());
        List<String> preview = new ArrayList<>();
        long lines = 0;
        try {
            f.put("bytes", Files.size(p));
            try (BufferedReader in = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (lines++ < config.extractPreviewLines()) {
                        preview.add(line);
                    }
                }
            }
            f.put("lines", lines);
            f.put("preview", preview);
        } catch (IOException | java.io.UncheckedIOException e) {
            f.put("error", "Cannot read file: " + e.getMessage());
        }
        return f;
    }

    private Plan newPlan(UserSession s, String action) {
        purgeExpired(s);
        Plan plan = new Plan(newId(), action, null, null, 0, false, null,
                Collections.<Unit>emptyList(), Collections.<String, String>emptyMap());
        s.actionPlans().put(plan.id, plan);
        return plan;
    }

    private Map<String, Object> planHeader(Plan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", plan.id);
        out.put("expiresInMinutes", config.actionPlanTtlMinutes());
        out.put("action", plan.action);
        return out;
    }

    private void requireAllowed(String action) {
        requireEnabled();
        if (!config.actionsAllowed().contains(action)) {
            throw new ApiException(403, "action_not_allowed",
                    action + " is not enabled on this daemon (allowed: " + config.actionsAllowed() + ")");
        }
    }

    private static boolean blank(String v) {
        return v == null || v.trim().isEmpty();
    }

    public Map<String, Object> taskStatus(UserSession s, List<Integer> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw ApiException.badRequest("bad_request", "taskIds is required");
        }
        List<TaskProgress> progress = hfm.withSession(s, bs -> backend.getTaskProgress(bs, taskIds));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("finished", allFinished(progress));
        out.put("tasks", tasks(progress));
        collectFinishedExtracts(s, progress, out);
        return out;
    }

    private Map<String, Object> runProcess(UserSession s, Plan plan) {
        int completed = 0;
        int skipped = 0;
        int failed = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Unit u : plan.units) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", u.entity);
            row.put("phase", u.phase);
            String outcome;
            String message = null;
            try {
                hfm.withSession(s, bs -> {
                    backend.runProcessAction(bs, plan.processAction, u.pov, u.phase, plan.level,
                            plan.includeDescendants, plan.comment);
                    return null;
                });
                outcome = "completed";
                completed++;
            } catch (ApiException e) {
                if ("session_expired".equals(e.code())) {
                    audit.record(s.userName(), s.application(), plan.action, u.pov, u.phase, "aborted", e.getMessage());
                    throw e;
                }
                message = e.getMessage();
                if (ALREADY.matcher(message).find()) {
                    outcome = "skipped";
                    skipped++;
                } else {
                    outcome = "failed";
                    failed++;
                }
            }
            audit.record(s.userName(), s.application(), plan.action + (plan.level > 0 ? " L" + plan.level : ""),
                    u.pov + (plan.includeDescendants ? " +descendants" : ""), u.phase, outcome, message);
            row.put("outcome", outcome);
            if (message != null) {
                row.put("message", message);
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", plan.action);
        out.put("pov", plan.sharedPov);
        out.put("completed", completed);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("results", rows);
        return out;
    }

    private Map<String, Object> runServerTask(UserSession s, Plan plan) {
        List<String> povs = new ArrayList<>();
        for (Unit u : plan.units) {
            povs.add(u.pov);
        }
        List<Integer> taskIds;
        try {
            taskIds = hfm.withSession(s, bs -> backend.startServerTask(bs, plan.serverTask, povs));
        } catch (ApiException e) {
            for (String p : povs) {
                audit.record(s.userName(), s.application(), plan.action, p, null, "failed", e.getMessage());
            }
            throw e;
        }
        if (taskIds.isEmpty()) {
            // Finished within the call (calculate / translate).
            for (String p : povs) {
                audit.record(s.userName(), s.application(), plan.action, p, null, "completed", null);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("action", plan.action);
            out.put("pov", plan.sharedPov);
            out.put("entities", entityNames(plan.units));
            out.put("finished", true);
            out.put("completed", true);
            return out;
        }
        for (String p : povs) {
            audit.record(s.userName(), s.application(), plan.action, p, null, "started", "tasks " + taskIds);
        }

        List<TaskProgress> progress = waitFor(s, taskIds);
        boolean finished = allFinished(progress);
        if (finished) {
            for (TaskProgress t : progress) {
                audit.record(s.userName(), s.application(), plan.action, "task " + t.taskId(), null,
                        t.status().name().toLowerCase(Locale.ROOT), t.description());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", plan.action);
        out.put("pov", plan.sharedPov);
        out.put("entities", entityNames(plan.units));
        out.put("taskIds", taskIds);
        out.put("finished", finished);
        out.put("tasks", tasks(progress));
        if (!finished) {
            out.put("next", "Still running; check later with the task status call and these taskIds.");
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static final class Unit {
        final String entity;
        final int phase;
        final String pov;

        Unit(String entity, int phase, String pov) {
            this.entity = entity;
            this.phase = phase;
            this.pov = pov;
        }
    }

    private static final class Units {
        final List<Unit> list;
        final Map<String, String> sharedPov;

        Units(List<Unit> list, Map<String, String> sharedPov) {
            this.list = list;
            this.sharedPov = sharedPov;
        }
    }

    /**
     * Process units: Scenario, Year, Period and Value from {@code pov}/defaults, × entities, ×
     * phases when {@code withPhases} (server tasks act on the slice, so they get phase 0).
     */
    private Units units(UserSession s, Map<String, String> pov, List<String> entities, List<Integer> phases,
                        boolean withPhases, int max) {
        PovResolver r = hfm.resolver(s);
        LinkedHashMap<String, String> base = r.basePov(pov);
        DimensionInfo entityDim = r.resolve("Entity");
        List<String> names = entities == null || entities.isEmpty()
                ? Collections.singletonList(base.get(entityDim.prefix()))
                : hfm.expandVary(s, entityDim, entities);
        List<Integer> ph;
        if (!withPhases) {
            ph = Collections.singletonList(0);
        } else {
            ph = phases == null || phases.isEmpty() ? Collections.singletonList(1) : phases;
            for (Integer p : ph) {
                if (p == null || p < 1 || p > 9) {
                    throw ApiException.badRequest("bad_phase", "phases must be between 1 and 9");
                }
            }
        }
        if ((long) names.size() * ph.size() > max) {
            throw ApiException.badRequest("too_many_units", names.size() + " entities × " + ph.size()
                    + " phases is more than the limit of " + max + "; narrow the request.");
        }

        LinkedHashMap<String, String> shared = new LinkedHashMap<>();
        LinkedHashMap<String, String> sharedByName = new LinkedHashMap<>();
        for (String dim : UNIT_DIMENSIONS) {
            DimensionInfo d = r.resolve(dim);
            shared.put(d.prefix(), base.get(d.prefix()));
            sharedByName.put(d.name(), base.get(d.prefix()));
        }
        List<Unit> list = new ArrayList<>();
        for (String e : names) {
            LinkedHashMap<String, String> unit = new LinkedHashMap<>(shared);
            unit.put(entityDim.prefix(), e);
            String unitPov = Pov.format(unit);
            for (Integer p : ph) {
                list.add(new Unit(e, p, unitPov));
            }
        }
        return new Units(list, sharedByName);
    }

    private void requireEnabled() {
        if (!config.actionsEnabled()) {
            throw new ApiException(403, "actions_disabled",
                    "Process-control actions and consolidations are disabled on this daemon (actions.enabled=false).");
        }
    }

    private boolean expired(Plan p) {
        return System.currentTimeMillis() - p.createdAt > config.actionPlanTtlMinutes() * 60_000L;
    }

    private void purgeExpired(UserSession s) {
        for (Iterator<Object> it = s.actionPlans().values().iterator(); it.hasNext(); ) {
            Object o = it.next();
            if (o instanceof Plan && expired((Plan) o)) {
                it.remove();
            }
        }
    }

    private String newId() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> allActions() {
        List<String> all = new ArrayList<>();
        for (ProcessAction a : ProcessAction.values()) {
            all.add(a.name());
        }
        for (ServerTask t : ServerTask.values()) {
            all.add(t.name());
        }
        return all;
    }

    /** Polls until every task has finished or {@code actions.waitSeconds} has passed. */
    private List<TaskProgress> waitFor(UserSession s, List<Integer> taskIds) {
        List<TaskProgress> progress = Collections.emptyList();
        long deadline = System.currentTimeMillis() + config.actionWaitSeconds() * 1000L;
        while (!taskIds.isEmpty()) {
            progress = hfm.withSession(s, bs -> backend.getTaskProgress(bs, taskIds));
            if (allFinished(progress) || System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(2000);
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

    private static List<Map<String, Object>> tasks(List<TaskProgress> progress) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskProgress t : progress) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.taskId());
            m.put("status", t.status().name());
            m.put("percentComplete", t.percentComplete());
            if (t.description() != null && !t.description().isEmpty()) {
                m.put("description", t.description());
            }
            out.add(m);
        }
        return out;
    }

    private static List<String> entityNames(List<Unit> units) {
        List<String> out = new ArrayList<>();
        for (Unit u : units) {
            out.add(u.entity);
        }
        return out;
    }

    /** HFM history times: seconds or milliseconds since the epoch. */
    private static String time(long t) {
        if (t <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(t < 100_000_000_000L ? t * 1000 : t).toString();
    }
}
