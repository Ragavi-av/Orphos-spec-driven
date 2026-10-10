package io.orphos.eip.agent.spec;

/**
 * How far an agent may act on its own (TO-BE design §5.2, §8).
 *
 * <ul>
 *   <li>{@link #SUGGEST_ONLY} — never creates a {@link ProposedAction}; read-only output only.</li>
 *   <li>{@link #ACT_WITH_APPROVAL} — every write is routed through the approval store as a
 *       {@link ProposedAction} and executed only after a human approves.</li>
 *   <li>{@link #AUTONOMOUS} — executes writes directly; reserved for low-risk, well-bounded
 *       tools and off by default.</li>
 * </ul>
 */
public enum Autonomy {
    SUGGEST_ONLY,
    ACT_WITH_APPROVAL,
    AUTONOMOUS
}
