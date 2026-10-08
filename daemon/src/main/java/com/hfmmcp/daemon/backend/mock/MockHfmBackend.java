package com.hfmmcp.daemon.backend.mock;

import com.hfmmcp.daemon.backend.AuthResult;
import com.hfmmcp.daemon.backend.BackendException;
import com.hfmmcp.daemon.backend.BackendSession;
import com.hfmmcp.daemon.backend.CellResult;
import com.hfmmcp.daemon.backend.CopyRequest;
import com.hfmmcp.daemon.backend.ExtractRequest;
import com.hfmmcp.daemon.backend.DimensionInfo;
import com.hfmmcp.daemon.backend.HfmBackend;
import com.hfmmcp.daemon.backend.LoadRequest;
import com.hfmmcp.daemon.backend.MemberInfo;
import com.hfmmcp.daemon.backend.ProcessAction;
import com.hfmmcp.daemon.backend.ProcessState;
import com.hfmmcp.daemon.backend.ServerTask;
import com.hfmmcp.daemon.backend.TaskProgress;
import com.hfmmcp.daemon.pov.Pov;
import com.hfmmcp.daemon.util.FileUnpacker;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory stand-in for HFM with a small sample application, so the daemon and the MCP server can
 * be developed and tested without an EPM install. Any user name works with the password
 * {@code password}. Entity and Account parents aggregate their base members.
 */
public final class MockHfmBackend implements HfmBackend {
    public static final String PASSWORD = "password";

    private static final long NODATA = 2L;
    private static final long NEEDS_CONSOLIDATION = 16777216L;
    private static final Pattern LIST = Pattern.compile("\\{(?:(.+)\\.)?\\[(\\w+)]}");

    private final List<DimensionInfo> dimensions = Arrays.asList(
            new DimensionInfo("S", "Scenario", "Actual"),
            new DimensionInfo("Y", "Year", "2025"),
            new DimensionInfo("P", "Period", "Dec"),
            new DimensionInfo("W", "View", "YTD"),
            new DimensionInfo("V", "Value", "<Entity Currency>"),
            new DimensionInfo("E", "Entity", "Group"),
            new DimensionInfo("A", "Account", "NetIncome"),
            new DimensionInfo("I", "ICP", "[ICP None]"),
            new DimensionInfo("C1", "Product", "[None]"),
            new DimensionInfo("C2", "Flows", "[None]"),
            new DimensionInfo("C3", "Custom3", "[None]"),
            new DimensionInfo("C4", "Custom4", "[None]"));

    /** Dimension name → parent → children, roots under the key "". */
    private final Map<String, Map<String, List<String>>> trees = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();

    /** HFM process-control states, in order. */
    private static final List<String> STATES = Arrays.asList("Not Started", "First Pass",
            "Review Level 1", "Review Level 2", "Review Level 3", "Review Level 4", "Review Level 5",
            "Review Level 6", "Review Level 7", "Review Level 8", "Review Level 9", "Review Level 10",
            "Submitted", "Approved", "Published");
    private static final int NOT_STARTED = 0;
    private static final int FIRST_PASS = 1;
    private static final int SUBMITTED = 12;
    private static final int APPROVED = 13;
    private static final int PUBLISHED = 14;

    /** "S|Y|P|E|phase" → state index; absent means Not Started. */
    private final Map<String, Integer> states = new ConcurrentHashMap<>();
    private final Map<String, ProcessState> lastActions = new ConcurrentHashMap<>();
    /** "S|Y|P|E" slices consolidated since start; their CN status is cleared. */
    private final Set<String> consolidated = ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextTaskId = new AtomicInteger(1000);
    private final Map<Integer, TaskProgress> tasks = new ConcurrentHashMap<>();
    private final Map<Integer, ExtractRequest> extracts = new ConcurrentHashMap<>();
    /** Target "S|Y|P" → source "S|Y|P" of a data copy; target cells read the source's values. */
    private final Map<String, String> copiedFrom = new ConcurrentHashMap<>();
    /** "S|Y|P|W|E|A" → value loaded from a file. */
    private final Map<String, Double> loaded = new ConcurrentHashMap<>();
    private final Map<Integer, String> taskLogs = new ConcurrentHashMap<>();

