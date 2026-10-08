package com.hfmmcp.daemon.service;

import com.hfmmcp.daemon.DaemonConfig;
import com.hfmmcp.daemon.backend.AuthResult;
import com.hfmmcp.daemon.backend.BackendException;
import com.hfmmcp.daemon.backend.BackendSession;
import com.hfmmcp.daemon.backend.CellResult;
import com.hfmmcp.daemon.backend.DimensionInfo;
import com.hfmmcp.daemon.backend.HfmBackend;
import com.hfmmcp.daemon.backend.MemberInfo;
import com.hfmmcp.daemon.http.ApiException;
import com.hfmmcp.daemon.pov.Pov;
import com.hfmmcp.daemon.pov.PovResolver;
import com.hfmmcp.daemon.session.LoginThrottle;
import com.hfmmcp.daemon.session.SessionManager;
import com.hfmmcp.daemon.session.UserSession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Read-only HFM operations exposed by the daemon, on top of an {@link HfmBackend}. */
public final class HfmService {
    private static final Logger LOG = Logger.getLogger(HfmService.class.getName());

    /** Relation names accepted by {@link #members} → HFM system member lists. */
    private static final Map<String, String> RELATIONS = new LinkedHashMap<>();

    static {
        RELATIONS.put("children", "[Children]");
        RELATIONS.put("descendants", "[Descendants]");
        RELATIONS.put("base", "[Base]");
        RELATIONS.put("parents", "[Parents]");
        RELATIONS.put("ancestors", "[Ancestors]");
    }

    /** What each calc status in a cells response means, for whoever reads the numbers. */
    private static final Map<String, String> STATUS_MEANINGS = new LinkedHashMap<>();

    static {
        STATUS_MEANINGS.put("OK", "Current.");
        STATUS_MEANINGS.put("OK SC", "Current, but metadata, rules or security changed since it was calculated.");
        STATUS_MEANINGS.put("CN", "Needs consolidation: data below this entity changed since it was last "
                + "consolidated at this Scenario/Year/Period/Value, so the number may be stale.");
        STATUS_MEANINGS.put("CH", "Needs calculation: inputs changed since rules last ran for this entity; may be stale.");
        STATUS_MEANINGS.put("TR", "Needs translation: the translated currency value may be stale.");
        STATUS_MEANINGS.put("NOACCESS", "Your HFM security does not let you read this cell.");
        STATUS_MEANINGS.put("INVALID", "Not a valid intersection (usually the account with these ICP/Custom members); "
                + "neither zero nor missing data.");
        STATUS_MEANINGS.put("ERROR", "HFM could not read this cell; see its error.");
    }

    private final HfmBackend backend;
    private final SessionManager sessions;
    private final LoginThrottle throttle;
    private final DaemonConfig config;

    public HfmService(HfmBackend backend, SessionManager sessions, LoginThrottle throttle, DaemonConfig config) {
        this.backend = backend;
        this.sessions = sessions;
        this.throttle = throttle;
        this.config = config;
    }

    // ---------------------------------------------------------------- sessions

