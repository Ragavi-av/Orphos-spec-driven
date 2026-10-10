package io.orphos.eip.agent.runtime.approval;

import io.orphos.eip.agent.spec.ProposedAction;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link ProposedAction}s (TO-BE design §5.3, §8). An {@code ACT_WITH_APPROVAL}
 * agent persists a PENDING proposal here; the approval inbox lists it; on approval the runtime
 * executes the write tool and transitions the state. {@code SUGGEST_ONLY} agents never touch it.
 */
public interface ApprovalStore {

    /** Persist a newly proposed action (typically PENDING). */
    ProposedAction save(ProposedAction action);

    /** Pending proposals for a tenant — backs the single approval inbox. */
    List<ProposedAction> findPending(UUID tenantId);

    Optional<ProposedAction> findById(UUID actionId);

    /** Transition a proposal's lifecycle state (APPROVED / REJECTED / EXECUTED / FAILED). */
    void updateState(UUID actionId, io.orphos.eip.agent.spec.ApprovalState state);

    /**
     * Replace a PENDING proposal's arguments (and human summary) — the edit-before-approve path.
     * The caller is responsible for having re-issued any content-bound confirmation token first.
     */
    void updateArguments(UUID actionId, Map<String, Object> arguments, String humanSummary);
}
