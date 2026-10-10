package com.orphos.mobile.data.model;

/**
 * A "Task for Action" shown on the Tasks screen, mapped from a decision/priority item.
 * When {@code requiresApproval} is true the item was recognised (by keyword) as needing
 * an approve/reject response, and {@code id} points at the decision to transition.
 */
public class ActionTask {
    public String id;               // decision id (for approve/reject), may be null
    public String title;
    public String meta;
    public String kind;             // "AI REASONING" | "AI ADVICE"
    public String reason;
    public String secondary;        // secondary/outlined button label
    public String primary;          // primary button label ("Review & Approve" when approval)
    public boolean requiresApproval;
    public String severity;         // CRITICAL | HIGH | MEDIUM | LOW
    public String status;           // mail-insight task status: OPEN | IN_PROGRESS

    // Approval workflow (Tasks "Approval Required" filter)
    public String approvalType = "APPROVE_REJECT"; // YES_NO | GO_AHEAD | APPROVE_REJECT
    public String decision;         // recorded decision (APPROVE|REJECT|YES|NO|GO|HOLD)
    public Long decidedAt;          // epoch millis when decided
    public Long expiresAt;          // epoch millis approval deadline (optional)
    public String approvalStatus = "PENDING"; // PENDING | APPROVED | REJECTED | EXPIRED
    public String approvalId;       // BFF Approval envelope id linked to this task (sourceRef == id)

    // Presentation (Figma "ORPHOS-1" Tasks cards)
    public String icon;             // glyph key: "person-check" | "handshake" | "megaphone" | "people"
    public boolean singleAction;    // High-priority cards use one full-width outlined action
    public String actionLabel;      // label for the single full-width action

    public ActionTask() { }
}
