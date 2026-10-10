package io.orphos.eip.agent.spec;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The uniform proposed-action / approval envelope — one shape for ALL agents, so the UI has a
 * single approval inbox regardless of which agent proposed the write (TO-BE design §5.2, §8).
 *
 * @param actionId     stable id for this proposal
 * @param tenantId     tenant the action belongs to
 * @param agentId      agent that proposed it
 * @param toolName     the write tool to run on approval, e.g. {@code "mail.send_email"}
 * @param arguments    arguments to pass to that tool when executed
 * @param humanSummary short human-readable description shown in the approval inbox
 * @param proposedAt   when the proposal was created
 * @param state        current lifecycle state
 */
public record ProposedAction(
        UUID actionId,
        UUID tenantId,
        String agentId,
        String toolName,
        Map<String, Object> arguments,
        String humanSummary,
        Instant proposedAt,
        ApprovalState state) {
}
