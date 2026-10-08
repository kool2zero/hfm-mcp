package com.hfmmcp.daemon.backend.oracle;

import com.hfmmcp.daemon.DaemonConfig;
import com.hfmmcp.daemon.backend.AuthResult;
import com.hfmmcp.daemon.backend.BackendException;
import com.hfmmcp.daemon.backend.BackendException.Kind;
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
import com.hfmmcp.daemon.http.Json;
import com.hfmmcp.daemon.pov.Pov;
import com.hfmmcp.daemon.util.FileUnpacker;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import oracle.epm.fm.common.datatype.transport.COPYDATAMODE;
import oracle.epm.fm.common.datatype.transport.CellDataAndStatusInfo;
import oracle.epm.fm.common.datatype.transport.CopyDataOptions;
import oracle.epm.fm.common.datatype.transport.DATA_EXTRACT_TYPE_FLAG;
import oracle.epm.fm.common.datatype.transport.DATA_LINEITEM_OPTION;
import oracle.epm.fm.common.datatype.transport.DATALOAD_DUPLICATE_HANDLING;
import oracle.epm.fm.common.datatype.transport.DATALOAD_FILE_FORMAT;
import oracle.epm.fm.common.datatype.transport.DATA_PUSH_OPTION;
import oracle.epm.fm.common.datatype.transport.DataLoadOptions;
import oracle.epm.fm.common.datatype.transport.LOAD_MODE;
import oracle.epm.fm.common.datatype.transport.DataExtractOptions;
import oracle.epm.fm.common.datatype.transport.DIMENSIONTYPE;
import oracle.epm.fm.common.datatype.transport.Dimension;
import oracle.epm.fm.common.datatype.transport.Member;
import oracle.epm.fm.common.datatype.transport.MetadataFilter;
import oracle.epm.fm.common.datatype.transport.MetadataViewOption;
import oracle.epm.fm.common.datatype.transport.PMTaskOptions;
import oracle.epm.fm.common.datatype.transport.PROCESS_FLOW_ACTION;
import oracle.epm.fm.common.datatype.transport.PROCESS_FLOW_STATE;
import oracle.epm.fm.common.datatype.transport.ProcessFlowHistoryItem;
import oracle.epm.fm.common.datatype.transport.ProcessFlowInfo;
import oracle.epm.fm.common.datatype.transport.RunningTaskProgress;
import oracle.epm.fm.common.datatype.transport.ServerPMTaskInfo;
import oracle.epm.fm.common.datatype.transport.ServerTaskInfo;
import oracle.epm.fm.common.datatype.transport.USERACTIVITYSTATUS;
import oracle.epm.fm.common.datatype.transport.WEBOMDATAGRIDTASKMASKENUM;
import oracle.epm.fm.common.datatype.transport.RecordSetRange;
import oracle.epm.fm.common.datatype.transport.SessionInfo;
import oracle.epm.fm.common.exception.ErrorCodeConsts;
import oracle.epm.fm.common.exception.HFMException;
import oracle.epm.fm.common.exception.HResultConsts;
import oracle.epm.fm.domainobject.administration.AdministrationOM;
import oracle.epm.fm.domainobject.application.SessionOM;
import oracle.epm.fm.domainobject.data.DataOM;
import oracle.epm.fm.domainobject.data.manage.ManageDataOM;
import oracle.epm.fm.domainobject.loadextract.LoadExtractOM;
import oracle.epm.fm.domainobject.metadata.MetadataOM;
import oracle.epm.fm.domainobject.security.Security;
import org.apache.thrift.TBase;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TSimpleJSONProtocol;

/**
 * {@link HfmBackend} on the HFM 11.2 Java API ({@code fm-web-objectmodel.jar} and its dependencies),
 * following the published 11.2 Javadoc ("Java API Reference for Oracle Hyperion Financial
 * Management").
 *
 * <p>Authentication goes through Shared Services ({@link Security}, i.e. CSS), which checks the
 * password against whichever provider owns the user — LDAP for LDAP users. Only the resulting SSO
 * token is kept.
 */
public final class OracleHfmBackend implements HfmBackend {
    private static final Logger LOG = Logger.getLogger(OracleHfmBackend.class.getName());