    public MockHfmBackend() {
        tree("Scenario", "", "Actual", "Budget");
        tree("Year", "", "2024", "2025", "2026");
        tree("Period", "", "[Year]");
        tree("Period", "[Year]", "Q1", "Q2", "Q3", "Q4");
        tree("Period", "Q1", "Jan", "Feb", "Mar");
        tree("Period", "Q2", "Apr", "May", "Jun");
        tree("Period", "Q3", "Jul", "Aug", "Sep");
        tree("Period", "Q4", "Oct", "Nov", "Dec");
        tree("View", "", "YTD", "Periodic");
        tree("Value", "", "<Entity Currency>", "<Parent Currency>", "USD");
        tree("Entity", "", "Group");
        tree("Entity", "Group", "US", "UK");
        tree("Entity", "US", "US01", "US02");
        tree("Entity", "UK", "UK01");
        tree("Account", "", "NetIncome");
        tree("Account", "NetIncome", "Revenue", "Expenses");
        tree("Account", "Revenue", "Sales", "OtherRevenue");
        tree("Account", "Expenses", "COGS", "Opex");
        tree("ICP", "", "[ICP None]");
        tree("Product", "", "[None]");
        tree("Flows", "", "[None]");
        tree("Custom3", "", "[None]");
        tree("Custom4", "", "[None]");
        descriptions.put("Group", "Consolidated group");
        descriptions.put("US", "United States");
        descriptions.put("US01", "US Operations East");
        descriptions.put("US02", "US Operations West");
        descriptions.put("UK", "United Kingdom");
        descriptions.put("UK01", "UK Operations");
        descriptions.put("NetIncome", "Net Income");
        descriptions.put("Revenue", "Total Revenue");
        descriptions.put("Sales", "Sales Revenue");
        descriptions.put("OtherRevenue", "Other Revenue");
        descriptions.put("Expenses", "Total Expenses");
        descriptions.put("COGS", "Cost of Goods Sold");
        descriptions.put("Opex", "Operating Expenses");
    }

    private static final class MockSession implements BackendSession {
        final String user;
        final String application;
        volatile boolean valid = true;

        MockSession(String user, String application) {
            this.user = user;
            this.application = application;
        }
    }

    /** Test hook: makes HFM report the session as gone on its next use. */
    public static void invalidate(BackendSession s) {
        ((MockSession) s).valid = false;
    }

    @Override
    public AuthResult authenticate(String userName, char[] password) throws BackendException {
        if (!PASSWORD.equals(new String(password))) {
            throw new BackendException(BackendException.Kind.AUTH_FAILED, "Invalid credentials");
        }
        return new AuthResult(userName, "mock-token-" + UUID.randomUUID());
    }

    @Override
    public BackendSession openSession(AuthResult auth, String application) throws BackendException {
        if (!auth.ssoToken().startsWith("mock-token-")) {
            throw new BackendException(BackendException.Kind.AUTH_EXPIRED, "Token not accepted");
        }
        return new MockSession(auth.userName(), application);
    }

    @Override
    public void closeSession(BackendSession session) {
        if (session != null) {
            ((MockSession) session).valid = false;
        }
    }

    @Override
    public List<DimensionInfo> getDimensions(BackendSession session) throws BackendException {
        check(session);
        return dimensions;
    }

    @Override
    public List<MemberInfo> expandMembers(BackendSession session, String dimensionName, String expression)
            throws BackendException {
        check(session);
        String dim = dimension(dimensionName);
        String expr = expression.trim();
        List<String[]> pairs = new ArrayList<>(); // {member, parent}
        Matcher m = LIST.matcher(expr);
        if (m.matches()) {
            String member = m.group(1);
            String list = m.group(2).toLowerCase(Locale.ROOT);
            if (member != null) {
                requireMember(dim, member);
            }
            switch (list) {
                case "hierarchy":
                    walk(dim, "", true, false, pairs);
                    break;
                case "children":
                    for (String c : children(dim, member)) {
                        pairs.add(new String[] {c, member});
                    }
                    break;
                case "descendants":
                    walk(dim, member, true, false, pairs);
                    break;
                case "base":
                    walk(dim, member == null ? "" : member, false, true, pairs);
                    break;
                case "parents":
                case "ancestors":
                    String p = parentOf(dim, member);
                    while (p != null && !p.isEmpty()) {
                        pairs.add(new String[] {p, parentOf(dim, p)});
                        if ("parents".equals(list)) {
                            break;
                        }
                        p = parentOf(dim, p);
                    }
                    break;
                default:
                    throw new BackendException(BackendException.Kind.BAD_REQUEST, "Unknown member list " + expr);
            }
        } else {
            requireMember(dim, expr);
            pairs.add(new String[] {expr, parentOf(dim, expr)});
        }
        List<MemberInfo> out = new ArrayList<>();
        for (String[] pair : pairs) {
            out.add(new MemberInfo(pair[0], descriptions.get(pair[0]), pair[1], null));
        }
        return out;
    }