    public Map<String, Object> login(String userName, char[] password, String application) {
        if (userName == null || userName.trim().isEmpty() || password == null || password.length == 0) {
            throw ApiException.badRequest("bad_request", "username and password are required");
        }
        String app = application == null || application.trim().isEmpty() ? config.defaultApplication() : application.trim();
        if (app == null) {
            throw ApiException.badRequest("bad_request", "application is required (no hfm.defaultApplication configured)");
        }
        if (throttle.isBlocked(userName)) {
            throw new ApiException(429, "too_many_attempts",
                    "Too many failed logins for this user; wait " + config.authFailureWindowMinutes() + " minutes.");
        }
        AuthResult auth;
        try {
            auth = backend.authenticate(userName.trim(), password);
        } catch (BackendException e) {
            if (e.kind() == BackendException.Kind.AUTH_FAILED || e.kind() == BackendException.Kind.AUTH_EXPIRED) {
                throttle.recordFailure(userName);
                LOG.info("Login failed for " + userName.trim());
                throw new ApiException(401, "auth_failed", "Invalid username or password.");
            }
            throw toApi(e);
        } finally {
            Arrays.fill(password, '\0');
        }
        throttle.recordSuccess(userName);

        BackendSession bs;
        try {
            bs = backend.openSession(auth, app);
        } catch (BackendException e) {
            throw toApi(e);
        }
        UserSession s = sessions.create(auth, app, bs);
        if (s == null) {
            backend.closeSession(bs);
            throw new ApiException(503, "too_many_sessions", "The daemon is at its session limit; try again later.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", s.id());
        out.put("user", auth.userName());
        out.put("application", app);
        out.put("idleTimeoutMinutes", config.sessionIdleTimeoutMinutes());
        return out;
    }

    public void logout(UserSession s) {
        sessions.remove(s);
    }

    // ---------------------------------------------------------------- metadata

    public Map<String, Object> dimensions(UserSession s) {
        PovResolver r = metadata(s).resolver;
        List<Map<String, Object>> dims = new ArrayList<>();
        Map<String, String> defaultPov = new LinkedHashMap<>();
        for (DimensionInfo d : r.dimensions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name());
            m.put("prefix", d.prefix());
            m.put("defaultMember", d.defaultMember());
            dims.add(m);
            defaultPov.put(d.name(), d.defaultMember());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", s.userName());
        out.put("application", s.application());
        out.put("dimensions", dims);
        out.put("defaultPov", defaultPov);
        return out;
    }

    /**
     * Members related to {@code member}, or the members an explicit HFM member-list
     * {@code expression} resolves to. With neither, the whole dimension hierarchy.
     */
    public Map<String, Object> members(UserSession s, String dimension, String member, String relation,
                                       String expression, int limit) {
        DimensionInfo dim = metadata(s).resolver.resolve(dimension);
        String expr;
        if (expression != null && !expression.trim().isEmpty()) {
            expr = expression.trim();
        } else if (member == null || member.trim().isEmpty()) {
            expr = "{[Hierarchy]}";
        } else {
            String rel = relation == null || relation.trim().isEmpty() ? "children" : relation.trim().toLowerCase(Locale.ROOT);
            if ("member".equals(rel)) {
                expr = member.trim();
            } else {
                String list = RELATIONS.get(rel);
                if (list == null) {
                    throw ApiException.badRequest("bad_relation",
                            "relation must be one of member, " + String.join(", ", RELATIONS.keySet()));
                }
                expr = "{" + member.trim() + "." + list + "}";
            }
        }
        return memberPage(dim, expr, expand(s, dim, expr), clampLimit(limit));
    }

    /** Case-insensitive search on member name and description, best matches first. */
    public Map<String, Object> search(UserSession s, String dimension, String query, int limit) {
        if (query == null || query.trim().isEmpty()) {
            throw ApiException.badRequest("bad_request", "query is required");
        }
        DimensionInfo dim = metadata(s).resolver.resolve(dimension);
        List<MemberInfo> all = hierarchy(s, dim);
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<MemberInfo> exact = new ArrayList<>();
        List<MemberInfo> prefix = new ArrayList<>();
        List<MemberInfo> contains = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MemberInfo m : all) {
            if (!seen.add(m.name())) {
                continue; // shared members appear once per parent in a hierarchy expansion
            }
            String name = m.name().toLowerCase(Locale.ROOT);
            String desc = m.description() == null ? "" : m.description().toLowerCase(Locale.ROOT);
            if (name.equals(q) || desc.equals(q)) {
                exact.add(m);
            } else if (name.startsWith(q) || desc.startsWith(q)) {
                prefix.add(m);
            } else if (name.contains(q) || desc.contains(q)) {
                contains.add(m);
            }
        }
        List<MemberInfo> ranked = new ArrayList<>(exact);
        ranked.addAll(prefix);
        ranked.addAll(contains);
        return memberPage(dim, null, ranked, clampLimit(limit));
    }

