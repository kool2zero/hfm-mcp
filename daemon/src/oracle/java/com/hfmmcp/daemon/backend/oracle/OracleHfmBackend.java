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
import com.hfmmcp.daemon.backend.JournalAction;
import com.hfmmcp.daemon.backend.JournalDetail;
import com.hfmmcp.daemon.backend.JournalError;
import com.hfmmcp.daemon.backend.JournalInfo;
import com.hfmmcp.daemon.backend.JournalQuery;
import com.hfmmcp.daemon.backend.LoadRequest;
import com.hfmmcp.daemon.backend.MemberInfo;
import com.hfmmcp.daemon.backend.ProcessAction;
import com.hfmmcp.daemon.backend.ProcessState;
import com.hfmmcp.daemon.backend.ReportDetail;
import com.hfmmcp.daemon.backend.ReportInfo;
import com.hfmmcp.daemon.backend.ReportQuery;
import com.hfmmcp.daemon.backend.ReportRunRequest;
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
import oracle.epm.fm.common.datatype.transport.DEBIT_CREDIT_UNIT;
import oracle.epm.fm.common.datatype.transport.JOURNAL_ACTION;
import oracle.epm.fm.common.datatype.transport.JOURNAL_STATUS;
import oracle.epm.fm.common.datatype.transport.JOURNAL_TYPE;
import oracle.epm.fm.common.datatype.transport.Journal;
import oracle.epm.fm.common.datatype.transport.JournalDimension;
import oracle.epm.fm.common.datatype.transport.JournalDisplayLine;
import oracle.epm.fm.common.datatype.transport.JournalErrorDetail;
import oracle.epm.fm.common.datatype.transport.JournalFilter;
import oracle.epm.fm.common.datatype.transport.JournalLineItem;
import oracle.epm.fm.common.datatype.transport.DOCUMENTFILETYPES;
import oracle.epm.fm.common.datatype.transport.DOCUMENTTYPES;
import oracle.epm.fm.common.datatype.transport.ENUMSHOWPRIVATEDOCS;
import oracle.epm.fm.common.datatype.transport.FMDocument;
import oracle.epm.fm.common.datatype.transport.REPORT_FORMATS;
import oracle.epm.fm.common.datatype.transport.REPORT_TYPES;
import oracle.epm.fm.common.datatype.transport.SystemReport;
import oracle.epm.fm.domainobject.common.SystemReportsHelper;
import oracle.epm.fm.domainobject.data.GenericDocument;
import oracle.epm.fm.domainobject.document.DocumentOM;
import oracle.epm.fm.domainobject.journal.JournalOM;
import oracle.epm.fm.domainobject.reports.ReportOverrideOptions;
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
        final String application;
        /** Custom dimension name (lower case) → "CustomN", an alternative name HFM accepts for member queries. */
        final Map<String, String> customAliases = new java.util.concurrent.ConcurrentHashMap<>();

        OracleSession(SessionInfo info, String ssoToken, String cluster, String application) {
            this.info = info;
            this.ssoToken = ssoToken;
            this.cluster = cluster;
            this.application = application;
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
            return new OracleSession(info, auth.ssoToken(), clusterName, application);
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

    // ---------------------------------------------------------------- journals

    @Override
    public List<JournalInfo> listJournals(BackendSession session, String scenario, String year, String period,
                                          String value, JournalQuery q) throws BackendException {
        SessionInfo info = info(session);
        try {
            JournalOM om = new JournalOM(info);
            // Start from the system default filter: it also decides which columns HFM fills in.
            JournalFilter system = withRetry(() -> om.getCurrentJournalFilter(true));
            JournalFilter filter = system == null ? new JournalFilter() : system;
            if (q.statuses != null && !q.statuses.isEmpty()) {
                List<JOURNAL_STATUS> statuses = new ArrayList<>();
                for (String s : q.statuses) {
                    statuses.add(journalEnum(JOURNAL_STATUS.class, s));
                }
                filter.setJournalStatuses(statuses);
            }
            if (q.types != null && !q.types.isEmpty()) {
                List<JOURNAL_TYPE> types = new ArrayList<>();
                for (String t : q.types) {
                    types.add(journalEnum(JOURNAL_TYPE.class, t));
                }
                filter.setJournalTypes(types);
            }
            if (notBlank(q.label)) {
                filter.setLabelFilter(q.label.trim());
            }
            if (notBlank(q.group)) {
                filter.setGroupFilter(q.group.trim());
            }
            if (notBlank(q.entity)) {
                filter.setEntityFilter(q.entity.trim());
            }
            if (notBlank(q.description)) {
                filter.setDescriptionFilter(q.description.trim());
            }
            final JournalFilter request = filter;
            List<JournalDisplayLine> lines = withRetry(() -> om.enumJournals(scenario, year, period, value, request));
            List<JournalInfo> out = new ArrayList<>();
            if (lines != null) {
                for (JournalDisplayLine l : lines) {
                    out.add(new JournalInfo(l.getId(), l.getLabel(), l.getDescription(), l.getJournalGroup(),
                            journalName(l.getStatus()), journalName(l.getJournalType()),
                            journalName(l.getBalanceType()), l.getEntity(), l.getParent(), l.getValue(),
                            l.getCreatedBy(), l.getCreatedOn(), l.getLastActedBy(), l.getLastActedOn(),
                            l.getApprovedBy(), l.getApprovedOn()));
                }
            }
            return out;
        } catch (HFMException e) {
            throw classify(e, "list journals for " + scenario + "/" + year + "/" + period);
        }
    }

    @Override
    public JournalDetail getJournal(BackendSession session, String label, String scenario, String year,
                                    String period) throws BackendException {
        SessionInfo info = info(session);
        try {
            JournalOM om = new JournalOM(info);
            Journal j = withRetry(() -> om.getJournalByName(label, scenario, year, period));
            if (j == null) {
                throw new BackendException(Kind.BAD_REQUEST,
                        "No journal '" + label + "' in " + scenario + "/" + year + "/" + period);
            }
            Map<String, String> pov = new LinkedHashMap<>();
            if (j.getPov() != null) {
                for (JournalDimension d : j.getPov()) {
                    pov.put(d.getName(), d.getMember());
                }
            }
            List<JournalDetail.Line> lines = new ArrayList<>();
            if (j.getLineItems() != null) {
                for (JournalLineItem l : j.getLineItems()) {
                    Map<String, String> dims = new LinkedHashMap<>();
                    if (l.getLineItemDimensions() != null) {
                        for (JournalDimension d : l.getLineItemDimensions()) {
                            dims.put(d.getName(), d.getMember());
                        }
                    }
                    lines.add(new JournalDetail.Line(l.getIndex(), l.getDescription(), l.getAmount(),
                            debitSide(l.getDebitUnit()), dims));
                }
            }
            return new JournalDetail(j.getId(), j.getLabel(), j.getDescription(), j.getGroup(),
                    journalName(j.getStatus()), journalName(j.getType()), journalName(j.getBalancedType()),
                    j.getSingleEntity(), pov, lines);
        } catch (HFMException e) {
            throw classify(e, "read journal " + label);
        }
    }

    @Override
    public List<JournalError> scanJournal(BackendSession session, JournalDetail journal) throws BackendException {
        SessionInfo info = info(session);
        Journal request = toJournal(journal);
        try {
            JournalOM om = new JournalOM(info);
            List<JournalErrorDetail> found = withRetry(() -> om.performScan(request));
            List<JournalError> out = new ArrayList<>();
            if (found != null) {
                for (JournalErrorDetail d : found) {
                    out.add(new JournalError(d.getLineNumber(), d.getValidationError()));
                }
            }
            return out;
        } catch (HFMException e) {
            throw classify(e, "scan journal " + journal.label);
        }
    }

    @Override
    public int saveJournal(BackendSession session, JournalDetail journal) throws BackendException {
        SessionInfo info = info(session);
        Journal request = toJournal(journal);
        try {
            JournalOM om = new JournalOM(info);
            return withRetry(() -> om.saveJournal(request));
        } catch (HFMException e) {
            BackendException be = classify(e, "save journal " + journal.label);
            // A rejected save (bad member, wrong status) is the request's problem, not HFM's.
            throw be.kind() == Kind.HFM_ERROR ? new BackendException(Kind.BAD_REQUEST, e.getMessage(), e) : be;
        }
    }

    @Override
    public List<String> runJournalAction(BackendSession session, JournalAction action, List<String> labels,
                                         String scenario, String year, String period) throws BackendException {
        SessionInfo info = info(session);
        JOURNAL_ACTION hfmAction = journalEnum(JOURNAL_ACTION.class, action.name());
        List<String> request = new ArrayList<>(labels);
        try {
            JournalOM om = new JournalOM(info);
            List<String> notDone = withRetry(() -> om.performJournalAction(request, scenario, year, period, hfmAction));
            return notDone == null ? new ArrayList<String>() : new ArrayList<>(notDone);
        } catch (HFMException e) {
            BackendException be = classify(e, action + " journals");
            throw be.kind() == Kind.HFM_ERROR ? new BackendException(Kind.BAD_REQUEST, e.getMessage(), e) : be;
        }
    }

    private static Journal toJournal(JournalDetail d) throws BackendException {
        Journal j = new Journal();
        j.setId(d.id);
        j.setLabel(d.label);
        if (d.id != 0) {
            j.setOldlabel(d.label);
        }
        j.setDescription(d.description == null ? "" : d.description);
        j.setGroup(d.group == null ? "" : d.group);
        j.setType(journalEnum(JOURNAL_TYPE.class, d.type == null ? "REGULAR" : d.type));
        j.setStatus(journalEnum(JOURNAL_STATUS.class, "WORKING"));
        for (Map.Entry<String, String> e : d.pov.entrySet()) {
            j.addToPov(new JournalDimension(e.getKey(), e.getValue(), ""));
        }
        for (JournalDetail.Line l : d.lines) {
            JournalLineItem item = new JournalLineItem();
            item.setIndex(l.index);
            item.setDescription(l.description == null ? "" : l.description);
            item.setAmount(l.amount);
            item.setDebitUnit(debitUnit(l.side));
            for (Map.Entry<String, String> e : l.dimensions.entrySet()) {
                item.addToLineItemDimensions(new JournalDimension(e.getKey(), e.getValue(), ""));
            }
            j.addToLineItems(item);
        }
        return j;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }

    /** HFM names its enum constants with a prefix ({@code JSF_WORKING}, {@code JBA_POST}); the API uses the rest. */
    private static String journalName(Enum<?> e) {
        if (e == null) {
            return null;
        }
        String n = e.name();
        int i = n.indexOf('_');
        return i < 0 ? n : n.substring(i + 1);
    }

    private static <E extends Enum<E>> E journalEnum(Class<E> type, String name) throws BackendException {
        for (E e : type.getEnumConstants()) {
            if (name.equalsIgnoreCase(journalName(e))) {
                return e;
            }
        }
        throw new BackendException(Kind.BAD_REQUEST, "'" + name + "' is not a valid " + type.getSimpleName());
    }

    private static String debitSide(DEBIT_CREDIT_UNIT unit) {
        return unit != null && unit.name().toUpperCase(java.util.Locale.ROOT).contains("DEBIT") ? "DEBIT" : "CREDIT";
    }

    private static DEBIT_CREDIT_UNIT debitUnit(String side) throws BackendException {
        for (DEBIT_CREDIT_UNIT u : DEBIT_CREDIT_UNIT.values()) {
            if (side.equals(debitSide(u))) {
                return u;
            }
        }
        throw new BackendException(Kind.BAD_REQUEST, "side must be DEBIT or CREDIT");
    }

    // ---------------------------------------------------------------- reports

    private static final Map<String, DOCUMENTTYPES> REPORT_DOC_TYPES = new LinkedHashMap<>();

    static {
        REPORT_DOC_TYPES.put("JOURNAL", DOCUMENTTYPES.WEBOM_DOCTYPE_RPTJOURNAL);
        REPORT_DOC_TYPES.put("INTERCOMPANY", DOCUMENTTYPES.WEBOM_DOCTYPE_RPTINTERCOMPANY);
        REPORT_DOC_TYPES.put("IC_MATCHING_TEMPLATE", DOCUMENTTYPES.WEBOM_DOCTYPE_RPTICMATCHINGTEMPLATE);
        REPORT_DOC_TYPES.put("IC_TRANSACTION", DOCUMENTTYPES.WEBOM_DOCTYPE_RPTICTRANSACTION);
        REPORT_DOC_TYPES.put("IC_MONITOR", DOCUMENTTYPES.WEBOM_DOCTYPE_RPTICMONITOR);
        REPORT_DOC_TYPES.put("IC_MATCH_BY_ACCOUNT", DOCUMENTTYPES.WEBOM_DOCTYPE_RPTICMATCHBYACCOUNT);
        REPORT_DOC_TYPES.put("IC_MATCH_BY_TRANS_ID", DOCUMENTTYPES.WEBOM_DOCTYPE_RPTICMATCHBYTRANSID);
        REPORT_DOC_TYPES.put("DATA_EXPLORER", DOCUMENTTYPES.WEBOM_DOCTYPE_RPTDATAEXPLORER);
        REPORT_DOC_TYPES.put("HYP", DOCUMENTTYPES.WEBOM_DOCTYPE_RPTHYP);
    }

    /** Financial Reporting (EPM Workspace repository, TENANT_ID='fr') type -> MCP reportType. */
    private static final Map<String, String> FR_REPO_TO_REPORT_TYPE = new LinkedHashMap<>();
    private static final Map<String, String> REPORT_TYPE_TO_FR_REPO = new LinkedHashMap<>();
    private static final Map<String, String> FR_REPORT_FILE_TYPES = new LinkedHashMap<>();
    private static final Set<String> FR_INTERNAL_TOP_FOLDERS = new HashSet<>(Arrays.asList(
            "HRINTERNALFOLDER", "IRBIVINTERNALFOLDER", "ANNOTATIONSINTERNALFOLDER",
            "BROADCAST MESSAGES", "SHARED WORKSPACE PAGES", "SYSTEM", "ADMINISTRATION", "SSR TEMPLATE"));

    static {
        registerFrType("FR_REPORT", "des", "DES");
        registerFrType("FR_BOOK", "kbk", "KBK");
        registerFrType("FR_SNAPSHOT_BOOK", "kbt", "KBT");
        registerFrType("FR_SNAPSHOT_REPORT", "rpt", "RPT");
        registerFrType("FR_BATCH", "bch", "BCH");
        registerFrType("FR_GRID", "rog", "ROG");
        registerFrType("FR_ROW_COLUMN", "ros", "ROS");
        registerFrType("FR_TEXT", "rot", "ROT");
        registerFrType("FOLDER", "FOLDER", "FOLDER");
        FR_REPO_TO_REPORT_TYPE.put("bjo", "FR_BATCH");
    }

    private static void registerFrType(String reportType, String repoType, String fileType) {
        FR_REPO_TO_REPORT_TYPE.put(repoType.toLowerCase(Locale.ROOT), reportType);
        REPORT_TYPE_TO_FR_REPO.put(reportType, repoType);
        FR_REPORT_FILE_TYPES.put(reportType, fileType);
    }

    private static final class FrRepoNode {
        final String uuid;
        final String repoType;
        final String reportType;
        final String fileType;
        final String parentUuid;
        final String author;
        final long creationTime;
        final boolean internal;
        final boolean hidden;
        String name;
        String description = "";
        String lastModifiedBy = "";
        long lastModified;
        String folderPath = "Root";
        boolean isPrivate;

        FrRepoNode(String uuid, String repoType, String reportType, String fileType, String parentUuid,
                   String author, long creationTime, boolean internal, boolean hidden) {
            this.uuid = uuid;
            this.repoType = repoType;
            this.reportType = reportType;
            this.fileType = fileType;
            this.parentUuid = parentUuid;
            this.author = author == null ? "" : author;
            this.creationTime = creationTime;
            this.internal = internal;
            this.hidden = hidden;
            this.lastModified = creationTime;
        }

        ReportInfo toReportInfo() {
            String owner = notBlank(lastModifiedBy) ? lastModifiedBy : author;
            return new ReportInfo(
                    name == null ? uuid : name,
                    reportType,
                    fileType,
                    folderPath,
                    description == null ? "" : description,
                    "[Default]",
                    isPrivate,
                    owner == null ? "" : owner,
                    lastModified);
        }
    }

    private final Object frCacheLock = new Object();
    private java.sql.Connection frJdbcConnection;
    private List<FrRepoNode> frCachedNodes;
    private long frCacheTimestampMs;
    private static final long FR_CACHE_TTL_MS = 15_000L;

    @Override
    public List<ReportInfo> listReports(BackendSession session, ReportQuery q) throws BackendException {
        SessionInfo info = info(session);
        ENUMSHOWPRIVATEDOCS priv = q.includePrivate
                ? ENUMSHOWPRIVATEDOCS.ENUMSHOWPRIVATEDOCS_SHOW
                : ENUMSHOWPRIVATEDOCS.ENUMSHOWPRIVATEDOCS_DONTSHOW;
        List<DOCUMENTTYPES> wantedHfmTypes = new ArrayList<>();
        Set<String> wantedFrTypes = new HashSet<>();
        if (q.reportTypes != null && !q.reportTypes.isEmpty()) {
            for (String rt : q.reportTypes) {
                if (!notBlank(rt)) {
                    continue;
                }
                String upper = rt.trim().toUpperCase(Locale.ROOT);
                if (REPORT_TYPE_TO_FR_REPO.containsKey(upper)) {
                    wantedFrTypes.add(upper);
                } else {
                    wantedHfmTypes.add(toDocType(upper));
                }
            }
        } else {
            wantedHfmTypes.addAll(REPORT_DOC_TYPES.values());
            wantedFrTypes.addAll(REPORT_TYPE_TO_FR_REPO.keySet());
            wantedFrTypes.remove("FOLDER");
        }
        String folder = normalizeDocPath(q.folder);
        Set<String> seen = new HashSet<>();
        List<ReportInfo> out = new ArrayList<>();

        // 1. Query HFM Document Manager (DocumentOM) if any HFM report types are requested
        if (!wantedHfmTypes.isEmpty()) {
            try {
                DocumentOM om = new DocumentOM(info);
                List<FMDocument> docs = new ArrayList<>();
                if (q.reportTypes == null || q.reportTypes.isEmpty()) {
                    List<FMDocument> all = enumDocsAtPath(om, folder, DOCUMENTTYPES.WEBOM_DOCTYPE_ALL,
                            DOCUMENTFILETYPES.WEBOM_DOCFILETYPE_ALL, priv);
                    if (all != null && !all.isEmpty()) {
                        docs.addAll(all);
                    } else {
                        for (DOCUMENTTYPES dt : wantedHfmTypes) {
                            List<FMDocument> found = enumDocsAtPath(om, folder, dt,
                                    DOCUMENTFILETYPES.WEBOM_DOCFILETYPE_ALL, priv);
                            if (found != null) {
                                docs.addAll(found);
                            }
                        }
                    }
                } else {
                    for (DOCUMENTTYPES dt : wantedHfmTypes) {
                        List<FMDocument> found = enumDocsAtPath(om, folder, dt,
                                DOCUMENTFILETYPES.WEBOM_DOCFILETYPE_ALL, priv);
                        if (found != null) {
                            docs.addAll(found);
                        }
                    }
                }
                Set<DOCUMENTTYPES> allowedSet = new HashSet<>(wantedHfmTypes);
                for (FMDocument d : docs) {
                    if (d == null || d.getDocType() == null || !allowedSet.contains(d.getDocType())) {
                        continue;
                    }
                    ReportInfo ri = toReportInfo(d, folder);
                    if (!matchesReportQuery(ri, q.query)) {
                        continue;
                    }
                    String key = ri.folder.toUpperCase(Locale.ROOT) + "|" + ri.reportType + "|"
                            + ri.name.toUpperCase(Locale.ROOT);
                    if (seen.add(key)) {
                        out.add(ri);
                    }
                }
            } catch (HFMException e) {
                if (wantedFrTypes.isEmpty()) {
                    throw classify(e, "list reports");
                }
                LOG.log(Level.FINE, "DocumentOM.enumDocuments returned error while listing reports", e);
            }
        }

        // 2. Query Financial Reporting Repository (TENANT_ID = 'fr')
        if (!wantedFrTypes.isEmpty()) {
            List<FrRepoNode> frNodes = loadFrRepositoryNodes(false);
            boolean explicitInternalFolder = isInternalFrFolder(folder);
            for (FrRepoNode node : frNodes) {
                if (!wantedFrTypes.contains(node.reportType)) {
                    continue;
                }
                if ((node.internal || node.hidden) && !explicitInternalFolder) {
                    continue;
                }
                if (!explicitInternalFolder && isInternalFrFolder(node.folderPath)) {
                    continue;
                }
                if (node.isPrivate && !q.includePrivate && !explicitInternalFolder) {
                    continue;
                }
                if (!matchesFolderFilter(node.folderPath, folder)) {
                    continue;
                }
                ReportInfo ri = node.toReportInfo();
                if (!matchesReportQuery(ri, q.query)) {
                    continue;
                }
                String key = ri.folder.toUpperCase(Locale.ROOT) + "|" + ri.reportType + "|"
                        + ri.name.toUpperCase(Locale.ROOT);
                if (seen.add(key)) {
                    out.add(ri);
                }
            }
        }

        out.sort((a, b) -> {
            int p = Integer.compare(reportTypePriority(a.reportType), reportTypePriority(b.reportType));
            if (p != 0) {
                return p;
            }
            int f = a.folder.compareToIgnoreCase(b.folder);
            if (f != 0) {
                return f;
            }
            int c = a.name.compareToIgnoreCase(b.name);
            return c != 0 ? c : a.reportType.compareToIgnoreCase(b.reportType);
        });
        return out;
    }

    @Override
    public ReportDetail getReport(BackendSession session, String folder, String name, String reportType)
            throws BackendException {
        SessionInfo info = info(session);
        String effectiveFolder = folder;
        String effectiveName = name == null ? "" : name.trim();
        if (effectiveName.contains("/") || effectiveName.contains("\\")) {
            String norm = effectiveName.replace('\\', '/');
            while (norm.startsWith("/")) {
                norm = norm.substring(1);
            }
            int lastSlash = norm.lastIndexOf('/');
            if (lastSlash > 0 && lastSlash < norm.length() - 1) {
                if (!notBlank(effectiveFolder) || "Root".equalsIgnoreCase(normalizeDocPath(effectiveFolder))) {
                    effectiveFolder = norm.substring(0, lastSlash);
                }
                effectiveName = norm.substring(lastSlash + 1);
            }
        }
        String normFolder = normalizeDocPath(effectiveFolder);
        String upperType = notBlank(reportType) ? reportType.trim().toUpperCase(Locale.ROOT) : null;
        boolean isFrOnly = upperType != null && REPORT_TYPE_TO_FR_REPO.containsKey(upperType);
        boolean isHfmOnly = upperType != null && !isFrOnly;

        // 1. Check Financial Reporting repository first unless an HFM-specific reportType was requested
        if (!isHfmOnly) {
            FrRepoNode frMatch = findFrReportNode(normFolder, effectiveName, upperType);
            if (frMatch != null) {
                return readFrReportDetail(frMatch);
            }
            if (isFrOnly) {
                throw new BackendException(Kind.BAD_REQUEST,
                        "No report '" + effectiveName + "' (" + upperType + ") in " + displayFolder(normFolder));
            }
        }

        // 2. Check HFM Document Manager (DocumentOM)
        try {
            DocumentOM om = new DocumentOM(info);
            ReportInfo meta = null;
            DOCUMENTTYPES dt = upperType != null ? toDocType(upperType) : null;
            DOCUMENTFILETYPES ft = DOCUMENTFILETYPES.WEBOM_DOCFILETYPE_RPTDEF;
            if (dt != null) {
                FMDocument doc = getDocInfoAtPath(om, normFolder, effectiveName, dt, ft);
                if (doc != null) {
                    meta = toReportInfo(doc, normFolder);
                    ft = doc.getFileType() != null ? doc.getFileType() : ft;
                }
            }
            if (meta == null) {
                List<String> filterTypes = dt == null
                        ? Collections.emptyList()
                        : Collections.singletonList(fromDocType(dt));
                List<ReportInfo> candidates = listReports(session, new ReportQuery(filterTypes, normFolder, null, true));
                List<ReportInfo> matches = new ArrayList<>();
                for (ReportInfo c : candidates) {
                    if (c.name != null && c.name.equalsIgnoreCase(effectiveName)) {
                        matches.add(c);
                    }
                }
                if (matches.isEmpty()) {
                    throw new BackendException(Kind.BAD_REQUEST,
                            "No report '" + effectiveName + "'" + (notBlank(reportType) ? " (" + reportType + ")" : "")
                                    + " in " + displayFolder(normFolder));
                }
                if (matches.size() > 1) {
                    List<String> locations = new ArrayList<>();
                    for (ReportInfo m : matches) {
                        locations.add(m.folder + "/" + m.name + " (" + m.reportType + ")");
                    }
                    throw new BackendException(Kind.BAD_REQUEST,
                            "Multiple reports named '" + effectiveName + "' found: "
                                    + String.join(", ", locations) + "; specify folder and/or reportType");
                }
                meta = matches.get(0);
                if (REPORT_TYPE_TO_FR_REPO.containsKey(meta.reportType)) {
                    FrRepoNode frNode = findFrReportNode(meta.folder, meta.name, meta.reportType);
                    if (frNode != null) {
                        return readFrReportDetail(frNode);
                    }
                }
                dt = toDocType(meta.reportType);
                ft = toFileType(meta.fileType);
            }
            final DOCUMENTTYPES docType = dt;
            final DOCUMENTFILETYPES fileType = ft;
            final String docName = meta.name;
            byte[] raw = getDocContentAtPath(om, normFolder, docName, docType, fileType);
            if (raw == null) {
                raw = new byte[0];
            }
            DecodedText decoded = decodeBytes(raw);
            return new ReportDetail(meta, decoded.text, decoded.encoding);
        } catch (HFMException e) {
            BackendException be = classify(e, "read report " + effectiveName);
            throw be.kind() == Kind.HFM_ERROR ? new BackendException(Kind.BAD_REQUEST, e.getMessage(), e) : be;
        }
    }

    @Override
    public int startReport(BackendSession session, ReportDetail report, ReportRunRequest request)
            throws BackendException {
        OracleSession os = oracle(session);
        REPORT_TYPES rType = toSystemReportType(report.info.reportType);
        REPORT_FORMATS rFormat = toSystemReportFormat(request.format);
        ReportOverrideOptions overrides = new ReportOverrideOptions();
        overrides.setReportFormat(rFormat);
        boolean hasOverrides = false;
        if (notBlank(request.povSlice)) {
            overrides.setPovSliceString(request.povSlice.trim());
            hasOverrides = true;
        }
        if (notBlank(request.entity)) {
            overrides.setEntity(request.entity.trim());
            hasOverrides = true;
        }
        if (notBlank(request.partner)) {
            overrides.setPartner(request.partner.trim());
            hasOverrides = true;
        }
        if (notBlank(request.stylesheet)) {
            overrides.setStylesheetName(request.stylesheet.trim());
            hasOverrides = true;
        }
        String hfmPath = toHfmDocPath(report.info.folder);
        try {
            SystemReportsHelper helper = new SystemReportsHelper(os.info);
            if (hasOverrides) {
                try {
                    int id = helper.generateReport(hfmPath, report.info.name, rType, rFormat, os.application, overrides);
                    if (id > 0) {
                        return id;
                    }
                } catch (Exception e) {
                    if (e instanceof HFMException) {
                        throw (HFMException) e;
                    }
                    LOG.log(Level.FINE, "generateReport fallback to startReport for " + report.info.name, e);
                }
            }
            SystemReport sr = new SystemReport()
                    .setReportDefinition(report.code == null ? "" : report.code)
                    .setReportTemplate(notBlank(request.stylesheet) ? request.stylesheet.trim() : "")
                    .setOutputFormat(rFormat)
                    .setReportType(rType);
            int id = withRetry(() -> helper.startReport(os.info, sr));
            if (id <= 0) {
                throw new BackendException(Kind.HFM_ERROR, "HFM did not start report task for " + report.info.name);
            }
            return id;
        } catch (HFMException e) {
            throw classify(e, "run report " + report.info.name);
        }
    }

    @Override
    public Path fetchReportFile(BackendSession session, int taskId, Path directory, String fileName)
            throws BackendException {
        SessionInfo info = info(session);
        try {
            AdministrationOM admin = new AdministrationOM(info);
            File file = withRetry(() -> admin.getRunningTaskFile(taskId));
            if (file == null) {
                throw new BackendException(Kind.HFM_ERROR, "HFM returned no output file for report task " + taskId);
            }
            List<Path> unpacked = FileUnpacker.unpack(file.getAbsoluteFile().toPath(), directory);
            if (unpacked.isEmpty()) {
                throw new BackendException(Kind.HFM_ERROR, "HFM report archive for task " + taskId + " was empty");
            }
            Path first = unpacked.get(0);
            if (notBlank(fileName) && unpacked.size() == 1) {
                Path target = directory.toAbsolutePath().normalize().resolve(fileName);
                if (!first.equals(target)) {
                    java.nio.file.Files.move(first, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return target;
                }
            }
            return first;
        } catch (HFMException e) {
            throw classify(e, "download report output of task " + taskId);
        } catch (IOException e) {
            throw new BackendException(Kind.HFM_ERROR,
                    "Cannot save report output of task " + taskId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void saveReport(BackendSession session, ReportDetail report, boolean overwrite) throws BackendException {
        SessionInfo info = info(session);
        ReportInfo ri = report.info;
        String upperType = ri.reportType == null ? "" : ri.reportType.trim().toUpperCase(Locale.ROOT);
        if (REPORT_TYPE_TO_FR_REPO.containsKey(upperType)) {
            saveFrReport(report, overwrite);
            return;
        }
        DOCUMENTTYPES dt = toDocType(ri.reportType);
        DOCUMENTFILETYPES ft = notBlank(ri.fileType) ? toFileType(ri.fileType) : DOCUMENTFILETYPES.WEBOM_DOCFILETYPE_RPTDEF;
        FMDocument doc = new FMDocument()
                .setName(ri.name)
                .setPath(toHfmDocPath(ri.folder))
                .setDocType(dt)
                .setFileType(ft)
                .setDescription(ri.description == null ? "" : ri.description)
                .setSecurityClass(notBlank(ri.securityClass) ? ri.securityClass : "[Default]")
                .setIsPrivate(ri.isPrivate);
        if (notBlank(ri.owner)) {
            doc.setOwner(ri.owner);
        }
        if (ri.lastModified > 0) {
            doc.setLastModified(ri.lastModified);
        }
        GenericDocument content = new GenericDocument();
        content.setDocumentObjectFromContent(encodeBytes(report.code == null ? "" : report.code, report.encoding));
        try {
            DocumentOM om = new DocumentOM(info);
            om.saveDocumentContent(doc, content, overwrite);
        } catch (HFMException e) {
            BackendException be = classify(e, "save report " + ri.name);
            throw be.kind() == Kind.HFM_ERROR ? new BackendException(Kind.BAD_REQUEST, e.getMessage(), e) : be;
        }
    }

    @Override
    public void deleteReport(BackendSession session, ReportInfo ri) throws BackendException {
        SessionInfo info = info(session);
        String upperType = ri.reportType == null ? "" : ri.reportType.trim().toUpperCase(Locale.ROOT);
        if (REPORT_TYPE_TO_FR_REPO.containsKey(upperType)) {
            deleteFrReport(ri);
            return;
        }
        DOCUMENTTYPES dt = toDocType(ri.reportType);
        DOCUMENTFILETYPES ft = notBlank(ri.fileType) ? toFileType(ri.fileType) : DOCUMENTFILETYPES.WEBOM_DOCFILETYPE_RPTDEF;
        try {
            DocumentOM om = new DocumentOM(info);
            om.deleteDocuments(
                    Collections.singletonList(ri.name),
                    Collections.singletonList(toHfmDocPath(ri.folder)),
                    Collections.singletonList(dt),
                    Collections.singletonList(ft));
        } catch (HFMException e) {
            BackendException be = classify(e, "delete report " + ri.name);
            throw be.kind() == Kind.HFM_ERROR ? new BackendException(Kind.BAD_REQUEST, e.getMessage(), e) : be;
        }
    }

    // ---------------------------------------------------------------- Financial Reporting Repository (TENANT_ID = 'fr')

    private java.sql.Connection getFrConnection() throws java.sql.SQLException {
        synchronized (frCacheLock) {
            try {
                if (frJdbcConnection != null && !frJdbcConnection.isClosed()) {
                    return frJdbcConnection;
                }
            } catch (java.sql.SQLException ignored) {
                frJdbcConnection = null;
            }
            try {
                Class<?> regUtils = Class.forName("com.hyperion.hit.registry.RegistryUtils");
                frJdbcConnection = (java.sql.Connection) regUtils.getMethod("createNewConnection").invoke(null);
                return frJdbcConnection;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof java.sql.SQLException) {
                    throw (java.sql.SQLException) cause;
                }
                throw new java.sql.SQLException("Cannot open EPM repository connection: " + cause.getMessage(), cause);
            }
        }
    }

    private void resetFrConnection() {
        synchronized (frCacheLock) {
            if (frJdbcConnection != null) {
                try {
                    frJdbcConnection.close();
                } catch (Exception ignored) {
                }
                frJdbcConnection = null;
            }
        }
    }

    private List<FrRepoNode> loadFrRepositoryNodes(boolean forceRefresh) {
        synchronized (frCacheLock) {
            long now = System.currentTimeMillis();
            if (!forceRefresh && frCachedNodes != null && (now - frCacheTimestampMs) < FR_CACHE_TTL_MS) {
                return frCachedNodes;
            }
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                java.sql.Connection conn = getFrConnection();
                Map<String, FrRepoNode> byUuid = new LinkedHashMap<>();
                String objSql = "SELECT OBJECT_UUID, TYPE, PARENT_FOLDER_UUID, AUTHOR, CREATION_DATE, INTERNAL, HIDDEN "
                        + "FROM REPOSITORY_OBJECTS WHERE TENANT_ID = 'fr'";
                try (java.sql.PreparedStatement ps = conn.prepareStatement(objSql);
                     java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String uuid = rs.getString(1);
                        String rawType = rs.getString(2);
                        String parent = rs.getString(3);
                        String author = rs.getString(4);
                        java.sql.Timestamp created = rs.getTimestamp(5);
                        String internalStr = rs.getString(6);
                        String hiddenStr = rs.getString(7);
                        if (uuid == null || rawType == null || "ROOTfr".equals(uuid)) {
                            continue;
                        }
                        String typeKey = rawType.trim().toLowerCase(Locale.ROOT);
                        String reportType = FR_REPO_TO_REPORT_TYPE.get(typeKey);
                        if (reportType == null) {
                            continue;
                        }
                        String fileType = FR_REPORT_FILE_TYPES.getOrDefault(reportType, rawType.trim().toUpperCase(Locale.ROOT));
                        long creationTime = created != null ? created.getTime() : 0L;
                        boolean internal = "Y".equalsIgnoreCase(internalStr) || "TRUE".equalsIgnoreCase(internalStr) || "1".equals(internalStr);
                        boolean hidden = "Y".equalsIgnoreCase(hiddenStr) || "TRUE".equalsIgnoreCase(hiddenStr) || "1".equals(hiddenStr);
                        byUuid.put(uuid, new FrRepoNode(uuid, rawType.trim(), reportType, fileType, parent,
                                author, creationTime, internal, hidden));
                    }
                }

                String propSql = "SELECT p.OBJECT_UUID, p.PROPERTY_NAME, p.PROPERTY_VALUE "
                        + "FROM REPOSITORY_OBJECT_PROPERTIES p "
                        + "JOIN REPOSITORY_OBJECTS o ON p.OBJECT_UUID = o.OBJECT_UUID AND o.TENANT_ID = 'fr' "
                        + "WHERE p.PROPERTY_NAME IN ('NAME', 'DESCRIPTION', 'lastModifiedBy', "
                        + "'{http://www.jcp.org/jcr/1.0}lastModifiedBy', 'jcr:lastModified') "
                        + "AND p.VERSION_NUMBER = (SELECT MAX(p2.VERSION_NUMBER) "
                        + "FROM REPOSITORY_OBJECT_PROPERTIES p2 WHERE p2.OBJECT_UUID = p.OBJECT_UUID)";
                try (java.sql.PreparedStatement ps = conn.prepareStatement(propSql);
                     java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        FrRepoNode node = byUuid.get(rs.getString(1));
                        if (node == null) {
                            continue;
                        }
                        String pName = rs.getString(2);
                        byte[] val = rs.getBytes(3);
                        if (pName == null || val == null) {
                            continue;
                        }
                        if ("NAME".equals(pName)) {
                            node.name = new String(val, java.nio.charset.StandardCharsets.UTF_8);
                        } else if ("DESCRIPTION".equals(pName)) {
                            node.description = new String(val, java.nio.charset.StandardCharsets.UTF_8);
                        } else if ("lastModifiedBy".equals(pName) || "{http://www.jcp.org/jcr/1.0}lastModifiedBy".equals(pName)) {
                            String modBy = new String(val, java.nio.charset.StandardCharsets.UTF_8).trim();
                            if (!modBy.isEmpty()) {
                                node.lastModifiedBy = modBy;
                            }
                        } else if ("jcr:lastModified".equals(pName)) {
                            if (val.length == 8) {
                                node.lastModified = java.nio.ByteBuffer.wrap(val).getLong();
                            }
                        }
                    }
                }

                List<FrRepoNode> result = new ArrayList<>(byUuid.size());
                for (FrRepoNode node : byUuid.values()) {
                    if (node.name == null || node.name.isEmpty()) {
                        node.name = node.uuid;
                    }
                    List<String> segments = new ArrayList<>();
                    String curParent = node.parentUuid;
                    int guard = 0;
                    while (curParent != null && !"ROOTfr".equals(curParent) && guard++ < 32) {
                        FrRepoNode parentNode = byUuid.get(curParent);
                        if (parentNode == null) {
                            break;
                        }
                        segments.add(0, parentNode.name != null ? parentNode.name : parentNode.uuid);
                        curParent = parentNode.parentUuid;
                    }
                    if (segments.isEmpty()) {
                        node.folderPath = "Root";
                    } else {
                        node.folderPath = String.join("/", segments);
                    }
                    String topSegment = segments.isEmpty()
                            ? ("FOLDER".equals(node.reportType) ? node.name : "")
                            : segments.get(0);
                    node.isPrivate = "Users".equalsIgnoreCase(topSegment);
                    result.add(node);
                }

                synchronized (frCacheLock) {
                    frCachedNodes = Collections.unmodifiableList(result);
                    frCacheTimestampMs = System.currentTimeMillis();
                    return frCachedNodes;
                }
            } catch (java.sql.SQLException e) {
                resetFrConnection();
                if (attempt == 2) {
                    LOG.log(Level.WARNING, "Failed to query Financial Reporting repository", e);
                }
            }
        }
        return Collections.emptyList();
    }

    private FrRepoNode findFrReportNode(String normFolder, String name, String reportType) throws BackendException {
        List<FrRepoNode> nodes = loadFrRepositoryNodes(false);
        List<FrRepoNode> exactFolderMatches = new ArrayList<>();
        List<FrRepoNode> subFolderMatches = new ArrayList<>();
        for (FrRepoNode node : nodes) {
            if ("FOLDER".equals(node.reportType)) {
                continue;
            }
            if (reportType != null && !reportType.equalsIgnoreCase(node.reportType)) {
                continue;
            }
            if (node.name == null || !node.name.equalsIgnoreCase(name)) {
                continue;
            }
            if (node.folderPath.equalsIgnoreCase(normFolder)) {
                exactFolderMatches.add(node);
            } else if (!node.internal && !node.hidden && !isInternalFrFolder(node.folderPath)
                    && matchesFolderFilter(node.folderPath, normFolder)) {
                subFolderMatches.add(node);
            }
        }
        List<FrRepoNode> candidates = !exactFolderMatches.isEmpty() ? exactFolderMatches : subFolderMatches;
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        // Prefer primary types (FR_REPORT / FR_BOOK) over fragment types (FR_TEXT / FR_GRID / FR_ROW_COLUMN)
        List<FrRepoNode> primary = new ArrayList<>();
        for (FrRepoNode c : candidates) {
            if ("FR_REPORT".equals(c.reportType) || "FR_BOOK".equals(c.reportType)) {
                primary.add(c);
            }
        }
        if (primary.size() == 1) {
            return primary.get(0);
        }
        List<FrRepoNode> reportList = !primary.isEmpty() ? primary : candidates;
        List<String> locations = new ArrayList<>();
        for (FrRepoNode c : reportList) {
            locations.add(c.folderPath + "/" + c.name + " (" + c.reportType + ")");
        }
        throw new BackendException(Kind.BAD_REQUEST,
                "Multiple reports named '" + name + "' found: "
                        + String.join(", ", locations) + "; specify folder and/or reportType");
    }

    private ReportDetail readFrReportDetail(FrRepoNode node) throws BackendException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                java.sql.Connection conn = getFrConnection();
                String sql = "SELECT PROPERTY_VALUE FROM REPOSITORY_OBJECT_PROPERTIES "
                        + "WHERE OBJECT_UUID = ? AND PROPERTY_NAME = 'jcr:data' "
                        + "ORDER BY VERSION_NUMBER DESC";
                byte[] raw = null;
                try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, node.uuid);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            raw = rs.getBytes(1);
                        }
                    }
                }
                if (raw == null) {
                    raw = new byte[0];
                }
                DecodedText decoded = decodeFrBytes(raw);
                return new ReportDetail(node.toReportInfo(), decoded.text, decoded.encoding);
            } catch (java.sql.SQLException e) {
                resetFrConnection();
                if (attempt == 2) {
                    throw new BackendException(Kind.HFM_ERROR,
                            "Failed to read Financial Reporting object '" + node.name + "': " + e.getMessage(), e);
                }
            }
        }
        throw new BackendException(Kind.HFM_ERROR, "Failed to read Financial Reporting object '" + node.name + "'");
    }

    private void saveFrReport(ReportDetail report, boolean overwrite) throws BackendException {
        ReportInfo ri = report.info;
        String code = report.code == null ? "" : report.code;
        String encoding = report.encoding;
        String normFolder = normalizeDocPath(ri.folder);
        FrRepoNode existing = findFrReportNode(normFolder, ri.name, ri.reportType);
        if (existing != null && !overwrite) {
            throw new BackendException(Kind.BAD_REQUEST,
                    "Report '" + ri.name + "' (" + ri.reportType + ") already exists in " + existing.folderPath);
        }

        FrRepoNode sourceNode = null;
        boolean explicitCopy = false;
        if (report.copyFrom != null && notBlank(report.copyFrom.name)) {
            sourceNode = findFrReportNode(normalizeDocPath(report.copyFrom.folder),
                    report.copyFrom.name, report.copyFrom.reportType);
            explicitCopy = sourceNode != null;
        }
        if (sourceNode == null && existing == null) {
            sourceNode = findDonorFrNode(normFolder, ri.reportType);
        }

        String repoType = existing != null
                ? existing.repoType
                : REPORT_TYPE_TO_FR_REPO.getOrDefault(
                        ri.reportType == null ? "FR_REPORT" : ri.reportType.toUpperCase(Locale.ROOT), "des");
        String parentFolderUuid = existing != null
                ? existing.parentUuid
                : findFrParentFolderUuid(normFolder, sourceNode);

        boolean zipCompressed = (encoding != null && encoding.toUpperCase(Locale.ROOT).startsWith("ZIP:"))
                || "des".equalsIgnoreCase(repoType)
                || "rog".equalsIgnoreCase(repoType)
                || "ros".equalsIgnoreCase(repoType)
                || "rot".equalsIgnoreCase(repoType)
                || "rpt".equalsIgnoreCase(repoType);
        byte[] payload = encodeFrBytes(code, encoding, zipCompressed);
        long now = ri.lastModified > 0 ? ri.lastModified : System.currentTimeMillis();
        byte[] nowLongBytes = java.nio.ByteBuffer.allocate(8).putLong(now).array();
        byte[] sizeLongBytes = java.nio.ByteBuffer.allocate(8).putLong(payload.length).array();
        byte[] sizeStrBytes = Long.toString(payload.length).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nameBytes = ri.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                java.sql.Connection conn = getFrConnection();
                boolean origAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    String targetUuid;
                    int targetVer = 1;
                    if (existing != null) {
                        targetUuid = existing.uuid;
                        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                                "SELECT MAX(VERSION_NUMBER) FROM REPOSITORY_OBJECT_PROPERTIES WHERE OBJECT_UUID = ?")) {
                            ps.setString(1, targetUuid);
                            try (java.sql.ResultSet rs = ps.executeQuery()) {
                                if (rs.next() && rs.getInt(1) > 0) {
                                    targetVer = rs.getInt(1);
                                }
                            }
                        }
                        try (java.sql.PreparedStatement delProp = conn.prepareStatement(
                                "DELETE FROM REPOSITORY_OBJECT_PROPERTIES WHERE OBJECT_UUID = ? AND PROPERTY_NAME LIKE '%DataQuery%'");
                             java.sql.PreparedStatement delIdx = conn.prepareStatement(
                                "DELETE FROM REPOSITORY_OBJECT_PROP_INDEX WHERE OBJECT_UUID = ? AND KEYWORD LIKE '%DataQuery%'")) {
                            delProp.setString(1, targetUuid);
                            delProp.executeUpdate();
                            delIdx.setString(1, targetUuid);
                            delIdx.executeUpdate();
                        }
                    } else {
                        targetUuid = java.util.UUID.randomUUID().toString();
                        java.sql.Timestamp nowTs = new java.sql.Timestamp(now);
                        String author = notBlank(ri.owner) ? ri.owner.trim()
                                : (sourceNode != null && notBlank(sourceNode.author) ? sourceNode.author : "admin");
                        try (java.sql.PreparedStatement insObj = conn.prepareStatement(
                                "INSERT INTO REPOSITORY_OBJECTS "
                                        + "(TENANT_ID, OBJECT_UUID, TYPE, PARENT_FOLDER_UUID, CREATION_DATE, AUTHOR, "
                                        + "INTERNAL, HIDDEN, READ_ONLY, VERSIONED, CHECKEDOUT, CHECKEDOUT_BY, LAST_ACCESSED, USAGE_COUNT) "
                                        + "VALUES ('fr', ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, NULL, ?, 0)")) {
                            insObj.setString(1, targetUuid);
                            insObj.setString(2, repoType);
                            insObj.setString(3, parentFolderUuid);
                            insObj.setTimestamp(4, nowTs);
                            insObj.setString(5, author);
                            insObj.setTimestamp(6, nowTs);
                            insObj.executeUpdate();
                        }
                        if (sourceNode != null) {
                            int srcVer = 1;
                            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                                    "SELECT MAX(VERSION_NUMBER) FROM REPOSITORY_OBJECT_PROPERTIES WHERE OBJECT_UUID = ?")) {
                                ps.setString(1, sourceNode.uuid);
                                try (java.sql.ResultSet rs = ps.executeQuery()) {
                                    if (rs.next() && rs.getInt(1) > 0) {
                                        srcVer = rs.getInt(1);
                                    }
                                }
                            }
                            String propFilter = explicitCopy
                                    ? "AND PROPERTY_NAME NOT LIKE 'USER_RECENT%' AND PROPERTY_NAME NOT LIKE 'USER_FAVORITE%' AND PROPERTY_NAME NOT LIKE '%DataQuery%'"
                                    : "AND (PROPERTY_NAME IN ('DataSourceIDs', '{http://www.jcp.org/jcr/1.0}autokeyword', 'AUTO_DELETE', 'PromptSet90') OR PROPERTY_NAME LIKE 'USER_PERMISSION%')";
                            try (java.sql.PreparedStatement copyProps = conn.prepareStatement(
                                    "INSERT INTO REPOSITORY_OBJECT_PROPERTIES "
                                            + "(OBJECT_UUID, PROPERTY_NAME, PROPERTY_VALUE, VERSION_NUMBER, VERSION_COPIED) "
                                            + "SELECT ?, PROPERTY_NAME, PROPERTY_VALUE, 1, 0 "
                                            + "FROM REPOSITORY_OBJECT_PROPERTIES "
                                            + "WHERE OBJECT_UUID = ? AND VERSION_NUMBER = ? " + propFilter)) {
                                copyProps.setString(1, targetUuid);
                                copyProps.setString(2, sourceNode.uuid);
                                copyProps.setInt(3, srcVer);
                                copyProps.executeUpdate();
                            }
                            try (java.sql.PreparedStatement copyAcls = conn.prepareStatement(
                                    "INSERT INTO REPOSITORY_OBJECT_ACLS (OBJECT_UUID, SUBJECT_ID, SUBJECT_TYPE, ACL_TYPE) "
                                            + "SELECT ?, SUBJECT_ID, SUBJECT_TYPE, ACL_TYPE "
                                            + "FROM REPOSITORY_OBJECT_ACLS WHERE OBJECT_UUID = ?")) {
                                copyAcls.setString(1, targetUuid);
                                copyAcls.setString(2, sourceNode.uuid);
                                copyAcls.executeUpdate();
                            }
                            String idxFilter = explicitCopy
                                    ? "AND KEYWORD NOT LIKE 'USER_RECENT%' AND KEYWORD NOT LIKE 'USER_FAVORITE%' AND KEYWORD NOT LIKE '%DataQuery%'"
                                    : "AND (KEYWORD IN ('DataSourceIDs', '{http://www.jcp.org/jcr/1.0}autokeyword', 'AUTO_DELETE', 'PromptSet90') OR KEYWORD LIKE 'USER_PERMISSION%')";
                            try (java.sql.PreparedStatement copyIdx = conn.prepareStatement(
                                    "INSERT INTO REPOSITORY_OBJECT_PROP_INDEX "
                                            + "(KEYWORD, TYPE, LONG_VALUE, DATE_VALUE, STRING_VALUE, VERSION_NUMBER, OBJECT_UUID) "
                                            + "SELECT KEYWORD, TYPE, LONG_VALUE, DATE_VALUE, STRING_VALUE, 1, ? "
                                            + "FROM REPOSITORY_OBJECT_PROP_INDEX "
                                            + "WHERE OBJECT_UUID = ? AND VERSION_NUMBER = ? " + idxFilter)) {
                                copyIdx.setString(1, targetUuid);
                                copyIdx.setString(2, sourceNode.uuid);
                                copyIdx.setInt(3, srcVer);
                                copyIdx.executeUpdate();
                            }
                        }
                    }

                    upsertFrProperty(conn, targetUuid, targetVer, "NAME", nameBytes);
                    upsertFrProperty(conn, targetUuid, targetVer, "jcr:originalFileName", nameBytes);
                    upsertFrProperty(conn, targetUuid, targetVer, "jcr:data", payload);
                    upsertFrProperty(conn, targetUuid, targetVer, "jcr:fileSize", sizeLongBytes);
                    upsertFrProperty(conn, targetUuid, targetVer, "fileSize", sizeStrBytes);
                    upsertFrProperty(conn, targetUuid, targetVer, "jcr:lastModified", nowLongBytes);
                    upsertFrProperty(conn, targetUuid, targetVer, "lastModified",
                            new java.util.Date(now).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String desc = ri.description == null ? "" : ri.description;
                    upsertFrProperty(conn, targetUuid, targetVer, "DESCRIPTION",
                            desc.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (notBlank(ri.owner)) {
                        byte[] ownerBytes = ri.owner.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        upsertFrProperty(conn, targetUuid, targetVer, "lastModifiedBy", ownerBytes);
                        upsertFrProperty(conn, targetUuid, targetVer, "{http://www.jcp.org/jcr/1.0}lastModifiedBy", ownerBytes);
                    }

                    upsertFrPropIndex(conn, targetUuid, targetVer, "NAME", "java.lang.String", -1L, ri.name);
                    upsertFrPropIndex(conn, targetUuid, targetVer, "jcr:originalFileName", "java.lang.String", -1L, ri.name);
                    upsertFrPropIndex(conn, targetUuid, targetVer, "DESCRIPTION", "java.lang.String", -1L, desc);
                    upsertFrPropIndex(conn, targetUuid, targetVer, "jcr:data", "byte", -1L, null);
                    upsertFrPropIndex(conn, targetUuid, targetVer, "jcr:fileSize", "java.lang.Long", payload.length, null);
                    upsertFrPropIndex(conn, targetUuid, targetVer, "jcr:lastModified", "java.lang.Long", now, null);

                    conn.commit();
                    synchronized (frCacheLock) {
                        frCachedNodes = null;
                    }
                    return;
                } catch (java.sql.SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(origAutoCommit);
                }
            } catch (java.sql.SQLException e) {
                resetFrConnection();
                if (attempt == 2) {
                    throw new BackendException(Kind.HFM_ERROR,
                            "Failed to save Financial Reporting object '" + ri.name + "': " + e.getMessage(), e);
                }
            }
        }
    }

    private FrRepoNode findDonorFrNode(String normFolder, String reportType) {
        List<FrRepoNode> nodes = loadFrRepositoryNodes(false);
        FrRepoNode anyMatch = null;
        for (FrRepoNode node : nodes) {
            if ("FOLDER".equals(node.reportType) || node.internal || node.hidden) {
                continue;
            }
            if (reportType != null && !reportType.equalsIgnoreCase(node.reportType)) {
                continue;
            }
            if (node.folderPath.equalsIgnoreCase(normFolder)) {
                return node;
            }
            if (anyMatch == null) {
                anyMatch = node;
            }
        }
        return anyMatch;
    }

    private String findFrParentFolderUuid(String normFolder, FrRepoNode sourceNode) throws BackendException {
        if (!notBlank(normFolder) || "Root".equalsIgnoreCase(normFolder)) {
            return "ROOTfr";
        }
        if (sourceNode != null && normFolder.equalsIgnoreCase(sourceNode.folderPath) && notBlank(sourceNode.parentUuid)) {
            return sourceNode.parentUuid;
        }
        List<FrRepoNode> nodes = loadFrRepositoryNodes(false);
        for (FrRepoNode node : nodes) {
            if ("FOLDER".equals(node.reportType)) {
                String fullPath = "Root".equalsIgnoreCase(node.folderPath)
                        ? node.name
                        : (node.folderPath + "/" + node.name);
                if (normFolder.equalsIgnoreCase(fullPath)) {
                    return node.uuid;
                }
            } else if (normFolder.equalsIgnoreCase(node.folderPath) && notBlank(node.parentUuid)) {
                return node.parentUuid;
            }
        }
        throw new BackendException(Kind.BAD_REQUEST,
                "Financial Reporting folder '" + displayFolder(normFolder) + "' does not exist");
    }

    private void deleteFrReport(ReportInfo ri) throws BackendException {
        String normFolder = normalizeDocPath(ri.folder);
        FrRepoNode existing = findFrReportNode(normFolder, ri.name, ri.reportType);
        if (existing == null) {
            throw new BackendException(Kind.BAD_REQUEST,
                    "Financial Reporting object '" + ri.name + "' (" + ri.reportType + ") does not exist in "
                            + displayFolder(normFolder));
        }
        if ("FOLDER".equalsIgnoreCase(existing.reportType)) {
            for (FrRepoNode n : loadFrRepositoryNodes(true)) {
                if (existing.uuid.equals(n.parentUuid)) {
                    throw new BackendException(Kind.BAD_REQUEST,
                            "Cannot delete non-empty folder '" + ri.name + "'");
                }
            }
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                java.sql.Connection conn = getFrConnection();
                boolean origAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (java.sql.PreparedStatement delIdx = conn.prepareStatement(
                            "DELETE FROM REPOSITORY_OBJECT_PROP_INDEX WHERE OBJECT_UUID = ?");
                         java.sql.PreparedStatement delAcl = conn.prepareStatement(
                            "DELETE FROM REPOSITORY_OBJECT_ACLS WHERE OBJECT_UUID = ?");
                         java.sql.PreparedStatement delProps = conn.prepareStatement(
                            "DELETE FROM REPOSITORY_OBJECT_PROPERTIES WHERE OBJECT_UUID = ?");
                         java.sql.PreparedStatement delObj = conn.prepareStatement(
                            "DELETE FROM REPOSITORY_OBJECTS WHERE OBJECT_UUID = ? AND TENANT_ID = 'fr'")) {
                        delIdx.setString(1, existing.uuid);
                        delIdx.executeUpdate();
                        delAcl.setString(1, existing.uuid);
                        delAcl.executeUpdate();
                        delProps.setString(1, existing.uuid);
                        delProps.executeUpdate();
                        delObj.setString(1, existing.uuid);
                        delObj.executeUpdate();
                    }
                    conn.commit();
                    synchronized (frCacheLock) {
                        frCachedNodes = null;
                    }
                    return;
                } catch (java.sql.SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(origAutoCommit);
                }
            } catch (java.sql.SQLException e) {
                resetFrConnection();
                if (attempt == 2) {
                    throw new BackendException(Kind.HFM_ERROR,
                            "Failed to delete Financial Reporting object '" + ri.name + "': " + e.getMessage(), e);
                }
            }
        }
    }

    private static void upsertFrPropIndex(java.sql.Connection conn, String uuid, int version,
                                          String keyword, String type, long longVal, String strVal)
            throws java.sql.SQLException {
        try (java.sql.PreparedStatement upd = conn.prepareStatement(
                "UPDATE REPOSITORY_OBJECT_PROP_INDEX SET TYPE = ?, LONG_VALUE = ?, STRING_VALUE = ? "
                        + "WHERE OBJECT_UUID = ? AND KEYWORD = ? AND VERSION_NUMBER = ?")) {
            upd.setString(1, type);
            upd.setLong(2, longVal);
            if (strVal != null) {
                upd.setString(3, strVal);
            } else {
                upd.setNull(3, java.sql.Types.NVARCHAR);
            }
            upd.setString(4, uuid);
            upd.setString(5, keyword);
            upd.setInt(6, version);
            if (upd.executeUpdate() > 0) {
                return;
            }
        }
        try (java.sql.PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO REPOSITORY_OBJECT_PROP_INDEX "
                        + "(KEYWORD, TYPE, LONG_VALUE, DATE_VALUE, STRING_VALUE, VERSION_NUMBER, OBJECT_UUID) "
                        + "VALUES (?, ?, ?, NULL, ?, ?, ?)")) {
            ins.setString(1, keyword);
            ins.setString(2, type);
            ins.setLong(3, longVal);
            if (strVal != null) {
                ins.setString(4, strVal);
            } else {
                ins.setNull(4, java.sql.Types.NVARCHAR);
            }
            ins.setInt(5, version);
            ins.setString(6, uuid);
            ins.executeUpdate();
        }
    }

    private static void upsertFrProperty(java.sql.Connection conn, String uuid, int version,
                                         String propName, byte[] value) throws java.sql.SQLException {
        try (java.sql.PreparedStatement upd = conn.prepareStatement(
                "UPDATE REPOSITORY_OBJECT_PROPERTIES SET PROPERTY_VALUE = ? "
                        + "WHERE OBJECT_UUID = ? AND PROPERTY_NAME = ? AND VERSION_NUMBER = ?")) {
            upd.setBytes(1, value);
            upd.setString(2, uuid);
            upd.setString(3, propName);
            upd.setInt(4, version);
            if (upd.executeUpdate() > 0) {
                return;
            }
        }
        try (java.sql.PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO REPOSITORY_OBJECT_PROPERTIES "
                        + "(OBJECT_UUID, PROPERTY_NAME, PROPERTY_VALUE, VERSION_NUMBER, VERSION_COPIED) "
                        + "VALUES (?, ?, ?, ?, 0)")) {
            ins.setString(1, uuid);
            ins.setString(2, propName);
            ins.setBytes(3, value);
            ins.setInt(4, version);
            ins.executeUpdate();
        }
    }

    private static DecodedText decodeFrBytes(byte[] bytes) {
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x50 && (bytes[1] & 0xFF) == 0x4B
                && (bytes[2] & 0xFF) == 0x03 && (bytes[3] & 0xFF) == 0x04) {
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(bytes))) {
                java.util.zip.ZipEntry entry = zis.getNextEntry();
                if (entry != null) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    DecodedText inner = decodeBytes(bos.toByteArray());
                    return new DecodedText(inner.text, "ZIP:" + inner.encoding);
                }
            } catch (IOException ignored) {
            }
        }
        return decodeBytes(bytes);
    }

    private static byte[] encodeFrBytes(String text, String encoding, boolean zipCompressed) throws BackendException {
        String innerEnc = encoding != null && encoding.toUpperCase(Locale.ROOT).startsWith("ZIP:")
                ? encoding.substring(4)
                : (encoding == null ? "UTF-8" : encoding);
        byte[] raw = encodeBytes(text, innerEnc);
        if (!zipCompressed) {
            return raw;
        }
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("compressed");
                zos.putNextEntry(entry);
                zos.write(raw);
                zos.closeEntry();
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BackendException(Kind.HFM_ERROR, "Failed to compress FR report payload: " + e.getMessage(), e);
        }
    }

    private static boolean isInternalFrFolder(String folderPath) {
        if (!notBlank(folderPath) || "Root".equalsIgnoreCase(folderPath)) {
            return false;
        }
        String norm = normalizeDocPath(folderPath);
        int slash = norm.indexOf('/');
        String top = slash > 0 ? norm.substring(0, slash) : norm;
        return FR_INTERNAL_TOP_FOLDERS.contains(top.toUpperCase(Locale.ROOT));
    }

    private static boolean matchesFolderFilter(String itemFolder, String filterFolder) {
        if (!notBlank(filterFolder) || "Root".equalsIgnoreCase(filterFolder)) {
            return true;
        }
        String normFilter = normalizeDocPath(filterFolder).toLowerCase(Locale.ROOT);
        String normItem = normalizeDocPath(itemFolder).toLowerCase(Locale.ROOT);
        return normItem.equals(normFilter) || normItem.startsWith(normFilter + "/");
    }

    private static boolean matchesReportQuery(ReportInfo ri, String query) {
        if (!notBlank(query)) {
            return true;
        }
        String raw = query.trim();
        if ("*".equals(raw)) {
            return true;
        }
        String name = ri.name == null ? "" : ri.name.toLowerCase(Locale.ROOT);
        String desc = ri.description == null ? "" : ri.description.toLowerCase(Locale.ROOT);
        String folder = ri.folder == null ? "" : ri.folder.toLowerCase(Locale.ROOT);
        String combined = folder + "/" + name + " " + desc;
        if (raw.contains("*")) {
            String regex = "\\Q" + raw.toLowerCase(Locale.ROOT).replace("*", "\\E.*\\Q") + "\\E";
            Pattern p = Pattern.compile(regex, Pattern.DOTALL);
            return p.matcher(name).matches() || p.matcher(desc).matches()
                    || p.matcher(folder).matches() || p.matcher(combined).find();
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (combined.contains(lower)) {
            return true;
        }
        String[] tokens = lower.split("\\s+");
        for (String tok : tokens) {
            if (tok.isEmpty()) {
                continue;
            }
            if (combined.contains(tok)) {
                continue;
            }
            // Handle common financial reporting abbreviations such as "Distribution" -> "Distrib" / "_DC"
            if (tok.length() >= 7 && combined.contains(tok.substring(0, 7))) {
                continue;
            }
            if (tok.startsWith("distrib") && (name.contains("_dc") || name.endsWith("dc"))) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static int reportTypePriority(String reportType) {
        if (reportType == null) {
            return 50;
        }
        switch (reportType.toUpperCase(Locale.ROOT)) {
            case "FR_BOOK":
            case "FR_REPORT":
            case "JOURNAL":
            case "INTERCOMPANY":
            case "IC_MATCHING_TEMPLATE":
            case "IC_TRANSACTION":
            case "IC_MONITOR":
            case "IC_MATCH_BY_ACCOUNT":
            case "IC_MATCH_BY_TRANS_ID":
            case "DATA_EXPLORER":
            case "HYP":
                return 10;
            case "FR_BATCH":
            case "FR_SNAPSHOT_BOOK":
            case "FR_SNAPSHOT_REPORT":
                return 20;
            case "FR_GRID":
            case "FR_ROW_COLUMN":
            case "FR_TEXT":
                return 30;
            case "FOLDER":
                return 40;
            default:
                return 50;
        }
    }

    private List<FMDocument> enumDocsAtPath(DocumentOM om, String normFolder, DOCUMENTTYPES dt,
                                            DOCUMENTFILETYPES ft, ENUMSHOWPRIVATEDOCS priv)
            throws HFMException, BackendException {
        String primary = toHfmDocPath(normFolder);
        try {
            List<FMDocument> list = withRetry(() -> om.enumDocuments(primary, dt, ft, priv));
            if (list != null && !list.isEmpty()) {
                return list;
            }
        } catch (HFMException e) {
            if (!"".equals(primary)) {
                throw e;
            }
        }
        if ("".equals(primary)) {
            try {
                List<FMDocument> alt = withRetry(() -> om.enumDocuments("Root", dt, ft, priv));
                if (alt != null) {
                    return alt;
                }
            } catch (HFMException ignored) {
            }
        }
        return Collections.emptyList();
    }

    private FMDocument getDocInfoAtPath(DocumentOM om, String normFolder, String name, DOCUMENTTYPES dt,
                                        DOCUMENTFILETYPES ft) throws HFMException, BackendException {
        String primary = toHfmDocPath(normFolder);
        try {
            FMDocument doc = withRetry(() -> om.getDocumentInfo(primary, name, dt, ft));
            if (doc != null) {
                return doc;
            }
        } catch (HFMException e) {
            if (!"".equals(primary)) {
                throw e;
            }
        }
        if ("".equals(primary)) {
            try {
                return withRetry(() -> om.getDocumentInfo("Root", name, dt, ft));
            } catch (HFMException ignored) {
            }
        }
        return null;
    }

    private byte[] getDocContentAtPath(DocumentOM om, String normFolder, String name, DOCUMENTTYPES dt,
                                       DOCUMENTFILETYPES ft) throws HFMException, BackendException {
        String primary = toHfmDocPath(normFolder);
        try {
            byte[] bytes = withRetry(() -> om.getDocumentContent(primary, name, dt, ft));
            if (bytes != null) {
                return bytes;
            }
        } catch (HFMException e) {
            if (!"".equals(primary)) {
                throw e;
            }
        }
        if ("".equals(primary)) {
            return withRetry(() -> om.getDocumentContent("Root", name, dt, ft));
        }
        return null;
    }

    private static String normalizeDocPath(String folder) {
        if (folder == null || folder.trim().isEmpty() || "/".equals(folder.trim())) {
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

    private static String toHfmDocPath(String folder) {
        String norm = normalizeDocPath(folder);
        return "Root".equalsIgnoreCase(norm) ? "" : norm;
    }

    private static String displayFolder(String folder) {
        return notBlank(folder) ? folder : "Root";
    }

    private static ReportInfo toReportInfo(FMDocument d, String defaultFolder) {
        String folder = notBlank(d.getPath()) ? normalizeDocPath(d.getPath()) : displayFolder(defaultFolder);
        return new ReportInfo(
                d.getName(),
                fromDocType(d.getDocType()),
                fromFileType(d.getFileType()),
                folder,
                d.getDescription(),
                d.getSecurityClass(),
                d.isIsPrivate(),
                d.getOwner(),
                d.getLastModified());
    }

    private static DOCUMENTTYPES toDocType(String reportType) throws BackendException {
        if (reportType == null || reportType.trim().isEmpty()) {
            throw new BackendException(Kind.BAD_REQUEST, "reportType is required");
        }
        String key = reportType.trim().toUpperCase(Locale.ROOT);
        DOCUMENTTYPES dt = REPORT_DOC_TYPES.get(key);
        if (dt != null) {
            return dt;
        }
        for (DOCUMENTTYPES c : DOCUMENTTYPES.values()) {
            if (c.name().equalsIgnoreCase(key) || c.name().equalsIgnoreCase("WEBOM_DOCTYPE_" + key)
                    || c.name().equalsIgnoreCase("WEBOM_DOCTYPE_RPT" + key.replace("_", ""))) {
                return c;
            }
        }
        throw new BackendException(Kind.BAD_REQUEST, "Unknown reportType '" + reportType + "'; expected one of "
                + String.join(", ", REPORT_DOC_TYPES.keySet()));
    }

    private static String fromDocType(DOCUMENTTYPES dt) {
        if (dt == null) {
            return "UNKNOWN";
        }
        for (Map.Entry<String, DOCUMENTTYPES> e : REPORT_DOC_TYPES.entrySet()) {
            if (e.getValue() == dt) {
                return e.getKey();
            }
        }
        String n = dt.name();
        return n.startsWith("WEBOM_DOCTYPE_") ? n.substring("WEBOM_DOCTYPE_".length()) : n;
    }

    private static DOCUMENTFILETYPES toFileType(String fileType) throws BackendException {
        if (!notBlank(fileType)) {
            return DOCUMENTFILETYPES.WEBOM_DOCFILETYPE_RPTDEF;
        }
        String key = fileType.trim().toUpperCase(Locale.ROOT);
        for (DOCUMENTFILETYPES ft : DOCUMENTFILETYPES.values()) {
            if (ft.name().equalsIgnoreCase(key) || ft.name().equalsIgnoreCase("WEBOM_DOCFILETYPE_" + key)) {
                return ft;
            }
        }
        throw new BackendException(Kind.BAD_REQUEST, "Unknown report fileType '" + fileType + "'");
    }

    private static String fromFileType(DOCUMENTFILETYPES ft) {
        if (ft == null) {
            return "RPTDEF";
        }
        String n = ft.name();
        return n.startsWith("WEBOM_DOCFILETYPE_") ? n.substring("WEBOM_DOCFILETYPE_".length()) : n;
    }

    private static REPORT_TYPES toSystemReportType(String reportType) {
        if (!notBlank(reportType)) {
            return REPORT_TYPES.JOURNAL_REPORT;
        }
        String key = reportType.trim().toUpperCase(Locale.ROOT);
        switch (key) {
            case "JOURNAL":
                return REPORT_TYPES.JOURNAL_REPORT;
            case "INTERCOMPANY":
            case "IC_MATCHING_TEMPLATE":
                return REPORT_TYPES.SYSTEM_MATCHING_REPORT;
            case "IC_MATCH_BY_ACCOUNT":
                return REPORT_TYPES.ICM_REPORT_TYPE_ACCOUNT;
            case "IC_MATCH_BY_TRANS_ID":
                return REPORT_TYPES.ICM_REPORT_TYPE_ID;
            case "IC_TRANSACTION":
            case "IC_MONITOR":
                return REPORT_TYPES.ICM_REPORT_TYPE_TRANSACTION;
            default:
                for (REPORT_TYPES rt : REPORT_TYPES.values()) {
                    if (rt.name().equalsIgnoreCase(key)) {
                        return rt;
                    }
                }
                return REPORT_TYPES.JOURNAL_REPORT;
        }
    }

    private static REPORT_FORMATS toSystemReportFormat(String format) throws BackendException {
        if (!notBlank(format)) {
            return REPORT_FORMATS.HTML_FORMAT;
        }
        String key = format.trim().toUpperCase(Locale.ROOT);
        for (REPORT_FORMATS rf : REPORT_FORMATS.values()) {
            if (rf.name().equalsIgnoreCase(key) || rf.name().equalsIgnoreCase(key + "_FORMAT")) {
                return rf;
            }
        }
        throw new BackendException(Kind.BAD_REQUEST, "Unsupported report format '" + format + "'");
    }

    private static final class DecodedText {
        final String text;
        final String encoding;

        DecodedText(String text, String encoding) {
            this.text = text;
            this.encoding = encoding;
        }
    }

    private static DecodedText decodeBytes(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new DecodedText(new String(bytes, 2, bytes.length - 2, java.nio.charset.StandardCharsets.UTF_16LE),
                    "UTF-16LE");
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new DecodedText(new String(bytes, 2, bytes.length - 2, java.nio.charset.StandardCharsets.UTF_16BE),
                    "UTF-16BE");
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new DecodedText(new String(bytes, 3, bytes.length - 3, java.nio.charset.StandardCharsets.UTF_8),
                    "UTF-8-BOM");
        }
        return new DecodedText(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), "UTF-8");
    }

    private static byte[] encodeBytes(String text, String encoding) {
        if ("UTF-16LE".equalsIgnoreCase(encoding)) {
            byte[] body = text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            byte[] out = new byte[body.length + 2];
            out[0] = (byte) 0xFF;
            out[1] = (byte) 0xFE;
            System.arraycopy(body, 0, out, 2, body.length);
            return out;
        }
        if ("UTF-16BE".equalsIgnoreCase(encoding)) {
            byte[] body = text.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
            byte[] out = new byte[body.length + 2];
            out[0] = (byte) 0xFE;
            out[1] = (byte) 0xFF;
            System.arraycopy(body, 0, out, 2, body.length);
            return out;
        }
        if ("UTF-8-BOM".equalsIgnoreCase(encoding)) {
            byte[] body = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] out = new byte[body.length + 3];
            out[0] = (byte) 0xEF;
            out[1] = (byte) 0xBB;
            out[2] = (byte) 0xBF;
            System.arraycopy(body, 0, out, 3, body.length);
            return out;
        }
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
