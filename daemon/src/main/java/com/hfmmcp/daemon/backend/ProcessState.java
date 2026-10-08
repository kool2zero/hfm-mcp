package com.hfmmcp.daemon.backend;

/** Process-control state of one process unit and phase, with its most recent action. */
public final class ProcessState {
    private final String state;
    private final String lastAction;
    private final String lastUser;
    private final long lastTime;
    private final String lastComment;

    public ProcessState(String state, String lastAction, String lastUser, long lastTime, String lastComment) {
        this.state = state;
        this.lastAction = lastAction;
        this.lastUser = lastUser;
        this.lastTime = lastTime;
        this.lastComment = lastComment;
    }

    /** HFM's name for the state, e.g. "Not Started", "First Pass", "Review Level 1", "Submitted". */
    public String state() {
        return state;
    }

    public String lastAction() {
        return lastAction;
    }

    public String lastUser() {
        return lastUser;
    }

    /** Epoch milliseconds, or 0 when unknown. */
    public long lastTime() {
        return lastTime;
    }

    public String lastComment() {
        return lastComment;
    }
}