    @Override
    public List<CellResult> getCells(BackendSession session, List<String> povs) throws BackendException {
        check(session);
        List<CellResult> out = new ArrayList<>();
        for (String pov : povs) {
            Map<String, String> p = Pov.parse(pov);
            String invalid = null;
            for (DimensionInfo d : dimensions) {
                String member = p.get(d.prefix());
                if (member == null) {
                    invalid = "POV is missing " + d.name();
                } else if (parentOf(d.name(), lastSegment(member)) == null) {
                    invalid = "Invalid member '" + member + "' for dimension " + d.name();
                }
            }
            if (invalid != null) {
                // Like HFM's errorDetail: one bad POV fails only its own cell.
                out.add(new CellResult(pov, 0, 0, invalid, null));
                continue;
            }
            String entity = lastSegment(p.get("E"));
            String account = p.get("A");
            String period = p.get("P");
            String source = copiedFrom.get(slice(p));
            if (source != null) {
                String[] sp = source.split("\\|");
                p = new LinkedHashMap<>(p);
                p.put("S", sp[0]);
                p.put("Y", sp[1]);
                p.put("P", sp[2]);
            }
            long status = 0;
            double value;
            if ("2026".equals(p.get("Y")) && !"Q1".equals(period) && !"Jan".equals(period)
                    && !"Feb".equals(period) && !"Mar".equals(period)) {
                value = 0;
                status |= NODATA; // future periods are empty
            } else {
                value = Math.round(aggregate(p, entity, account) * 100.0) / 100.0;
            }
            if ("Budget".equals(p.get("S")) && !children("Entity", entity).isEmpty()
                    && !consolidated.contains(slice(p) + "|" + entity)) {
                status |= NEEDS_CONSOLIDATION;
            }
            out.add(new CellResult(pov, value, status, null));
        }
        return out;
    }

    // ---------------------------------------------------------------- process control

    @Override
    public ProcessState getProcessState(BackendSession session, String unitPov, int phase) throws BackendException {
        check(session);
        Map<String, String> p = Pov.parse(unitPov);
        String entity = lastSegment(p.get("E"));
        requireMember("Entity", entity);
        String key = slice(p) + "|" + entity + "|" + phase;
        ProcessState last = lastActions.get(key);
        String state = STATES.get(states.getOrDefault(key, NOT_STARTED));
        return last == null ? new ProcessState(state, null, null, 0, null)
                : new ProcessState(state, last.lastAction(), last.lastUser(), last.lastTime(), last.lastComment());
    }

    @Override
    public void runProcessAction(BackendSession session, ProcessAction action, String unitPov, int phase,
                                 int promotionLevel, boolean includeDescendants, String comment)
            throws BackendException {
        check(session);
        Map<String, String> p = Pov.parse(unitPov);
        String entity = lastSegment(p.get("E"));
        requireMember("Entity", entity);
        List<String> targets = new ArrayList<>();
        targets.add(entity);
        if (includeDescendants) {
            List<String[]> desc = new ArrayList<>();
            walk("Entity", entity, true, false, desc);
            for (String[] d : desc) {
                targets.add(d[0]);
            }
        }
        int changed = 0;
        for (String e : targets) {
            String key = slice(p) + "|" + e + "|" + phase;
            int from = states.getOrDefault(key, NOT_STARTED);
            int to = transition(action, from, promotionLevel);
            if (to < 0) {
                continue;
            }
            states.put(key, to);
            lastActions.put(key, new ProcessState(STATES.get(to), action.name(), ((MockSession) session).user,
                    System.currentTimeMillis(), comment));
            changed++;
        }
        if (changed == 0) {
            throw new BackendException(BackendException.Kind.BAD_REQUEST,
                    "The process unit is not at the proper review level for this action (" + action + ")");
        }
    }

