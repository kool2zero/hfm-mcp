package com.hfmmcp.daemon.service;

import com.hfmmcp.daemon.DaemonConfig;
import com.hfmmcp.daemon.backend.DimensionInfo;
import com.hfmmcp.daemon.backend.HfmBackend;
import com.hfmmcp.daemon.backend.JournalAction;
import com.hfmmcp.daemon.backend.JournalDetail;
import com.hfmmcp.daemon.backend.JournalError;
import com.hfmmcp.daemon.backend.JournalInfo;
import com.hfmmcp.daemon.backend.JournalQuery;
import com.hfmmcp.daemon.http.ApiException;
import com.hfmmcp.daemon.pov.PovResolver;
import com.hfmmcp.daemon.session.UserSession;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Journals: listing and reading (always available), and creating, editing, deleting and moving
 * them through their workflow (preview, then confirm).
 *
 * <p>Writes follow the same two steps as {@link ActionService}: a preview resolves everything and
 * stores a single-use plan, and {@link #execute} runs exactly that plan as the logged-in user.
 * Each write is its own entry in {@code actions.allowed}: {@code JOURNAL_SAVE}, {@code JOURNAL_DELETE},
 * {@code JOURNAL_SUBMIT}, {@code JOURNAL_UNSUBMIT}, {@code JOURNAL_APPROVE}, {@code JOURNAL_REJECT},
 * {@code JOURNAL_POST} and {@code JOURNAL_UNPOST}.
 */
public final class JournalService {
    static final String SAVE = "JOURNAL_SAVE";
    private static final String[] UNIT_DIMENSIONS = {"Scenario", "Year", "Period", "Value"};
    private static final Set<String> STATUSES = new HashSet<>(
            Arrays.asList("WORKING", "SUBMITTED", "APPROVED", "REJECTED", "POSTED"));
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList("REGULAR", "AUTOREVERSING", "AUTOREVERSAL"));
    private static final int MAX_ROWS = 500;
    private static final int MAX_LINES = 500;

    /** One line of a journal as the caller supplies it; dimension keys may be names or POV prefixes. */
    public static final class LineInput {
        public final String side;
        public final String amount;
        public final String description;
        public final Map<String, String> dimensions;

        public LineInput(String side, String amount, String description, Map<String, String> dimensions) {
            this.side = side;
            this.amount = amount;
            this.description = description;
            this.dimensions = dimensions;
        }
    }

    /** What a plan will do, stored until executed or expired. */
    static final class Plan {
        final String id;
        final long createdAt = System.currentTimeMillis();
        final String action;
        final Map<String, String> pov;
        final JournalDetail journal;
        final JournalAction journalAction;
        final List<String> labels;

        Plan(String id, String action, Map<String, String> pov, JournalDetail journal, JournalAction journalAction,
             List<String> labels) {
            this.id = id;
            this.action = action;
            this.pov = pov;
            this.journal = journal;
            this.journalAction = journalAction;
            this.labels = labels;
        }
    }

    private final HfmBackend backend;
    private final HfmService hfm;
    private final DaemonConfig config;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public JournalService(HfmBackend backend, HfmService hfm, DaemonConfig config, AuditLog audit) {
        this.backend = backend;
        this.hfm = hfm;
        this.config = config;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- reading

    public Map<String, Object> list(UserSession s, Map<String, String> pov, JournalQuery q) {
        Unit u = unit(s, pov);
        List<String> statuses = upper(q.statuses, STATUSES, "status");
        List<String> types = upper(q.types, TYPES, "type");
        JournalQuery query = new JournalQuery(statuses, types, q.label, q.group, q.entity, q.description);
        List<JournalInfo> found = hfm.withSession(s,
                bs -> backend.listJournals(bs, u.scenario, u.year, u.period, u.value, query));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (JournalInfo j : found) {
            byStatus.merge(j.status == null ? "(unknown)" : j.status, 1, Integer::sum);
            if (rows.size() < MAX_ROWS) {
                rows.add(row(j));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pov", u.byName);
        out.put("count", found.size());
        out.put("byStatus", byStatus);
        if (found.size() > MAX_ROWS) {
            out.put("truncated", true);
            out.put("note", "Showing the first " + MAX_ROWS + "; narrow with status, group, entity or label.");
        }
        out.put("journals", rows);
        return out;
    }

    public Map<String, Object> get(UserSession s, Map<String, String> pov, String label) {
        if (blank(label)) {
            throw ApiException.badRequest("bad_request", "label is required");
        }
        Unit u = unit(s, pov);
        JournalDetail j = hfm.withSession(s, bs -> backend.getJournal(bs, label.trim(), u.scenario, u.year, u.period));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pov", u.byName);
        out.put("id", j.id);
        out.put("label", j.label);
        out.put("description", j.description);
        out.put("group", j.group);
        out.put("status", j.status);
        out.put("type", j.type);
        out.put("balanceType", j.balanceType);
        if (!blank(j.singleEntity)) {
            out.put("singleEntity", j.singleEntity);
        }
        if (j.pov != null && !j.pov.isEmpty()) {
            out.put("journalPov", j.pov);
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JournalDetail.Line l : j.lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", l.index);
            m.put("side", l.side);
            m.put("amount", l.amount);
            if (!blank(l.description)) {
                m.put("description", l.description);
            }
            m.put("dimensions", l.dimensions);
            lines.add(m);
            BigDecimal a = decimal(l.amount);
            if (a != null) {
                if ("DEBIT".equals(l.side)) {
                    debit = debit.add(a);
                } else {
                    credit = credit.add(a);
                }
            }
        }
        out.put("lines", lines);
        out.put("totalDebits", debit.toPlainString());
        out.put("totalCredits", credit.toPlainString());
        return out;
    }

    // ---------------------------------------------------------------- previews

    public Map<String, Object> previewSave(UserSession s, Map<String, String> pov, String label, String description,
                                           String group, String type, List<LineInput> lines) {
        requireAllowed(SAVE);
        if (blank(label)) {
            throw ApiException.badRequest("bad_request", "label is required");
        }
        if (lines == null || lines.isEmpty()) {
            throw ApiException.badRequest("bad_request", "lines is required: at least one debit or credit line");
        }
        if (lines.size() > MAX_LINES) {
            throw ApiException.badRequest("too_many_lines", "A journal may have at most " + MAX_LINES + " lines");
        }
        String journalType = type == null || type.trim().isEmpty() ? "REGULAR" : upper(type, TYPES, "type");
        Unit u = unit(s, pov);
        PovResolver r = hfm.resolver(s);
        String entityName = r.resolve("Entity").name();
        String accountName = r.resolve("Account").name();

        List<JournalDetail.Line> built = new ArrayList<>();
        Map<String, BigDecimal[]> byEntity = new LinkedHashMap<>();
        int index = 0;
        for (LineInput in : lines) {
            index++;
            String side = in.side == null ? "" : in.side.trim().toUpperCase(Locale.ROOT);
            if (!"DEBIT".equals(side) && !"CREDIT".equals(side)) {
                throw ApiException.badRequest("bad_line", "Line " + index + ": side must be DEBIT or CREDIT");
            }
            BigDecimal amount = decimal(in.amount);
            if (amount == null || amount.signum() < 0) {
                throw ApiException.badRequest("bad_line", "Line " + index
                        + ": amount must be a number of zero or more (use side for the direction)");
            }
            Map<String, String> dims = new LinkedHashMap<>();
            if (in.dimensions != null) {
                for (Map.Entry<String, String> e : in.dimensions.entrySet()) {
                    if (blank(e.getValue())) {
                        throw ApiException.badRequest("bad_line", "Line " + index + ": empty member for '" + e.getKey() + "'");
                    }
                    dims.put(r.resolve(e.getKey()).name(), e.getValue().trim());
                }
            }
            if (!dims.containsKey(entityName) || !dims.containsKey(accountName)) {
                throw ApiException.badRequest("bad_line", "Line " + index + ": dimensions must include "
                        + entityName + " and " + accountName + " (plus ICP and custom dimensions where the account needs them)");
            }
            built.add(new JournalDetail.Line(index, in.description == null ? "" : in.description,
                    amount.toPlainString(), side, dims));
            BigDecimal[] t = byEntity.computeIfAbsent(dims.get(entityName), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t["DEBIT".equals(side) ? 0 : 1] = t["DEBIT".equals(side) ? 0 : 1].add(amount);
        }

        // An existing journal is replaced, and only while HFM still lets it be edited.
        JournalInfo existing = find(s, u, label.trim());
        if (existing != null && !"WORKING".equals(existing.status) && !"REJECTED".equals(existing.status)) {
            throw ApiException.badRequest("journal_locked", "Journal " + existing.label + " is " + existing.status
                    + " and cannot be edited; unpost, unsubmit or reject it first.");
        }
        JournalDetail journal = new JournalDetail(existing == null ? 0 : existing.id, label.trim(),
                description == null ? "" : description, group == null ? "" : group.trim(), "WORKING", journalType, null,
                null, u.povForJournal, built);

        List<JournalError> errors = hfm.withSession(s, bs -> backend.scanJournal(bs, journal));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", SAVE);
        out.put("pov", u.byName);
        out.put("label", journal.label);
        out.put("replacesExisting", existing != null);
        out.put("type", journalType);
        out.put("lines", describeLines(built));
        List<Map<String, Object>> balance = new ArrayList<>();
        boolean balanced = true;
        for (Map.Entry<String, BigDecimal[]> e : byEntity.entrySet()) {
            boolean ok = e.getValue()[0].compareTo(e.getValue()[1]) == 0;
            balanced &= ok;
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("entity", e.getKey());
            b.put("debits", e.getValue()[0].toPlainString());
            b.put("credits", e.getValue()[1].toPlainString());
            b.put("balanced", ok);
            balance.add(b);
        }
        out.put("balanceByEntity", balance);
        out.put("balanced", balanced);
        if (!errors.isEmpty()) {
            List<Map<String, Object>> problems = new ArrayList<>();
            for (JournalError e : errors) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("line", e.line);
                p.put("message", e.message);
                problems.add(p);
            }
            out.put("scanErrors", problems);
            out.put("next", "HFM's scan found problems, so there is no plan to confirm. Fix them and preview again.");
            return out;
        }
        out.put("effect", (existing == null ? "Creates the journal " : "Replaces the lines of journal ") + journal.label
                + " as WORKING" + (balanced ? "." : "; it is out of balance, so HFM will not let it be submitted or posted until it balances."));
        Plan plan = store(s, new Plan(newId(), SAVE, u.byName, journal, null, null));
        out.put("planId", plan.id);
        out.put("expiresInMinutes", config.actionPlanTtlMinutes());
        out.put("next", "Show this to the user. Only if they confirm, call execute with this planId.");
        return out;
    }

    public Map<String, Object> previewAction(UserSession s, String action, Map<String, String> pov, List<String> labels) {
        String name = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        JournalAction ja;
        try {
            ja = JournalAction.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_action", "action must be one of " + Arrays.toString(JournalAction.values()));
        }
        String key = "JOURNAL_" + name;
        requireAllowed(key);
        if (labels == null || labels.isEmpty()) {
            throw ApiException.badRequest("bad_request", "labels is required");
        }
        if (labels.size() > config.maxActionUnits()) {
            throw ApiException.badRequest("too_many_units", labels.size() + " journals is more than the limit of "
                    + config.maxActionUnits() + "; narrow the request.");
        }
        Unit u = unit(s, pov);
        Map<String, JournalInfo> all = new LinkedHashMap<>();
        List<JournalInfo> found = hfm.withSession(s, bs -> backend.listJournals(bs, u.scenario, u.year, u.period,
                u.value, new JournalQuery(null, null, null, null, null, null)));
        for (JournalInfo j : found) {
            all.put(j.label.toUpperCase(Locale.ROOT), j);
        }
        List<String> exact = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String label : labels) {
            JournalInfo j = blank(label) ? null : all.get(label.trim().toUpperCase(Locale.ROOT));
            if (j == null) {
                missing.add(label);
                continue;
            }
            exact.add(j.label);
            rows.add(row(j));
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("no_such_journal", "No such journal in " + u.byName + ": " + missing);
        }
        Plan plan = store(s, new Plan(newId(), key, u.byName, null, ja, exact));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", plan.id);
        out.put("expiresInMinutes", config.actionPlanTtlMinutes());
        out.put("action", key);
        out.put("pov", u.byName);
        out.put("count", rows.size());
        out.put("targets", rows);
        if (ja == JournalAction.DELETE) {
            out.put("effect", "Permanently deletes these journals.");
        }
        out.put("next", "Show this to the user. Only if they confirm, call execute with this planId.");
        return out;
    }

    // ---------------------------------------------------------------- execute

    /** True when {@code plan} was made by this service. */
    static boolean owns(Object plan) {
        return plan instanceof Plan;
    }

    Map<String, Object> execute(UserSession s, Object o) {
        Plan plan = (Plan) o;
        if (System.currentTimeMillis() - plan.createdAt > config.actionPlanTtlMinutes() * 60_000L) {
            throw new ApiException(404, "plan_not_found", "That plan has expired; preview the action again.");
        }
        return plan.journal != null ? runSave(s, plan) : runAction(s, plan);
    }

    private Map<String, Object> runSave(UserSession s, Plan plan) {
        JournalDetail j = plan.journal;
        String target = povText(plan.pov) + " " + j.label;
        int id;
        try {
            id = hfm.withSession(s, bs -> backend.saveJournal(bs, j));
        } catch (ApiException e) {
            audit.record(s.userName(), s.application(), SAVE, target, null, "failed", e.getMessage());
            throw e;
        }
        audit.record(s.userName(), s.application(), SAVE, target, null, "completed", "id " + id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", SAVE);
        out.put("pov", plan.pov);
        out.put("label", j.label);
        out.put("id", id);
        out.put("completed", true);
        out.put("next", "Read it back with the journal call to confirm what HFM stored.");
        return out;
    }

    private Map<String, Object> runAction(UserSession s, Plan plan) {
        Unit u = unit(s, plan.pov);
        List<String> notProcessed;
        try {
            notProcessed = hfm.withSession(s, bs -> backend.runJournalAction(bs, plan.journalAction, plan.labels,
                    u.scenario, u.year, u.period));
        } catch (ApiException e) {
            for (String l : plan.labels) {
                audit.record(s.userName(), s.application(), plan.action, povText(plan.pov) + " " + l, null, "failed",
                        e.getMessage());
            }
            throw e;
        }
        Set<String> notDone = new HashSet<>();
        for (String l : notProcessed) {
            notDone.add(l.toUpperCase(Locale.ROOT));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int completed = 0;
        for (String l : plan.labels) {
            boolean ok = !notDone.contains(l.toUpperCase(Locale.ROOT));
            completed += ok ? 1 : 0;
            audit.record(s.userName(), s.application(), plan.action, povText(plan.pov) + " " + l, null,
                    ok ? "completed" : "skipped", ok ? null : "HFM did not process it");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", l);
            row.put("outcome", ok ? "completed" : "not processed");
            if (!ok) {
                row.put("message", "HFM did not process it: wrong status for this action, no rights, or the"
                        + " journal is out of balance.");
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", plan.action);
        out.put("pov", plan.pov);
        out.put("completed", completed);
        out.put("notProcessed", plan.labels.size() - completed);
        out.put("results", rows);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static final class Unit {
        final String scenario;
        final String year;
        final String period;
        final String value;
        final Map<String, String> byName;
        final Map<String, String> povForJournal;

        Unit(String scenario, String year, String period, String value, Map<String, String> byName) {
            this.scenario = scenario;
            this.year = year;
            this.period = period;
            this.value = value;
            this.byName = byName;
            this.povForJournal = byName;
        }
    }

    /** Scenario, Year, Period and Value from {@code pov}, filled from the default POV. Keyed by name. */
    private Unit unit(UserSession s, Map<String, String> pov) {
        PovResolver r = hfm.resolver(s);
        LinkedHashMap<String, String> base = r.basePov(pov);
        Map<String, String> byName = new LinkedHashMap<>();
        for (String dim : UNIT_DIMENSIONS) {
            DimensionInfo d = r.resolve(dim);
            byName.put(d.name(), base.get(d.prefix()));
        }
        return new Unit(base.get(r.resolve("Scenario").prefix()), base.get(r.resolve("Year").prefix()),
                base.get(r.resolve("Period").prefix()), base.get(r.resolve("Value").prefix()), byName);
    }

    private JournalInfo find(UserSession s, Unit u, String label) {
        List<JournalInfo> found = hfm.withSession(s, bs -> backend.listJournals(bs, u.scenario, u.year, u.period,
                u.value, new JournalQuery(null, null, label, null, null, null)));
        for (JournalInfo j : found) {
            if (j.label != null && j.label.equalsIgnoreCase(label)) {
                return j;
            }
        }
        return null;
    }

    private Map<String, Object> row(JournalInfo j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", j.label);
        m.put("id", j.id);
        m.put("status", j.status);
        m.put("type", j.type);
        m.put("balanceType", j.balanceType);
        if (!blank(j.description)) {
            m.put("description", j.description);
        }
        if (!blank(j.group)) {
            m.put("group", j.group);
        }
        if (!blank(j.entity)) {
            m.put("entity", j.entity);
        }
        if (!blank(j.createdBy)) {
            m.put("createdBy", j.createdBy);
            m.put("createdOn", time(j.createdOn));
        }
        if (!blank(j.lastActedBy)) {
            m.put("lastActedBy", j.lastActedBy);
            m.put("lastActedOn", time(j.lastActedOn));
        }
        if (!blank(j.approvedBy)) {
            m.put("approvedBy", j.approvedBy);
            m.put("approvedOn", time(j.approvedOn));
        }
        return m;
    }

    private static List<Map<String, Object>> describeLines(List<JournalDetail.Line> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JournalDetail.Line l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", l.index);
            m.put("side", l.side);
            m.put("amount", l.amount);
            if (!blank(l.description)) {
                m.put("description", l.description);
            }
            m.put("dimensions", l.dimensions);
            out.add(m);
        }
        return out;
    }

    private static List<String> upper(List<String> values, Set<String> allowed, String what) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            out.add(upper(v, allowed, what));
        }
        return out;
    }

    private static String upper(String v, Set<String> allowed, String what) {
        String n = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(n)) {
            throw ApiException.badRequest("bad_request", "'" + v + "' is not a journal " + what + "; use one of "
                    + new java.util.TreeSet<>(allowed));
        }
        return n;
    }

    private static BigDecimal decimal(String v) {
        if (blank(v)) {
            return null;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String povText(Map<String, String> pov) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : pov.entrySet()) {
            sb.append(sb.length() == 0 ? "" : ".").append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private Plan store(UserSession s, Plan plan) {
        for (Iterator<Object> it = s.actionPlans().values().iterator(); it.hasNext(); ) {
            Object o = it.next();
            if (o instanceof Plan && System.currentTimeMillis() - ((Plan) o).createdAt
                    > config.actionPlanTtlMinutes() * 60_000L) {
                it.remove();
            }
        }
        s.actionPlans().put(plan.id, plan);
        return plan;
    }

    private void requireAllowed(String action) {
        if (!config.actionsEnabled()) {
            throw new ApiException(403, "actions_disabled",
                    "Journal changes are disabled on this daemon (actions.enabled=false).");
        }
        if (!config.actionsAllowed().contains(action)) {
            throw new ApiException(403, "action_not_allowed",
                    action + " is not enabled on this daemon (allowed: " + config.actionsAllowed() + ")");
        }
    }

    private String newId() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean blank(String v) {
        return v == null || v.trim().isEmpty();
    }

    /** HFM times: seconds or milliseconds since the epoch. */
    private static String time(long t) {
        if (t <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(t < 100_000_000_000L ? t * 1000 : t).toString();
    }
}
