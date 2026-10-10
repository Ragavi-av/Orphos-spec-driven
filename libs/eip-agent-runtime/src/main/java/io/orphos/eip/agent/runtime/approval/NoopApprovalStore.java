package io.orphos.eip.agent.runtime.approval;

import io.orphos.eip.agent.spec.ApprovalState;
import io.orphos.eip.agent.spec.ProposedAction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fallback {@link ApprovalStore} used when the agent has no ArcadeDB configured (e.g. a
 * SUGGEST_ONLY agent like Briefing). Saving is logged and dropped so a mis-configured
 * write-capable agent fails loudly rather than silently pretending an action was queued.
 */
public class NoopApprovalStore implements ApprovalStore {

    private static final Logger log = LogManager.getLogger(NoopApprovalStore.class);

    @Override
    public ProposedAction save(ProposedAction action) {
        log.warn("No approval store configured (no arcadedb.url) — dropping proposed action {} for tool {}. "
                + "A write-capable agent must configure ArcadeDB.", action.actionId(), action.toolName());
        return action;
    }

    @Override
    public List<ProposedAction> findPending(UUID tenantId) {
        return List.of();
    }

    @Override
    public Optional<ProposedAction> findById(UUID actionId) {
        return Optional.empty();
    }

    @Override
    public void updateState(UUID actionId, ApprovalState state) {
        // no-op
    }

    @Override
    public void updateArguments(UUID actionId, Map<String, Object> arguments, String humanSummary) {
        // no-op
    }
}
