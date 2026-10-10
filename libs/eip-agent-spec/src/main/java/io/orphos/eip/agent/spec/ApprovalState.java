package io.orphos.eip.agent.spec;

/**
 * Lifecycle of a {@link ProposedAction} in the approval store (TO-BE design §5.2, §8).
 * A write proposed by an {@code ACT_WITH_APPROVAL} agent starts {@link #PENDING}; a human
 * decision moves it to {@link #APPROVED} or {@link #REJECTED}; on execution it ends
 * {@link #EXECUTED} or {@link #FAILED}.
 */
public enum ApprovalState {
    PENDING,
    APPROVED,
    REJECTED,
    EXECUTED,
    FAILED
}