    private static int transition(ProcessAction action, int from, int level) {
        boolean inReview = from >= FIRST_PASS && from < SUBMITTED;
        switch (action) {
            case START:
                return from == NOT_STARTED ? FIRST_PASS : -1;
            case PROMOTE:
                int target = FIRST_PASS + level;
                return inReview && from < target ? target : -1;
            case SUBMIT:
                return inReview ? SUBMITTED : -1;
            case REJECT:
                return from > FIRST_PASS && from <= APPROVED ? FIRST_PASS : -1;
            case APPROVE:
                return from == SUBMITTED ? APPROVED : -1;
            case PUBLISH:
                return from == APPROVED ? PUBLISHED : -1;
            default:
                return -1;
        }
    }

    @Override
    public List<Integer> startServerTask(BackendSession session, ServerTask task, List<String> povs)
            throws BackendException {
        check(session);
        int id = nextTaskId.incrementAndGet();
        for (String pov : povs) {
            Map<String, String> p = Pov.parse(pov);
            String entity = lastSegment(p.get("E"));
            requireMember("Entity", entity);
            if (task.name().startsWith("CONSOLIDATE")) {
                consolidated.add(slice(p) + "|" + entity);
                List<String[]> desc = new ArrayList<>();
                walk("Entity", entity, true, false, desc);
                for (String[] d : desc) {
                    consolidated.add(slice(p) + "|" + d[0]);
                }
            }
        }
        List<Integer> ids = new ArrayList<>();
        if (task.name().startsWith("CONSOLIDATE")) {
            // Like HFM: consolidations are background tasks; calculate/translate finish in the call.
            tasks.put(id, new TaskProgress(id, TaskProgress.Status.COMPLETED, 100, task + " of " + povs.size() + " POV(s)"));
            ids.add(id);
        }
        return ids;
    }

    @Override
    public List<TaskProgress> getTaskProgress(BackendSession session, List<Integer> taskIds) throws BackendException {
        check(session);
        List<TaskProgress> out = new ArrayList<>();
        for (Integer id : taskIds) {
            TaskProgress t = tasks.get(id);
            out.add(t != null ? t : new TaskProgress(id, TaskProgress.Status.UNKNOWN, 0, "No such task"));
        }
        return out;
    }

    // ---------------------------------------------------------------- data management

    @Override
    public int startExtract(BackendSession session, ExtractRequest request) throws BackendException {
        check(session);
        // Like HFM: every dimension, written P#member or P#{list}, or "The POV selected ... is invalid".
        java.util.Set<String> given;
        try {
            given = com.hfmmcp.daemon.pov.Pov.parse(request.slice).keySet();
        } catch (IllegalArgumentException e) {
            given = java.util.Collections.emptySet();
        }
        for (DimensionInfo d : dimensions) {
            if (!given.contains(d.prefix())) {
                throw new BackendException(BackendException.Kind.BAD_REQUEST,
                        "(hr -2147220948) The POV selected for this function is invalid.");
            }
        }
        int id = nextTaskId.incrementAndGet();
        extracts.put(id, request);
        tasks.put(id, new TaskProgress(id, TaskProgress.Status.COMPLETED, 100, "Extended Analytics extract"));
        return id;
    }

    /** Writes a small zipped ';'-delimited extract, as HFM returns one, then unpacks it. */
    @Override
    public List<Path> fetchExtractFile(BackendSession session, int taskId, Path targetDir) throws BackendException {
        check(session);
        ExtractRequest r = extracts.get(taskId);
        if (r == null || r.format != ExtractRequest.Format.FLATFILE) {
            throw new BackendException(BackendException.Kind.BAD_REQUEST, "No flat-file extract for task " + taskId);
        }
        try {
            Path zip = Files.createTempFile("mock-extract", ".zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry(r.prefix + ".dat"));
                writeRows(out);
                out.closeEntry();
            }
            List<Path> files = FileUnpacker.unpack(zip, targetDir);
            Files.deleteIfExists(zip);
            return files;
        } catch (IOException e) {
            throw new BackendException(BackendException.Kind.HFM_ERROR, "Cannot write extract: " + e.getMessage(), e);
        }
    }

