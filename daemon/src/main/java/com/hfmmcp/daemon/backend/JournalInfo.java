package com.hfmmcp.daemon.backend;

/** One row of a journal listing. Times are epoch milliseconds or seconds, 0 when unknown. */
public final class JournalInfo {
    public final int id;
    public final String label;
    public final String description;
    public final String group;
    public final String status;
    public final String type;
    public final String balanceType;
    public final String entity;
    public final String parent;
    public final String value;
    public final String createdBy;
    public final long createdOn;
    public final String lastActedBy;
    public final long lastActedOn;
    public final String approvedBy;
    public final long approvedOn;

    public JournalInfo(int id, String label, String description, String group, String status, String type,
                       String balanceType, String entity, String parent, String value, String createdBy,
                       long createdOn, String lastActedBy, long lastActedOn, String approvedBy, long approvedOn) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.group = group;
        this.status = status;
        this.type = type;
        this.balanceType = balanceType;
        this.entity = entity;
        this.parent = parent;
        this.value = value;
        this.createdBy = createdBy;
        this.createdOn = createdOn;
        this.lastActedBy = lastActedBy;
        this.lastActedOn = lastActedOn;
        this.approvedBy = approvedBy;
        this.approvedOn = approvedOn;
    }
}