    /**
     * Checks every member of a POV string in HFM notation, e.g.
     * {@code S#Actual;Budget.Y#2025.E#{Group.[Base]};UK01.A#Sales}: each dimension may list several
     * members separated by ';', and members may be member lists ({@code {Parent.[List]}},
     * {@code {[Base]}}, {@code {NamedList}}) or parent-qualified ({@code Group.UK}).
     */
    public Map<String, Object> validatePov(UserSession s, String pov) {
        if (pov == null || pov.trim().isEmpty()) {
            throw ApiException.badRequest("bad_request", "pov is required");
        }
        PovResolver r = metadata(s).resolver;
        LinkedHashMap<String, String> parsed;
        try {
            parsed = Pov.parse(pov);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_pov", e.getMessage());
        }
        List<Map<String, Object>> dims = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int invalidTotal = 0;
        for (Map.Entry<String, String> e : parsed.entrySet()) {
            DimensionInfo d;
            try {
                d = r.resolve(e.getKey());
            } catch (ApiException ex) {
                unknown.add(e.getKey());
                invalidTotal++;
                continue;
            }
            seen.add(d.prefix());
            List<MemberInfo> all = hierarchy(s, d);
            Map<String, MemberInfo> byName = new java.util.HashMap<>();
            for (MemberInfo m : all) {
                byName.putIfAbsent(m.name().toUpperCase(Locale.ROOT), m);
            }
            List<Map<String, Object>> invalid = new ArrayList<>();
            int count = 0;
            for (String raw : e.getValue().split(";")) {
                String element = raw.trim();
                if (element.isEmpty()) {
                    continue;
                }
                count++;
                String problem = checkElement(s, d, element, all, byName);
                if (problem != null) {
                    Map<String, Object> bad = new LinkedHashMap<>();
                    bad.put("member", element);
                    bad.put("reason", problem);
                    List<String> close = suggestions(all, element);
                    if (!close.isEmpty()) {
                        bad.put("didYouMean", close);
                    }
                    invalid.add(bad);
                }
            }
            invalidTotal += invalid.size();
            Map<String, Object> dim = new LinkedHashMap<>();
            dim.put("dimension", d.name());
            dim.put("prefix", d.prefix());
            dim.put("members", count);
            if (!invalid.isEmpty()) {
                dim.put("invalid", invalid);
            }
            dims.add(dim);
        }
        List<String> omitted = new ArrayList<>();
        for (DimensionInfo d : r.dimensions()) {
            if (!seen.contains(d.prefix())) {
                omitted.add(d.name());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", invalidTotal == 0);
        out.put("invalidCount", invalidTotal);
        out.put("dimensions", dims);
        if (!unknown.isEmpty()) {
            out.put("unknownDimensions", unknown);
        }
        if (!omitted.isEmpty()) {
            out.put("omittedDimensions", omitted);
        }
        return out;
    }

    /**
     * An Extended Analytics slice with every dimension: HFM rejects an extract slice that leaves
     * one out ("The POV selected for this function is invalid"). Omitted dimensions take the
     * default-POV member (listed in {@code filled} as "Name = member"); {@code E{...}} is written
     * {@code E#{...}}; dimensions come out in the application's order.
     */
    public String completeSlice(UserSession s, String slice, List<String> filled) {
        PovResolver r = metadata(s).resolver;
        LinkedHashMap<String, String> given;
        try {
            given = Pov.parse(slice.trim().replaceAll("(^|\\.)([A-Za-z0-9]+)\\{", "$1$2#{"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_pov", e.getMessage());
        }
        Map<String, String> byPrefix = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : given.entrySet()) {
            DimensionInfo d = r.resolve(e.getKey());
            if (byPrefix.put(d.prefix(), e.getValue()) != null) {
                throw ApiException.badRequest("bad_pov", d.name() + " appears twice in the slice");
            }
        }
        LinkedHashMap<String, String> full = new LinkedHashMap<>();
        for (DimensionInfo d : r.dimensions()) {
            String member = byPrefix.get(d.prefix());
            if (member == null) {
                member = d.defaultMember();
                filled.add(d.name() + " = " + member);
            }
            full.put(d.prefix(), member);
        }
        return Pov.format(full);
    }

    /** Null when valid, else why not. */
    private String checkElement(UserSession s, DimensionInfo d, String element, List<MemberInfo> all,
                                Map<String, MemberInfo> byName) {
        if (element.startsWith("{")) {
            if (!element.endsWith("}")) {
                return "unbalanced braces";
            }
            String inner = element.substring(1, element.length() - 1);
            int dot = inner.indexOf(".[");
            if (dot < 0 && inner.contains(".")) {
                String member = inner.substring(inner.lastIndexOf('.') + 1);
                return "braces hold a member list such as {" + member + ".[Children]} or a named list; "
                        + "write one member without braces: " + d.prefix() + "#" + member;
            }
            if (dot > 0) {
                String parent = inner.substring(0, dot);
                String bare = parent.substring(parent.lastIndexOf('.') + 1);
                return byName.containsKey(bare.toUpperCase(Locale.ROOT)) ? null
                        : "parent '" + parent + "' of the member list does not exist";
            }
            try {
                expand(s, d, element);
                return null;
            } catch (ApiException e) {
                if ("session_expired".equals(e.code())) {
                    throw e;
                }
                return "not a valid member list: " + e.getMessage();
            }
        }
        String[] parts = element.split("\\.");
        String member = parts[parts.length - 1];
        MemberInfo m = byName.get(member.toUpperCase(Locale.ROOT));
        if (m == null) {
            return "no such member in " + d.name();
        }
        if (parts.length > 1) {
            String parent = parts[parts.length - 2];
            if (!byName.containsKey(parent.toUpperCase(Locale.ROOT))) {
                return "no such parent '" + parent + "'";
            }
            boolean related = false;
            boolean knowParents = false;
            for (MemberInfo x : all) {
                if (x.name().equalsIgnoreCase(member) && x.parent() != null) {
                    knowParents = true;
                    related |= x.parent().equalsIgnoreCase(parent);
                }
            }
            if (knowParents && !related) {
                return "'" + parent + "' is not a parent of '" + member + "'";
            }
        }
        return null;
    }

    private static List<String> suggestions(List<MemberInfo> all, String element) {
        String q = element.replaceAll("[{}\\[\\]]", "");
        int dot = q.lastIndexOf('.');
        q = (dot >= 0 ? q.substring(dot + 1) : q).toLowerCase(Locale.ROOT);
        if (q.length() < 2) {
            return new ArrayList<>();
        }
        // Substring and prefix matches first, then typos (edit distance up to a third of the length).
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String stem = q.substring(0, Math.max(2, q.length() - 2));
        for (MemberInfo m : all) {
            String n = m.name().toLowerCase(Locale.ROOT);
            if (n.contains(q) || q.contains(n) && n.length() > 2 || n.startsWith(stem)) {
                out.add(m.name());
                if (out.size() == 3) {
                    return new ArrayList<>(out);
                }
            }
        }
        int maxDistance = Math.max(1, q.length() / 3);
        java.util.TreeMap<Integer, java.util.LinkedHashSet<String>> close = new java.util.TreeMap<>();
        for (MemberInfo m : all) {
            int d = editDistance(q, m.name().toLowerCase(Locale.ROOT), maxDistance);
            if (d <= maxDistance) {
                close.computeIfAbsent(d, k -> new java.util.LinkedHashSet<>()).add(m.name());
            }
        }
        for (java.util.Set<String> names : close.values()) {
            for (String n : names) {
                if (out.size() == 3) {
                    return new ArrayList<>(out);
                }
                out.add(n);
            }
        }
        return new ArrayList<>(out);
    }

    /** Edit distance with adjacent transpositions ("Dce" → "Dec" is 1); above {@code max} returns max + 1. */
    static int editDistance(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return max + 1;
        }
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return Math.min(d[a.length()][b.length()], max + 1);
    }

    // ---------------------------------------------------------------- data

    /**
     * Reads the cross product of {@code vary} on top of {@code pov} (itself on top of the default
     * POV). Entries of {@code vary} that start with "{" are HFM member lists and are expanded first.
     */
    public Map<String, Object> cells(UserSession s, Map<String, String> pov, Map<String, List<String>> vary) {
        Metadata md = metadata(s);
        PovResolver r = md.resolver;
        LinkedHashMap<String, String> base = r.basePov(pov);

        List<String> prefixes = new ArrayList<>();
        List<List<String>> axes = new ArrayList<>();
        long total = 1;
        if (vary != null) {
            for (Map.Entry<String, List<String>> e : vary.entrySet()) {
                DimensionInfo d = r.resolve(e.getKey());
                if (prefixes.contains(d.prefix())) {
                    throw ApiException.badRequest("bad_request", "Dimension " + d.name() + " is listed twice in vary");
                }
                List<String> members = expandVary(s, d, e.getValue());
                if (members.isEmpty()) {
                    throw ApiException.badRequest("bad_request", "No members to vary for dimension " + d.name());
                }
                prefixes.add(d.prefix());
                axes.add(members);
                total *= members.size();
                if (total > config.maxCellsPerRequest()) {
                    throw ApiException.badRequest("too_many_cells", "Request needs more than "
                            + config.maxCellsPerRequest() + " cells; narrow the members or split the request.");
                }
            }
        }

        List<LinkedHashMap<String, String>> povMaps = new ArrayList<>();
        cross(base, prefixes, axes, 0, povMaps);
        List<String> povStrings = new ArrayList<>(povMaps.size());
        for (Map<String, String> m : povMaps) {
            povStrings.add(Pov.format(m));
        }

        List<CellResult> results = withSession(s, bs -> backend.getCells(bs, povStrings));
        if (results.size() != povStrings.size()) {
            throw new ApiException(502, "hfm_error", "HFM returned " + results.size() + " cells for "
                    + povStrings.size() + " requested");
        }

        List<Map<String, Object>> cells = new ArrayList<>();
        int stale = 0;
        int noData = 0;
        int errors = 0;
        for (int i = 0; i < results.size(); i++) {
            CellResult c = results.get(i);
            CalcStatus st = CalcStatus.decode(c.statusBits());
            Map<String, Object> cell = new LinkedHashMap<>();
            Map<String, String> byName = r.byName(povMaps.get(i));
            if (prefixes.isEmpty()) {
                cell.put("pov", byName);
            } else {
                // Only the varying dimensions; the shared part is returned once in "pov".
                Map<String, String> varying = new LinkedHashMap<>();
                for (String p : prefixes) {
                    DimensionInfo d = r.resolve(p);
                    varying.put(d.name(), povMaps.get(i).get(p));
                }
                cell.put("members", varying);
            }
            if (c.error() != null) {
                cell.put("value", null);
                cell.put("status", "ERROR");
                cell.put("error", c.error());
                errors++;
            } else {
                cell.put("value", st.noData() ? null : c.value());
                cell.put("status", st.code());
                if (!st.flags().isEmpty()) {
                    cell.put("flags", st.flags());
                }
            }
            if (config.includeRaw()) {
                cell.put("povString", povStrings.get(i));
                cell.put("statusBits", c.statusBits());
                cell.put("raw", c.raw());
            }
            if (c.error() == null && st.stale()) {
                stale++;
            }
            if (c.error() == null && st.noData()) {
                noData++;
            }
            cells.add(cell);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pov", r.byName(base));
        out.put("count", cells.size());
        out.put("staleCount", stale);
        out.put("noDataCount", noData);
        out.put("errorCount", errors);
        Map<String, String> legend = new java.util.TreeMap<>();
        for (Map<String, Object> cell : cells) {
            Object code = cell.get("status");
            if (code != null && STATUS_MEANINGS.containsKey(code.toString())) {
                legend.put(code.toString(), STATUS_MEANINGS.get(code.toString()));
            }
        }
        out.put("statusLegend", legend);
        out.put("cells", cells);
        return out;
    }

    // ---------------------------------------------------------------- internals

    /** The user's dimensions and default POV (cached). */
    PovResolver resolver(UserSession s) {
        return metadata(s).resolver;
    }

    List<String> expandVary(UserSession s, DimensionInfo d, List<String> entries) {
        if (entries == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String entry : entries) {
            String e = entry == null ? "" : entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            if (e.startsWith("{")) {
                for (MemberInfo m : expand(s, d, e)) {
                    out.add(m.name());
                }
            } else {
                out.add(e);
            }
            if (out.size() > config.maxCellsPerRequest()) {
                throw ApiException.badRequest("too_many_cells", "Member list for " + d.name() + " expands to more than "
                        + config.maxCellsPerRequest() + " members");
            }
        }
        return out;
    }

    private static void cross(LinkedHashMap<String, String> base, List<String> prefixes, List<List<String>> axes,
                              int depth, List<LinkedHashMap<String, String>> out) {
        if (depth == prefixes.size()) {
            out.add(new LinkedHashMap<>(base));
            return;
        }
        String p = prefixes.get(depth);
        String saved = base.get(p);
        for (String m : axes.get(depth)) {
            base.put(p, m);
            cross(base, prefixes, axes, depth + 1, out);
        }
        base.put(p, saved);
    }

    private Map<String, Object> memberPage(DimensionInfo dim, String expr, List<MemberInfo> members, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (MemberInfo m : members) {
            if (list.size() >= limit) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", m.name());
            if (m.description() != null && !m.description().isEmpty()) {
                item.put("description", m.description());
            }
            if (m.parent() != null && !m.parent().isEmpty()) {
                item.put("parent", m.parent());
            }
            if (config.includeRaw() && m.raw() != null) {
                item.put("raw", m.raw());
            }
            list.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", dim.name());
        if (expr != null) {
            out.put("expression", expr);
        }
        out.put("total", members.size());
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (MemberInfo m : members) {
            distinct.add(m.name().toUpperCase(Locale.ROOT));
        }
        if (distinct.size() != members.size()) {
            out.put("distinctMembers", distinct.size()); // shared members appear once per parent
        }
        out.put("returned", list.size());
        out.put("truncated", members.size() > list.size());
        out.put("members", list);
        return out;
    }

    /** {@code {Member.[Relation]}}: a member's system list. */
    private static final Pattern PARENT_LIST = Pattern.compile("\\{\\s*(.+?)\\s*\\.\\s*\\[(\\w+)]\\s*}");

    /**
     * Expands a member or member list. A member's relation list ({@code {Group.[Children]}}) is
     * worked out here from the cached hierarchy: HFM's {@code getMembers} ignores the member in
     * such a list and returns the whole dimension. Anything else goes to the backend.
     */
    List<MemberInfo> expand(UserSession s, DimensionInfo d, String expression) {
        String expr = expression.trim();
        Matcher m = PARENT_LIST.matcher(expr);
        if (m.matches()) {
            String rel = m.group(2).toLowerCase(Locale.ROOT);
            if (LOCAL_RELATIONS.contains(rel)) {
                return relation(hierarchy(s, d), d, m.group(1), rel);
            }
        }
        return withSession(s, bs -> backend.expandMembers(bs, d.name(), expr));
    }

    private static final java.util.Set<String> LOCAL_RELATIONS = new java.util.HashSet<>(Arrays.asList(
            "member", "children", "descendants", "base", "parents", "ancestors"));

    static List<MemberInfo> relation(List<MemberInfo> hierarchy, DimensionInfo d, String member, String rel) {
        Map<String, MemberInfo> byName = new LinkedHashMap<>();
        Map<String, List<MemberInfo>> children = new LinkedHashMap<>();
        Map<String, List<String>> parents = new LinkedHashMap<>();
        for (MemberInfo m : hierarchy) {
            String key = m.name().toUpperCase(Locale.ROOT);
            byName.putIfAbsent(key, m);
            String parent = m.parent();
            if (parent != null && !parent.trim().isEmpty()) {
                String pkey = parent.toUpperCase(Locale.ROOT);
                List<MemberInfo> kids = children.computeIfAbsent(pkey, k -> new ArrayList<>());
                if (kids.stream().noneMatch(k -> k.name().equalsIgnoreCase(m.name()))) {
                    kids.add(m);
                }
                List<String> ps = parents.computeIfAbsent(key, k -> new ArrayList<>());
                if (!ps.contains(pkey)) {
                    ps.add(pkey);
                }
            }
        }
        // Entity names may come parent-qualified (GROUP.UK01), as HFM writes them.
        MemberInfo self = byName.get(member.substring(member.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT));
        if (self == null) {
            throw ApiException.badRequest("unknown_member",
                    "'" + member + "' is not a member of " + d.name() + " (or you have no access to it)");
        }
        String key = self.name().toUpperCase(Locale.ROOT);
        List<MemberInfo> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        switch (rel) {
            case "member":
                out.add(self);
                break;
            case "children":
                out.addAll(children.getOrDefault(key, new ArrayList<>()));
                break;
            case "descendants":
            case "base":
                boolean baseOnly = "base".equals(rel);
                if (baseOnly && !children.containsKey(key)) {
                    out.add(self); // a base member is its own base
                    break;
                }
                java.util.Deque<MemberInfo> stack = new java.util.ArrayDeque<>();
                List<MemberInfo> top = children.getOrDefault(key, new ArrayList<>());
                for (int i = top.size() - 1; i >= 0; i--) {
                    stack.push(top.get(i));
                }
                while (!stack.isEmpty()) {
                    MemberInfo m = stack.pop();
                    String k = m.name().toUpperCase(Locale.ROOT);
                    List<MemberInfo> kids = children.get(k);
                    if ((!baseOnly || kids == null) && seen.add(k)) {
                        out.add(m);
                    }
                    if (kids != null) {
                        for (int i = kids.size() - 1; i >= 0; i--) {
                            stack.push(kids.get(i));
                        }
                    }
                }
                break;
            default: // parents, ancestors
                java.util.Deque<String> queue = new java.util.ArrayDeque<>(parents.getOrDefault(key, new ArrayList<>()));
                while (!queue.isEmpty()) {
                    String p = queue.poll();
                    MemberInfo pm = byName.get(p);
                    if (pm == null || !seen.add(p)) {
                        continue;
                    }
                    out.add(pm);
                    if ("ancestors".equals(rel)) {
                        queue.addAll(parents.getOrDefault(p, new ArrayList<>()));
                    }
                }
        }
        return out;
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return 200;
        }
        return Math.min(limit, config.maxMembersPerRequest());
    }

    // Metadata is cached per session because HFM security decides what each user can see.
    private static final class Metadata {
        final long loadedAt;
        final PovResolver resolver;
        final Map<String, List<MemberInfo>> hierarchies = new ConcurrentHashMap<>();

        Metadata(PovResolver resolver) {
            this.loadedAt = System.currentTimeMillis();
            this.resolver = resolver;
        }
    }

    private Metadata metadata(UserSession s) {
        Object cached = s.metadataCache();
        long ttl = config.metadataCacheTtlMinutes() * 60_000L;
        if (cached instanceof Metadata && System.currentTimeMillis() - ((Metadata) cached).loadedAt < ttl) {
            return (Metadata) cached;
        }
        List<DimensionInfo> dims = withSession(s, backend::getDimensions);
        Metadata md = new Metadata(new PovResolver(dims));
        s.metadataCache(md);
        return md;
    }

    private List<MemberInfo> hierarchy(UserSession s, DimensionInfo d) {
        Metadata md = metadata(s);
        List<MemberInfo> h = md.hierarchies.get(d.prefix());
        if (h == null) {
            h = withSession(s, bs -> backend.expandMembers(bs, d.name(), "{[Hierarchy]}"));
            md.hierarchies.put(d.prefix(), h);
        }
        return h;
    }

    @FunctionalInterface
    interface BackendCall<T> {
        T apply(BackendSession bs) throws BackendException;
    }

    /**
     * Runs {@code call} on the user's HFM session, serialized per session. If HFM says the session
     * is gone, re-opens it once with the cached CSS token and retries.
     */
    <T> T withSession(UserSession s, BackendCall<T> call) {
        s.lock().lock();
        try {
            try {
                return call.apply(s.backendSession());
            } catch (BackendException e) {
                if (e.kind() != BackendException.Kind.SESSION_INVALID) {
                    throw e;
                }
                LOG.info("HFM session invalid for " + s.userName() + "; re-opening");
                backend.closeSession(s.backendSession());
                s.replaceBackendSession(backend.openSession(s.auth(), s.application()));
                return call.apply(s.backendSession());
            }
        } catch (BackendException e) {
            if (e.kind() == BackendException.Kind.SESSION_INVALID || e.kind() == BackendException.Kind.AUTH_EXPIRED) {
                sessions.remove(s); // the lock is reentrant, so remove() can take it too
                throw new ApiException(401, "session_expired", "HFM session expired; log in again.");
            }
            throw toApi(e);
        } finally {
            s.lock().unlock();
        }
    }

    private static ApiException toApi(BackendException e) {
        switch (e.kind()) {
            case AUTH_FAILED:
                return new ApiException(401, "auth_failed", "Invalid username or password.");
            case AUTH_EXPIRED:
            case SESSION_INVALID:
                return new ApiException(401, "session_expired", "HFM session expired; log in again.");
            case BAD_REQUEST:
                return new ApiException(400, "hfm_rejected", e.getMessage());
            default:
                LOG.log(Level.WARNING, "HFM call failed", e);
                return new ApiException(502, "hfm_error", "HFM error: " + e.getMessage());
        }
    }
}
