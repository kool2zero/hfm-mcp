package com.hfmmcp.daemon.backend;

import java.util.List;
import java.util.Map;

/** A journal with its POV and line items, in the form the backend reads and saves it. */
public final class JournalDetail {
    /** One line item. {@code dimensions} maps HFM dimension name to member (Entity, Account, ICP, customs). */
    public static final class Line {
        public final int index;
        public final String description;
        /** Amount as HFM holds it (a decimal string). */
        public final String amount;
        /** DEBIT or CREDIT. */
        public final String side;
        public final Map<String, String> dimensions;

        public Line(int index, String description, String amount, String side, Map<String, String> dimensions) {
            this.index = index;
            this.description = description;
            this.amount = amount;
            this.side = side;
            this.dimensions = dimensions;
        }
    }

    public final int id;
    public final String label;
    public final String description;
    public final String group;
    public final String status;
    public final String type;
    public final String balanceType;
    public final String singleEntity;
    /** Scenario, Year, Period and Value by dimension name. */
    public final Map<String, String> pov;
    public final List<Line> lines;

    public JournalDetail(int id, String label, String description, String group, String status, String type,
                         String balanceType, String singleEntity, Map<String, String> pov, List<Line> lines) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.group = group;
        this.status = status;
        this.type = type;
        this.balanceType = balanceType;
        this.singleEntity = singleEntity;
        this.pov = pov;
        this.lines = lines;
    }
}