    private void writeRows(OutputStream out) throws IOException {
        Map<String, String> pov = new LinkedHashMap<>();
        pov.put("S", "Actual");
        pov.put("Y", "2025");
        pov.put("P", "Dec");
        pov.put("W", "YTD");
        for (String e : Arrays.asList("US01", "US02", "UK01")) {
            for (String a : Arrays.asList("Sales", "OtherRevenue", "COGS", "Opex")) {
                String row = "Actual;2025;Dec;YTD;<Entity Currency>;" + e + ";" + a + ";[ICP None];[None];[None];[None];[None];"
                        + baseValue(pov, e, a) + "\n";
                out.write(row.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Native load format, one cell per line after an optional {@code !Data} header:
     * Scenario;Year;Period;View;Entity;Value;Account;ICP;Custom1..4;Amount.
     */
    @Override
    public List<Integer> startLoad(BackendSession session, LoadRequest r) throws BackendException {
        check(session);
        List<String> lines;
        try {
            lines = Files.readAllLines(r.file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BackendException(BackendException.Kind.BAD_REQUEST, "Cannot read " + r.file + ": " + e.getMessage());
        }
        String[] dims = {"Scenario", "Year", "Period", "View", "Entity", "Value", "Account", "ICP",
                "Product", "Flows", "Custom3", "Custom4"};
        StringBuilder log = new StringBuilder((r.scanOnly ? "Scan" : "Load") + " of " + r.file.getFileName()
                + " (" + r.mode + ")\n");
        Map<String, Double> values = new LinkedHashMap<>();
        int errors = 0;
        int n = 0;
        for (String line : lines) {
            n++;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("!") || t.startsWith("'")) {
                continue;
            }
            String[] f = t.split(java.util.regex.Pattern.quote(r.delimiter), -1);
            if (f.length != dims.length + 1) {
                log.append("Line ").append(n).append(": expected ").append(dims.length + 1).append(" fields\n");
                errors++;
                continue;
            }
            String bad = null;
            for (int i = 0; i < dims.length && bad == null; i++) {
                if (parentOf(dims[i], f[i].trim()) == null) {
                    bad = "invalid member '" + f[i].trim() + "' for " + dims[i];
                }
            }
            double amount = 0;
            if (bad == null) {
                try {
                    amount = Double.parseDouble(f[dims.length].trim());
                } catch (NumberFormatException e) {
                    bad = "invalid amount '" + f[dims.length].trim() + "'";
                }
            }
            if (bad != null) {
                log.append("Line ").append(n).append(": ").append(bad).append("\n");
                errors++;
                continue;
            }
            String key = f[0].trim() + "|" + f[1].trim() + "|" + f[2].trim() + "|" + f[3].trim() + "|" + f[4].trim()
                    + "|" + f[6].trim();
            values.merge(key, amount, r.accumulateWithinFile ? Double::sum : (a, b) -> b);
        }
        if (!r.scanOnly) {
            for (Map.Entry<String, Double> e : values.entrySet()) {
                if (r.mode == LoadRequest.Mode.ACCUMULATE) {
                    loaded.merge(e.getKey(), e.getValue(), Double::sum);
                } else {
                    loaded.put(e.getKey(), e.getValue());
                }
            }
        }
        log.append(values.size()).append(" cell(s) ").append(r.scanOnly ? "valid" : "loaded").append(", ")
                .append(errors).append(" error(s)\n");
        int id = nextTaskId.incrementAndGet();
        taskLogs.put(id, log.toString());
        tasks.put(id, new TaskProgress(id, TaskProgress.Status.COMPLETED, 100, (r.scanOnly ? "Scan " : "Load ")
                + r.file.getFileName()));
        List<Integer> ids = new ArrayList<>();
        ids.add(id);
        return ids;
    }

    @Override
    public String fetchTaskLog(BackendSession session, int taskId) throws BackendException {
        check(session);
        return taskLogs.get(taskId);
    }

    @Override
    public String copyData(BackendSession session, CopyRequest request) throws BackendException {
        check(session);
        Map<String, String> src;
        Map<String, String> dst;
        try {
            src = Pov.parse(request.source);
            dst = Pov.parse(request.target);
        } catch (IllegalArgumentException e) {
            throw new BackendException(BackendException.Kind.BAD_REQUEST, e.getMessage());
        }
        for (Map<String, String> m : Arrays.asList(src, dst)) {
            for (String k : Arrays.asList("S", "Y", "P")) {
                if (m.get(k) == null) {
                    throw new BackendException(BackendException.Kind.BAD_REQUEST, "Copy slices need S#, Y# and P#");
                }
            }
        }
        copiedFrom.put(slice(dst), slice(src));
        return "mock/copydata-" + nextTaskId.incrementAndGet() + ".log";
    }

    private static String slice(Map<String, String> p) {
        return p.get("S") + "|" + p.get("Y") + "|" + p.get("P");
    }

    // ---------------------------------------------------------------- helpers

    private void check(BackendSession s) throws BackendException {
        if (!(s instanceof MockSession) || !((MockSession) s).valid) {
            throw new BackendException(BackendException.Kind.SESSION_INVALID, "Session is not valid");
        }
    }

    private void tree(String dim, String parent, String... children) {
        trees.computeIfAbsent(dim, k -> new LinkedHashMap<>())
                .computeIfAbsent(parent, k -> new ArrayList<>())
                .addAll(Arrays.asList(children));
    }

    private String dimension(String name) throws BackendException {
        for (String d : trees.keySet()) {
            if (d.equalsIgnoreCase(name)) {
                return d;
            }
        }
        throw new BackendException(BackendException.Kind.BAD_REQUEST, "Unknown dimension " + name);
    }

    private List<String> children(String dim, String member) {
        List<String> c = trees.get(dim).get(member == null ? "" : member);
        return c == null ? new ArrayList<>() : c;
    }

    private String parentOf(String dim, String member) {
        for (Map.Entry<String, List<String>> e : trees.get(dim).entrySet()) {
            if (e.getValue().contains(member)) {
                return e.getKey();
            }
        }
        return null;
    }

    private void requireMember(String dim, String member) throws BackendException {
        if (parentOf(dim, member) == null) {
            throw new BackendException(BackendException.Kind.BAD_REQUEST,
                    "Member '" + member + "' does not exist in dimension " + dim);
        }
    }

    private void walk(String dim, String member, boolean includeParents, boolean baseOnly, List<String[]> out) {
        for (String c : children(dim, member)) {
            boolean isBase = children(dim, c).isEmpty();
            if (includeParents || (baseOnly && isBase)) {
                out.add(new String[] {c, member});
            }
            walk(dim, c, includeParents, baseOnly, out);
        }
    }

    private double aggregate(Map<String, String> pov, String entity, String account) {
        List<String> ec = children("Entity", entity);
        if (!ec.isEmpty()) {
            double sum = 0;
            for (String c : ec) {
                sum += aggregate(pov, c, account);
            }
            return sum;
        }
        List<String> ac = children("Account", account);
        if (!ac.isEmpty()) {
            double sum = 0;
            for (String c : ac) {
                double v = aggregate(pov, entity, c);
                sum += isExpense(c) ? -v : v;
            }
            return sum;
        }
        return baseValue(pov, entity, account);
    }

    private boolean isExpense(String account) {
        return "Expenses".equals(account);
    }

    private double baseValue(Map<String, String> pov, String entity, String account) {
        Double v = loaded.get(pov.get("S") + "|" + pov.get("Y") + "|" + pov.get("P") + "|" + pov.get("W") + "|"
                + entity + "|" + account);
        if (v != null) {
            return v;
        }
        int periods = periodsThrough(pov.get("P"));
        if ("Periodic".equals(pov.get("W"))) {
            String p = pov.get("P");
            periods = p.startsWith("Q") ? 3 : "[Year]".equals(p) ? 12 : 1;
        }
        int seed = (entity + "|" + account + "|" + pov.get("S") + "|" + pov.get("Y")).hashCode();
        double monthly = 1000 + Math.abs(seed % 9000);
        if ("Budget".equals(pov.get("S"))) {
            monthly *= 1.05;
        }
        return monthly * periods;
    }

    private int periodsThrough(String period) {
        List<String> months = Arrays.asList("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec");
        int i = months.indexOf(period);
        if (i >= 0) {
            return i + 1;
        }
        if (period.matches("Q[1-4]")) {
            return 3 * (period.charAt(1) - '0');
        }
        return 12; // [Year]
    }

    private static String lastSegment(String member) {
        int dot = member.lastIndexOf('.');
        return dot < 0 ? member : member.substring(dot + 1);
    }
}
