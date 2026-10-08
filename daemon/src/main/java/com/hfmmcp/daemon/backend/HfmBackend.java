package com.hfmmcp.daemon.backend;

import java.util.List;

/**
 * The narrow slice of HFM the daemon needs. {@code OracleHfmBackend} implements it with the HFM
 * Java API; {@code MockHfmBackend} implements it in memory for development and tests.
 *
 * <p>Callers serialize access per {@link BackendSession}; implementations need not be thread-safe
 * for a single session but must allow different sessions to be used concurrently.
 */
public interface HfmBackend {

    /** Authenticates against Shared Services (CSS), which delegates to LDAP for LDAP users. */
    AuthResult authenticate(String userName, char[] password) throws BackendException;

    /** Opens an HFM session on {@code application} for an already authenticated user. */
    BackendSession openSession(AuthResult auth, String application) throws BackendException;

    /** Closes the HFM session. Must not throw. */
    void closeSession(BackendSession session);

    /** Dimensions in POV-string order, each with its default-POV member. */
    List<DimensionInfo> getDimensions(BackendSession session) throws BackendException;

    /**
     * Resolves a member or member-list expression for one dimension, honouring the user's security.
     *
     * @param expression a member name or an HFM member list such as {@code {Group.[Base]}}
     */
    List<MemberInfo> expandMembers(BackendSession session, String dimensionName, String expression)
            throws BackendException;

    /** Reads cells by fully qualified POV string, e.g. {@code S#Actual.Y#2024.P#Jan...}. */
    List<CellResult> getCells(BackendSession session, List<String> povs) throws BackendException;

    // ---------------------------------------------------------------- process control

    /**
     * Process-control state of one process unit for one phase.
     *
     * @param unitPov scenario, year, period, value and entity, e.g. {@code S#Actual.Y#2025.P#Dec.V#<Entity Currency>.E#Group.UK}
     * @param phase   submission phase, 1 to 9
     */
    ProcessState getProcessState(BackendSession session, String unitPov, int phase) throws BackendException;

    /**
     * Runs a process-management action on one process unit for one phase. Throws when HFM refuses
     * it (wrong level, no rights, ...), with HFM's message.
     *
     * @param promotionLevel review level for {@link ProcessAction#PROMOTE}, 1 to 10; ignored otherwise
     * @param includeDescendants also act on the entity's descendants
     */
    void runProcessAction(BackendSession session, ProcessAction action, String unitPov, int phase,
                          int promotionLevel, boolean includeDescendants, String comment) throws BackendException;

    /**
     * Runs a server task (consolidation, calculation, translation). Returns the ids of the
     * background tasks it started; empty when it already finished within the call.
     */
    List<Integer> startServerTask(BackendSession session, ServerTask task, List<String> povs) throws BackendException;

    List<TaskProgress> getTaskProgress(BackendSession session, List<Integer> taskIds) throws BackendException;

    // ---------------------------------------------------------------- data management

    /** Starts an Extended Analytics extract; returns its task id. */
    int startExtract(BackendSession session, ExtractRequest request) throws BackendException;

    /**
     * Downloads the file of a finished flat-file extract into {@code targetDir} (unzipping it) and
     * returns the files written.
     */
    List<java.nio.file.Path> fetchExtractFile(BackendSession session, int taskId, java.nio.file.Path targetDir)
            throws BackendException;

    /** Starts a data-file load (or scan); returns its task ids. */
    List<Integer> startLoad(BackendSession session, LoadRequest request) throws BackendException;

    /** Text of a finished task's log, or null when there is none. */
    String fetchTaskLog(BackendSession session, int taskId) throws BackendException;

    /** Copies data between slices; synchronous. Returns HFM's log path for the copy (may be null). */
    String copyData(BackendSession session, CopyRequest request) throws BackendException;
}
