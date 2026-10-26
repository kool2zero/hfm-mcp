package com.hfmmcp.daemon.backend;

/** Which journals to list for one Scenario / Year / Period / Value. Null or blank fields do not filter. */
public final class JournalQuery {
    /** HFM status names (WORKING, SUBMITTED, APPROVED, REJECTED, POSTED); null for all. */
    public final java.util.List<String> statuses;
    /** HFM journal types (REGULAR, AUTOREVERSING, AUTOREVERSAL); null for all. */
    public final java.util.List<String> types;
    public final String label;
    public final String group;
    public final String entity;
    public final String description;

    public JournalQuery(java.util.List<String> statuses, java.util.List<String> types, String label, String group,
                        String entity, String description) {
        this.statuses = statuses;
        this.types = types;
        this.label = label;
        this.group = group;
        this.entity = entity;
        this.description = description;
    }
}
