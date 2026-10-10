package io.orphos.eip.agent.vo;

import io.orphos.eip.agent.spec.Trigger;

/**
 * A live agent as discovered from the registry's {@code /agents/active} endpoint (TO-BE design §6),
 * reduced to what the orchestrator needs to route a trigger.
 *
 * @param agentId  agent slug
 * @param baseUrl  where to POST {@code /invoke}
 * @param trigger  the agent's declared trigger (schedule / event / on-demand)
 * @param degraded whether the registry flagged the agent DEGRADED (missing capabilities) for the tenant
 */
public record DiscoveredAgent(String agentId, String baseUrl, Trigger trigger, boolean degraded) {
}
