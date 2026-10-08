package com.hfmmcp.daemon.backend;

/** Progress of a server task such as a consolidation. */
public final class TaskProgress {
    public enum Status { SCHEDULED, RUNNING, COMPLETED, ABORTED, STOPPED, UNKNOWN }

    private final int taskId;
    private final Status status;
    private final int percentComplete;
    private final String description;

    public TaskProgress(int taskId, Status status, int percentComplete, String description) {
        this.taskId = taskId;
        this.status = status;
        this.percentComplete = percentComplete;
        this.description = description;
    }

    public int taskId() {
        return taskId;
    }

    public Status status() {
        return status;
    }

    public int percentComplete() {
        return percentComplete;
    }

    public String description() {
        return description;
    }

    /** Not running any more. UNKNOWN counts: HFM stops reporting a task once it is gone. */
    public boolean finished() {
        return status != Status.SCHEDULED && status != Status.RUNNING;
    }
}
