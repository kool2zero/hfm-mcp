package com.hfmmcp.daemon.backend;

/** Workflow steps for a journal, as {@code JournalOM.performJournalAction} takes them. */
public enum JournalAction {
    SUBMIT, APPROVE, POST, UNPOST, UNSUBMIT, REJECT, DELETE
}
