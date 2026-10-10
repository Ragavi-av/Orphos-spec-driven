package com.orphos.mobile.data.model;

/**
 * An approval the executive must action, as served by the mobile BFF (/api/mobile/approvals).
 * Auto-inferred and persisted server-side; the only user-entered part is {@link #decision}.
 * {@link #sourceRef} points back at the originating decision, so a decision-derived task can
 * be matched to its approval envelope for the decision write.
 */
public class ApprovalItem {
    public String id;               // approval envelope id — the id used to POST a decision
    public String sourceId;         // e.g. "decision-<id>"
    public String sourceRef;        // originating decision id
    public String source;           // EMAIL | MS_TEAMS | SLACK | DECISION | DEMO
    public String subject;
    public String summary;
    public String approvalType;     // APPROVE_REJECT | GO_AHEAD | YES_NO
    public String status;           // PENDING | APPROVED | REJECTED | EXPIRED
    public String decision;         // APPROVE | REJECT | YES | NO | GO | HOLD (once decided)

    public boolean isPending() { return "PENDING".equalsIgnoreCase(status); }

    public ApprovalItem() { }
}