    /** Wrong credentials. 65546/65556 come from {@code Security.authenticateUser}. */
    private static final Set<String> AUTH_FAILED_CODES = new HashSet<>(Arrays.asList(
            ErrorCodeConsts.CANNOT_AUTHENTICATE_USER_ERROR, "EPMHFM-65546", "EPMHFM-65556"));
    /** The CSS token is no longer good: the user must log in again. */
    private static final Set<String> AUTH_EXPIRED_CODES = new HashSet<>(Arrays.asList(
            ErrorCodeConsts.INVALID_SSO_TOKEN_ERROR, ErrorCodeConsts.CSS_TOKEN_NOT_AVAILABLE_ERROR,
            ErrorCodeConsts.USER_NOT_AUTHENTICATED_ERROR));
    private static final Set<Integer> AUTH_EXPIRED_HRESULTS = new HashSet<>(Arrays.asList(
            HResultConsts.E_HFM_INVALID_TOKEN));
    /** The HFM session is gone but the token may still be good: re-open the session. */
    private static final Set<String> SESSION_INVALID_CODES = new HashSet<>(Arrays.asList(
            ErrorCodeConsts.INVALID_SESSION_INFO));
    private static final Set<Integer> SESSION_INVALID_HRESULTS = new HashSet<>(Arrays.asList(
            HResultConsts.E_HFM_INVALID_SESSION,
            HResultConsts.E_XFM_INVALID_SESSION_ID,
            HResultConsts.E_XFM_SESSION_TERMINATED,
            HResultConsts.E_HFM_SESSION_NOT_SET,
            HResultConsts.E_HFM_WEBSESSION_DOESNOT_EXIST,
            HResultConsts.E_HFM_DME_INVALID_SESSION_ID,
            HResultConsts.E_HOPE_SYSTEM_CHANGED_RELOGIN,
            HResultConsts.S_HFM_HSVDATASOURCEIMPL_USER_SESSION_REVOKED));
    /** The request names something HFM does not have. */
    private static final Set<String> BAD_REQUEST_CODES = new HashSet<>(Arrays.asList(
            ErrorCodeConsts.INVALID_MEMBER_ERROR, ErrorCodeConsts.MEMBER_NOT_FOUND,
            ErrorCodeConsts.ERROR_INVALID_MEMBER_SELECTED_FOR_DIM));
    private static final Set<Integer> BAD_REQUEST_HRESULTS = new HashSet<>(Arrays.asList(
            HResultConsts.E_HFM_INVALID_POV,
            HResultConsts.E_HFM_INVALID_MEMBER_FOR_DIMENSION,
            HResultConsts.E_XFM_INVALID_MEMBER_NAME,
            HResultConsts.E_XFM_INVALID_MEMBER_OF_DIMENSION,
            HResultConsts.E_HOPE_MEMBER_NOT_FOUND,
            HResultConsts.E_HFM_DSMETADATA_SQL_MEMBER_NOT_FOUND,
            HResultConsts.E_GRID_INVALID_POV_SELECTION));
    /** Safety net for session errors reported without one of the codes above. */
    private static final Pattern SESSION_TEXT = Pattern.compile(
            "(?i)session.{0,40}(invalid|expired|terminated|revoked|does not exist)"
                    + "|(invalid|expired|terminated|revoked).{0,40}session");

    /** POV prefix → dimension type, for the fixed dimensions. */
    private static final Map<String, DIMENSIONTYPE> STANDARD = new LinkedHashMap<>();

    static {
        STANDARD.put("S", DIMENSIONTYPE.SCENARIO);
        STANDARD.put("Y", DIMENSIONTYPE.YEAR);
        STANDARD.put("P", DIMENSIONTYPE.PERIOD);
        STANDARD.put("W", DIMENSIONTYPE.VIEW);
        STANDARD.put("V", DIMENSIONTYPE.VALUE);
        STANDARD.put("E", DIMENSIONTYPE.ENTITY);
        STANDARD.put("A", DIMENSIONTYPE.ACCOUNT);
        STANDARD.put("I", DIMENSIONTYPE.ICP);
    }

    /** A member list expression such as {@code {Group.[Children]}}, {@code {[Hierarchy]}} or {@code {MyList}}. */
    private static final Pattern MEMBER_LIST = Pattern.compile("\\{.+}");

    /** {@code {[Hierarchy]}}: a system list over the whole dimension, sent bare as {@code [Hierarchy]}. */
    private static final Pattern BARE_SYSTEM_LIST = Pattern.compile("\\{(\\[[A-Za-z]+])}");

    /** HFM's wording when an HFM server in the cluster is momentarily unreachable. */
    private static final Pattern UNAVAILABLE = Pattern.compile(
            "(?i)is unavailable|connection could not be established");

    private final String cluster;
    private final Locale locale;
    private final int cellBatchSize;
    private final boolean includeRaw;
    private final int connectionRetries;

    public OracleHfmBackend(DaemonConfig config) {
        this.cluster = config.get("hfm.cluster", null);
        String[] loc = config.get("hfm.locale", "en_US").split("_");
        this.locale = loc.length > 1 ? new Locale(loc[0], loc[1]) : new Locale(loc[0]);
        this.cellBatchSize = config.getInt("oracle.cellBatchSize", 500);
        this.includeRaw = config.includeRaw();
        this.connectionRetries = config.getInt("oracle.connectionRetries", 3);
        // A prebuilt backend carries the API it was compiled against; refuse to start on a mismatch
        // rather than fail later on the first call that touches it.
        List<String> mismatches = ApiCheck.verify();
        if (mismatches != null && !mismatches.isEmpty()) {
            throw new IllegalStateException("The prebuilt Oracle backend does not match this server's HFM API: "
                    + mismatches + ". Rebuild it here with scripts\\build-oracle-backend.ps1.");
        }
    }

