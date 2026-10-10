package io.orphos.eip.agent.runtime;

import java.util.List;
import java.util.UUID;

/**
 * The read-only outcome an agent returns from {@code /invoke} (TO-BE design §8, read-only path).
 * Write-capable agents additionally persist {@link io.orphos.eip.agent.spec.ProposedAction}s to the
 * approval store (Phase 3); this envelope carries the suggestion/briefing text plus run metadata.
 *
 * @param agentId       the agent that produced the result
 * @param correlationId ties the result to the invocation's logs and tool calls
 * @param output        the produced text (briefing / suggestions / answer)
 * @param toolServers   how many MCP servers were reachable for this run (0 → answered without tools)
 * @param proposedActionIds ids of any actions queued for approval (empty for SUGGEST_ONLY)
 */
public record AgentResult(
        String agentId,
        UUID correlationId,
        String output,
        int toolServers,
        List<UUID> proposedActionIds) {

    public static AgentResult suggestion(String agentId, UUID correlationId, String output, int toolServers) {
        return new AgentResult(agentId, correlationId, output, toolServers, List.of());
    }
}