    @FunctionalInterface
    private interface HfmCall<T> {
        T call() throws HFMException, BackendException;
    }

    /**
     * Runs a read, retrying with a growing wait while HFM reports a server as unavailable (a
     * cluster member restarting or a dropped connection). Reads are idempotent, so this is safe.
     */
    private <T> T withRetry(HfmCall<T> call) throws HFMException, BackendException {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.call();
            } catch (HFMException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (attempt > connectionRetries || !UNAVAILABLE.matcher(msg).find()) {
                    throw e;
                }
                long waitMillis = 2000L * attempt;
                LOG.info("HFM server unavailable; retry " + attempt + "/" + connectionRetries + " in "
                        + waitMillis / 1000 + "s");
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static final class OracleSession implements BackendSession {
        final SessionInfo info;
        final String ssoToken;
        final String cluster;
        /** Custom dimension name (lower case) → "CustomN", an alternative name HFM accepts for member queries. */
        final Map<String, String> customAliases = new java.util.concurrent.ConcurrentHashMap<>();

        OracleSession(SessionInfo info, String ssoToken, String cluster) {
            this.info = info;
            this.ssoToken = ssoToken;
            this.cluster = cluster;
        }
    }

    // ---------------------------------------------------------------- sessions

    @Override
    public AuthResult authenticate(String userName, char[] password) throws BackendException {
        try {
            Security security = new Security();
            security.authenticateUser(userName, new String(password), null);
            String token = security.getSsoToken();
            if (token == null || token.isEmpty()) {
                throw new BackendException(Kind.AUTH_FAILED, "CSS returned no token");
            }
            String canonical = security.getUserName();
            return new AuthResult(canonical == null || canonical.isEmpty() ? userName : canonical, token);
        } catch (HFMException e) {
            if (AUTH_FAILED_CODES.contains(e.getErrorCode())) {
                throw new BackendException(Kind.AUTH_FAILED, "Authentication failed", e);
            }
            throw classify(e, "authenticate");
        }
    }

    @Override
    public BackendSession openSession(AuthResult auth, String application) throws BackendException {
        try {
            SessionOM sessionOM = new SessionOM();
            String clusterName = cluster != null ? cluster : sessionOM.getAvailableCluster(auth.ssoToken());
            SessionInfo info = sessionOM.createSession(auth.ssoToken(), locale, clusterName, application);
            if (info == null) {
                throw new BackendException(Kind.HFM_ERROR, "HFM returned no session for " + application);
            }
            return new OracleSession(info, auth.ssoToken(), clusterName);
        } catch (HFMException e) {
            if (ErrorCodeConsts.ERROR_CREATING_SESSION_FOR_APPLICATION.equals(e.getErrorCode())) {
                throw new BackendException(Kind.HFM_ERROR, "Cannot open application '" + application
                        + "' (does it exist, and is the user provisioned for it?)", e);
            }
            throw classify(e, "open session on " + application);
        }
    }

    @Override
    public void closeSession(BackendSession session) {
        if (!(session instanceof OracleSession)) {
            return;
        }
        try {
            new SessionOM().closeSession(((OracleSession) session).info);
        } catch (Exception e) {
            LOG.log(Level.FINE, "closeSession failed (session may already be gone)", e);
        }
    }

    // ---------------------------------------------------------------- metadata

    /**
     * Dimensions in default-POV order. Each POV prefix is matched to a {@link Dimension} by its
     * {@code shortName}, then by type for the fixed dimensions, then to the remaining custom
     * dimensions in order.
     */
    @Override
    public List<DimensionInfo> getDimensions(BackendSession session) throws BackendException {
        SessionInfo info = info(session);
        try {
            MetadataOM metadata = new MetadataOM(info);
            Map<String, String> defaultPov = Pov.parse(withRetry(metadata::getDefaultPOV));
            List<Dimension> all = withRetry(() -> metadata.getDimensions(Collections.singletonList(DIMENSIONTYPE.ALL)));
            List<Dimension> unused = all == null ? new ArrayList<Dimension>() : new ArrayList<>(all);

            List<DimensionInfo> out = new ArrayList<>();
            int customs = 0;
            for (Map.Entry<String, String> e : defaultPov.entrySet()) {
                String prefix = e.getKey();
                Dimension match = null;
                for (Dimension d : unused) {
                    if (prefix.equalsIgnoreCase(d.getShortName())) {
                        match = d;
                        break;
                    }
                }
                DIMENSIONTYPE wanted = STANDARD.get(prefix.toUpperCase(Locale.ROOT));
                for (int i = 0; match == null && i < unused.size(); i++) {
                    Dimension d = unused.get(i);
                    if (wanted != null ? d.getType() == wanted : d.getType() == DIMENSIONTYPE.CUSTOM) {
                        match = d;
                    }
                }
                if (match != null) {
                    unused.remove(match);
                }
                String name = match != null && match.getName() != null ? match.getName() : prefix;
                if (wanted == null) {
                    customs++;
                    String alias = "Custom" + (prefix.matches("(?i)C\\d+") ? prefix.substring(1) : String.valueOf(customs));
                    if (!alias.equalsIgnoreCase(name)) {
                        oracle(session).customAliases.put(name.toLowerCase(Locale.ROOT), alias);
                    }
                }
                out.add(new DimensionInfo(prefix, name, e.getValue()));
            }
            return out;
        } catch (HFMException e) {
            throw classify(e, "read dimensions");
        } catch (IllegalArgumentException e) {
            throw new BackendException(Kind.HFM_ERROR, "Unexpected default POV format: " + e.getMessage(), e);
        }
    }

    /**
     * Member lists go through {@link MetadataOM#getMembers}: a dimension-wide system list bare
     * ({@code [Hierarchy]}), a named list as written. HFM ignores the member in a parent's list
     * ({@code {Group.[Children]}} returns the whole hierarchy), so HfmService expands those itself. A plain member name goes through
     * {@link MetadataOM#validateMembers}, which is also the fallback if HFM rejects the list.
     * Custom dimensions are retried under their {@code CustomN} name if their own name finds nothing.
     */
    @Override
    public List<MemberInfo> expandMembers(BackendSession session, String dimensionName, String expression)
            throws BackendException {
        OracleSession os = oracle(session);
        List<String> names = new ArrayList<>();
        names.add(dimensionName);
        String alias = os.customAliases.get(dimensionName.toLowerCase(Locale.ROOT));
        if (alias != null) {
            names.add(alias);
        }
        BackendException failure = null;
        for (String dim : names) {
            try {
                List<MemberInfo> found = expandAs(os.info, dim, expression.trim());
                if (!found.isEmpty()) {
                    return found;
                }
            } catch (BackendException e) {
                if (e.kind() == Kind.SESSION_INVALID || e.kind() == Kind.AUTH_EXPIRED) {
                    throw e;
                }
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return new ArrayList<>();
    }

    private List<MemberInfo> expandAs(SessionInfo info, String dimensionName, String expr) throws BackendException {
        if (MEMBER_LIST.matcher(expr).matches()) {
            Matcher bare = BARE_SYSTEM_LIST.matcher(expr);
            String listName = bare.matches() ? bare.group(1) : expr;
            try {
                return withRetry(() -> byList(info, dimensionName, listName));
            } catch (HFMException e) {
                BackendException be = classify(e, "list " + expr + " in " + dimensionName);
                if (be.kind() == Kind.SESSION_INVALID || be.kind() == Kind.AUTH_EXPIRED) {
                    throw be;
                }
                LOG.log(Level.FINE, "getMembers rejected " + expr + "; trying validateMembers", e);
            }
        }
        try {
            return withRetry(() -> byValidate(info, dimensionName, expr));
        } catch (HFMException e) {
            throw classify(e, "expand " + expr + " in " + dimensionName);
        }
    }

    private List<MemberInfo> byList(SessionInfo info, String dimensionName, String listExpression)
            throws HFMException, BackendException {
        MetadataFilter filter = new MetadataFilter();
        filter.setDimensionName(dimensionName);
        filter.setListName(listExpression);
        MetadataViewOption view = new MetadataViewOption();
        view.setIncludeParent(true);
        RecordSetRange all = new RecordSetRange();
        all.setStartIndex(0);
        all.setEndIndex(-1); // every member, not one page
        view.setResultSetRange(all);
        return toMembers(new MetadataOM(info).getMembers(filter, view));
    }

    private List<MemberInfo> byValidate(SessionInfo info, String dimensionName, String expression)
            throws HFMException, BackendException {
        Map<String, List<String>> request = new LinkedHashMap<>();
        request.put(dimensionName, Collections.singletonList(expression));
        Map<String, List<Member>> result = new MetadataOM(info).validateMembers(request, false);
        if (result != null) {
            for (Map.Entry<String, List<Member>> e : result.entrySet()) {
                if (e.getKey().equalsIgnoreCase(dimensionName)) {
                    return toMembers(e.getValue());
                }
            }
        }
        return new ArrayList<>();
    }

    private List<MemberInfo> toMembers(List<Member> members) throws BackendException {
        List<MemberInfo> out = new ArrayList<>();
        if (members == null) {
            return out;
        }
        for (Member m : members) {
            // [None] is kept: unlike for process units, it is a valid POV member (C1#[None]).
            String name = m.getName() != null ? m.getName() : m.getId();
            if (name == null) {
                continue;
            }
            out.add(MemberInfo.fromHfm(name, m.getDescription(), m.getParentName(), includeRaw ? toMap(m) : null));
        }
        return out;
    }

    // ---------------------------------------------------------------- data

    /**
     * {@link DataOM#getCellsDataAndStatus}: each result carries the stored value as text
     * ({@code rawData}), the cell status bits ({@code cellStatus}) and, when that one POV could
     * not be read, {@code errorDetail}.
     */
    @Override
    public List<CellResult> getCells(BackendSession session, List<String> povs) throws BackendException {
        SessionInfo info = info(session);
        List<CellResult> out = new ArrayList<>(povs.size());
        try {
            DataOM data = new DataOM(info);
            for (int from = 0; from < povs.size(); from += cellBatchSize) {
                List<String> batch = povs.subList(from, Math.min(povs.size(), from + cellBatchSize));
                List<String> request = new ArrayList<>(batch);
                List<CellDataAndStatusInfo> cells = withRetry(() -> data.getCellsDataAndStatus(request));
                if (cells == null || cells.size() != batch.size()) {
                    throw new BackendException(Kind.HFM_ERROR, "HFM returned "
                            + (cells == null ? 0 : cells.size()) + " cells for " + batch.size() + " POVs");
                }
                for (int i = 0; i < batch.size(); i++) {
                    out.add(toCell(batch.get(i), cells.get(i)));
                }
            }
            return out;
        } catch (HFMException e) {
            throw classify(e, "read cells");
        }
    }

    private CellResult toCell(String pov, CellDataAndStatusInfo c) throws BackendException {
        Map<String, Object> raw = includeRaw ? toMap(c) : null;
        String error = c.getErrorDetail();
        if (error != null && !error.trim().isEmpty()) {
            return new CellResult(pov, 0, c.getCellStatus(), error.trim(), raw);
        }
        String text = c.getRawData() == null ? "" : c.getRawData().trim();
        double value = 0;
        if (!text.isEmpty()) {
            try {
                value = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return new CellResult(pov, 0, c.getCellStatus(), "Unreadable cell value '" + text + "'", raw);
            }
        }
        return new CellResult(pov, value, c.getCellStatus(), null, raw);
    }

    // ---------------------------------------------------------------- process control

    @Override
    public ProcessState getProcessState(BackendSession session, String unitPov, int phase) throws BackendException {
        SessionInfo info = info(session);
        try {
            DataOM data = new DataOM(info);
            ProcessFlowInfo pf = withRetry(() -> data.getProcessFlowInformationByPhase(unitPov, phase));
            if (pf == null) {
                throw new BackendException(Kind.HFM_ERROR, "HFM returned no process information for " + unitPov);
            }
            ProcessFlowHistoryItem last = null;
            if (pf.getHistory() != null) {
                for (ProcessFlowHistoryItem h : pf.getHistory()) {
                    if (last == null || h.getTime() >= last.getTime()) {
                        last = h;
                    }
                }
            }
            return last == null ? new ProcessState(pf.getCurrentState(), null, null, 0, null)
                    : new ProcessState(pf.getCurrentState(), last.getAction(), last.getUser(), last.getTime(),
                            last.getComment());
        } catch (HFMException e) {
            throw classify(e, "read process state of " + unitPov);
        }
    }

    /** One phase per call, promotion level from the action. */
    @Override
    public void runProcessAction(BackendSession session, ProcessAction action, String unitPov, int phase,
                                 int promotionLevel, boolean includeDescendants, String comment)
            throws BackendException {
        SessionInfo info = info(session);
        PROCESS_FLOW_ACTION pfAction;
        PROCESS_FLOW_STATE level;
        switch (action) {
            case START:
                pfAction = PROCESS_FLOW_ACTION.PROCESS_FLOW_ACTION_START;
                level = PROCESS_FLOW_STATE.PROCESS_FLOW_STATE_FIRST_PASS;
                break;
            case REJECT:
                pfAction = PROCESS_FLOW_ACTION.PROCESS_FLOW_ACTION_REJECT;
                level = PROCESS_FLOW_STATE.PROCESS_FLOW_STATE_FIRST_PASS;
                break;
            case SUBMIT:
                pfAction = PROCESS_FLOW_ACTION.PROCESS_FLOW_ACTION_SUBMIT;
                level = PROCESS_FLOW_STATE.PROCESS_FLOW_STATE_SUBMITTED;
                break;
            case PROMOTE:
                pfAction = PROCESS_FLOW_ACTION.PROCESS_FLOW_ACTION_PROMOTE;
                level = PROCESS_FLOW_STATE.valueOf("PROCESS_FLOW_STATE_REVIEW_" + promotionLevel);
                break;
            case APPROVE:
                pfAction = PROCESS_FLOW_ACTION.PROCESS_FLOW_ACTION_APPROVE;
                level = PROCESS_FLOW_STATE.PROCESS_FLOW_STATE_APPROVED;
                break;
            case PUBLISH:
                pfAction = PROCESS_FLOW_ACTION.PROCESS_FLOW_ACTION_PUBLISH;
                level = PROCESS_FLOW_STATE.PROCESS_FLOW_STATE_PUBLISHED;
                break;
            default:
                throw new BackendException(Kind.BAD_REQUEST, "Unsupported action " + action);
        }
        PMTaskOptions options = new PMTaskOptions();
        options.setPromotionLevel(level.getValue());
        options.setSelectedPhases(new ArrayList<>(Collections.singletonList(phase)));
        options.setIncludeDescendants(includeDescendants);
        if (comment != null && !comment.isEmpty()) {
            options.setComments(comment);
        }
        try {
            DataOM data = new DataOM(info);
            List<String> povs = new ArrayList<>(Collections.singletonList(unitPov));
            ServerPMTaskInfo result = withRetry(() -> data.executeServerPMTaskForPovs(pfAction, povs, options));
            String error = result == null ? null : result.getErrorMessage();
            if (error != null && !error.trim().isEmpty()) {
                throw new BackendException(Kind.BAD_REQUEST, error.trim());
            }
        } catch (HFMException e) {
            BackendException be = classify(e, action + " " + unitPov + " phase " + phase);
            // A refused action (wrong level, no rights) is the request's problem, not HFM's.
            throw be.kind() == Kind.HFM_ERROR ? new BackendException(Kind.BAD_REQUEST, e.getMessage(), e) : be;
        }
    }

    @Override
    public List<Integer> startServerTask(BackendSession session, ServerTask task, List<String> povs)
            throws BackendException {
        SessionInfo info = info(session);
        WEBOMDATAGRIDTASKMASKENUM mask;
        switch (task) {
            case CONSOLIDATE:
                mask = WEBOMDATAGRIDTASKMASKENUM.WEBOM_DATAGRID_TASK_CONSOLIDATE;
                break;
            case CONSOLIDATE_ALL_WITH_DATA:
                mask = WEBOMDATAGRIDTASKMASKENUM.WEBOM_DATAGRID_TASK_CONSOLIDATEALLWITHDATA;
                break;
            case CONSOLIDATE_ALL:
                mask = WEBOMDATAGRIDTASKMASKENUM.WEBOM_DATAGRID_TASK_CONSOLIDATEALL;
                break;
            case CALCULATE:
                mask = WEBOMDATAGRIDTASKMASKENUM.WEBOM_DATAGRID_TASK_CALCULATE;
                break;
            case FORCE_CALCULATE:
                mask = WEBOMDATAGRIDTASKMASKENUM.WEBOM_DATAGRID_TASK_FORCECALCULATE;
                break;
            case TRANSLATE:
                mask = WEBOMDATAGRIDTASKMASKENUM.WEBOM_DATAGRID_TASK_TRANSLATE;
                break;
            case FORCE_TRANSLATE:
                mask = WEBOMDATAGRIDTASKMASKENUM.WEBOM_DATAGRID_TASK_FORCETRANSLATE;
                break;
            default:
                throw new BackendException(Kind.BAD_REQUEST, "Unsupported task " + task);
        }
        try {
            DataOM data = new DataOM(info);
            List<String> request = new ArrayList<>(povs);
            ServerTaskInfo result = withRetry(() -> data.executeServerTask(mask, request));
            List<Integer> ids = result == null || result.getTaskIDs() == null
                    ? new ArrayList<Integer>() : new ArrayList<>(result.getTaskIDs());
            String error = result == null ? null : result.getErrorMessage();
            if (error != null && !error.trim().isEmpty() && ids.isEmpty()) {
                throw new BackendException(Kind.BAD_REQUEST, error.trim());
            }
            // Only consolidations run as background tasks. Calculate and translate finish within
            // the call and return no task ids (only consolidations need polling).
            return ids;
        } catch (HFMException e) {
            throw classify(e, "start " + task);
        }
    }

    @Override
    public List<TaskProgress> getTaskProgress(BackendSession session, List<Integer> taskIds) throws BackendException {
        SessionInfo info = info(session);
        try {
            AdministrationOM admin = new AdministrationOM(info);
            List<Integer> request = new ArrayList<>(taskIds);
            List<RunningTaskProgress> progress = withRetry(() -> admin.getCurrentTaskProgress(request));
            Map<Integer, RunningTaskProgress> byId = new LinkedHashMap<>();
            if (progress != null) {
                for (RunningTaskProgress p : progress) {
                    byId.put(p.getTaskID(), p);
                }
            }
            List<TaskProgress> out = new ArrayList<>();
            for (Integer id : taskIds) {
                RunningTaskProgress p = byId.get(id);
                out.add(p == null
                        ? new TaskProgress(id, TaskProgress.Status.UNKNOWN, 0, "HFM no longer reports this task")
                        : new TaskProgress(id, status(p.getTaskStatus()), p.getPrecentCompleted(), p.getDescription()));
            }
            return out;
        } catch (HFMException e) {
            throw classify(e, "read task progress");
        }
    }

    private static TaskProgress.Status status(USERACTIVITYSTATUS s) {
        if (s == null) {
            return TaskProgress.Status.UNKNOWN;
        }
        switch (s) {
            case USERACTIVITYSTATUS_SCHEDULED_START:
                return TaskProgress.Status.SCHEDULED;
            case USERACTIVITYSTATUS_RUNNING:
            case USERACTIVITYSTATUS_STARTING:
            case USERACTIVITYSTATUS_PAUSED:
            case USERACTIVITYSTATUS_STOPPING:
            case USERACTIVITYSTATUS_NOT_RESPONDING:
                return TaskProgress.Status.RUNNING;
            case USERACTIVITYSTATUS_COMPLETED:
                return TaskProgress.Status.COMPLETED;
            case USERACTIVITYSTATUS_ABORTED:
                return TaskProgress.Status.ABORTED;
            case USERACTIVITYSTATUS_STOPPED:
            case USERACTIVITYSTATUS_SCHEDULED_STOP:
                return TaskProgress.Status.STOPPED;
            default:
                return TaskProgress.Status.UNKNOWN;
        }
    }

    // ---------------------------------------------------------------- data management

    /** EA engine, line-item detail, ';' flat file or STARSCHEMA_CREATE. */
    @Override
    public int startExtract(BackendSession session, ExtractRequest r) throws BackendException {
        OracleSession os = oracle(session);
        DataExtractOptions options = new DataExtractOptions();
        options.setIncludeData(true);
        options.setIncludeCalculatedData(r.includeCalculated);
        options.setIncludeDerivedData(r.includeDerived);
        options.setIncludeDynamicAccounts(r.includeDynamicAccounts);
        options.setLineItemOption(DATA_LINEITEM_OPTION.EA_LINEITEM_DETAIL);
        options.setTablePrefix(r.prefix);
        options.setMetadataSlice(r.slice);
        try {
            if (r.format == ExtractRequest.Format.FLATFILE) {
                options.setDelimiter(";");
                options.setExtractFormat(DATA_EXTRACT_TYPE_FLAG.EA_EXTRACT_TYPE_FLATFILE_NOHEADER);
            } else {
                Map<String, String> dsnInfo = withRetry(() -> new SessionOM().getDSNDetails(os.cluster, os.ssoToken, r.dsn));
                if (dsnInfo == null || dsnInfo.isEmpty()) {
                    throw new BackendException(Kind.BAD_REQUEST, "HFM has no details for DSN '" + r.dsn + "'");
                }
                options.setMapDbConnectInfo(dsnInfo);
                options.setDSN(r.dsn);
                options.setDatabaseOption(DATA_PUSH_OPTION.STARSCHEMA_CREATE);
                options.setExtractFormat(r.format == ExtractRequest.Format.METADATA
                        ? DATA_EXTRACT_TYPE_FLAG.EA_EXTRACT_TYPE_METADATA_ALL
                        : DATA_EXTRACT_TYPE_FLAG.EA_EXTRACT_TYPE_WAREHOUSE);
            }
            LoadExtractOM extract = new LoadExtractOM(os.info);
            return withRetry(() -> extract.extractData(options));
        } catch (HFMException e) {
            throw classify(e, "start extract of " + r.slice);
        }
    }

    @Override
    public List<Path> fetchExtractFile(BackendSession session, int taskId, Path targetDir) throws BackendException {
        SessionInfo info = info(session);
        try {
            AdministrationOM admin = new AdministrationOM(info);
            File file = withRetry(() -> admin.getRunningTaskFile(taskId));
            if (file == null) {
                throw new BackendException(Kind.HFM_ERROR, "HFM returned no file for task " + taskId);
            }
            return FileUnpacker.unpack(file.getAbsoluteFile().toPath(), targetDir);
        } catch (HFMException e) {
            throw classify(e, "download extract of task " + taskId);
        } catch (IOException e) {
            throw new BackendException(Kind.HFM_ERROR, "Cannot unpack extract of task " + taskId + ": " + e.getMessage(), e);
        }
    }

    /** Native format, no decimal/thousands characters, calculated data not loaded. */
    @Override
    public List<Integer> startLoad(BackendSession session, LoadRequest r) throws BackendException {
        SessionInfo info = info(session);
        DATALOAD_DUPLICATE_HANDLING duplicates;
        switch (r.mode) {
            case ACCUMULATE:
                duplicates = DATALOAD_DUPLICATE_HANDLING.DATALOAD_ACCUMULATE;
                break;
            case REPLACE:
                duplicates = DATALOAD_DUPLICATE_HANDLING.DATALOAD_REPLACE;
                break;
            case REPLACE_WITH_SECURITY:
                duplicates = DATALOAD_DUPLICATE_HANDLING.DATALOAD_REPLACEWITHSECURITY;
                break;
            default:
                duplicates = DATALOAD_DUPLICATE_HANDLING.DATALOAD_MERGE;
        }
        String path = r.file.toString();
        DataLoadOptions options = new DataLoadOptions();
        options.setAccumulateWithinFile(r.accumulateWithinFile);
        options.setAppendToLogFile(false);
        options.setContainSharesData(r.containsOwnershipData);
        options.setContainSubmissionPhaseData(false);
        options.setDecimalChar("");
        options.setThousandsChar("");
        options.setDelimiter(r.delimiter);
        options.setDuplicates(duplicates);
        options.setFileFormat(DATALOAD_FILE_FORMAT.DATALOAD_FILE_FORMAT_NATIVE);
        options.setLoadCalculated(false);
        options.setMode(r.scanOnly ? LOAD_MODE.SCAN : LOAD_MODE.LOAD);
        options.setUserFileName(path);
        try {
            LoadExtractOM load = new LoadExtractOM(info);
            List<Integer> ids = withRetry(() -> load.loadData(new ArrayList<>(Collections.singletonList(path)),
                    new ArrayList<>(Collections.singletonList(options))));
            if (ids == null || ids.isEmpty()) {
                throw new BackendException(Kind.HFM_ERROR, "HFM did not start the load of " + path);
            }
            return new ArrayList<>(ids);
        } catch (HFMException e) {
            throw classify(e, "load " + path);
        }
    }

    @Override
    public String fetchTaskLog(BackendSession session, int taskId) throws BackendException {
        SessionInfo info = info(session);
        try {
            AdministrationOM admin = new AdministrationOM(info);
            File log = withRetry(() -> admin.getRunningTaskLog(taskId));
            if (log == null || !log.isFile()) {
                return null;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(log.toPath());
            // HFM writes some logs as UTF-16 with a byte-order mark.
            if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                return new String(bytes, 2, bytes.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
            }
            if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                return new String(bytes, 2, bytes.length - 2, java.nio.charset.StandardCharsets.UTF_16BE);
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (HFMException e) {
            throw classify(e, "download log of task " + taskId);
        } catch (IOException e) {
            throw new BackendException(Kind.HFM_ERROR, "Cannot read log of task " + taskId + ": " + e.getMessage(), e);
        }
    }

    /** Synchronous; HFM returns the path of its copy log. */
    @Override
    public String copyData(BackendSession session, CopyRequest r) throws BackendException {
        SessionInfo info = info(session);
        CopyDataOptions options = new CopyDataOptions();
        options.setSSourceSlice(r.source);
        options.setSDestSlice(r.target);
        options.setSSliceEntitiesAndAccounts(r.entitiesAndAccounts);
        options.setSView(r.view);
        options.setDataCopyMode(COPYDATAMODE.valueOf(r.mode.name()));
        options.setBCopyData(true);
        options.setBCopyRatesAndSystemData(r.copyRatesAndSystemData);
        options.setBCopyDerivedData(r.copyDerivedData);
        options.setBCopyCellText(r.copyCellText);
        options.setBEnableDetailedLogging(true);
        options.setDScaleFactor(r.scale);
        try {
            ManageDataOM manage = new ManageDataOM(info);
            return withRetry(() -> manage.copyData(options));
        } catch (HFMException e) {
            throw classify(e, "copy " + r.source + " to " + r.target);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static OracleSession oracle(BackendSession session) throws BackendException {
        if (!(session instanceof OracleSession)) {
            throw new BackendException(Kind.SESSION_INVALID, "No HFM session");
        }
        return (OracleSession) session;
    }

    private static SessionInfo info(BackendSession session) throws BackendException {
        if (!(session instanceof OracleSession)) {
            throw new BackendException(Kind.SESSION_INVALID, "No HFM session");
        }
        return ((OracleSession) session).info;
    }

    private static BackendException classify(HFMException e, String action) {
        String code = e.getErrorCode();
        int hr = e.getHResult();
        String message = e.getMessage() == null ? "" : e.getMessage();
        Kind kind;
        if (AUTH_EXPIRED_CODES.contains(code) || AUTH_EXPIRED_HRESULTS.contains(hr)) {
            kind = Kind.AUTH_EXPIRED;
        } else if (SESSION_INVALID_CODES.contains(code) || SESSION_INVALID_HRESULTS.contains(hr)
                || SESSION_TEXT.matcher(message).find()) {
            kind = Kind.SESSION_INVALID;
        } else if (BAD_REQUEST_CODES.contains(code) || BAD_REQUEST_HRESULTS.contains(hr)) {
            kind = Kind.BAD_REQUEST;
        } else {
            kind = Kind.HFM_ERROR;
        }
        String detail = (code == null ? "" : code + " ") + (hr == 0 ? "" : "(hr " + hr + ") ") + message;
        if (kind == Kind.HFM_ERROR) {
            LOG.log(Level.WARNING, "HFM failed to " + action + ": " + detail, e);
        }
        return new BackendException(kind, "Failed to " + action + ": " + detail.trim(), e);
    }

    /** Thrift struct → map of all its fields, for {@code debug.includeRaw}. */
    @SuppressWarnings("rawtypes")
    private static Map<String, Object> toMap(TBase struct) throws BackendException {
        try {
            // TSerializer is not thread-safe, so one per call. Its constructor declares a checked
            // exception in newer Thrift versions only; catching Exception compiles against both.
            TSerializer serializer = new TSerializer(new TSimpleJSONProtocol.Factory());
            return Json.parseObject(serializer.toString(struct));
        } catch (Exception e) {
            throw new BackendException(Kind.HFM_ERROR, "Cannot read HFM response object: " + e.getMessage(), e);
        }
    }
}
