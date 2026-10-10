package io.orphos.eip.agent.spec;

import java.util.Map;
import java.util.UUID;

/**
 * The uniform invocation envelope POSTed to an agent's {@code /invoke} endpoint — identical for
 * every agent, so the orchestrator has one shape to send (TO-BE design §5.2, §8).
 *
 * @param tenantId      tenant the run executes for; the agent binds {@code TenantContextHolder} from it
 * @param agentId       which agent is being invoked
 * @param trigger       what fired this run (schedule / event / on-demand)
 * @param input         trigger-specific inputs (event payload, UI parameters, …)
 * @param correlationId ties this run's logs, tool calls, and proposed actions together
 */
public record AgentInvocation(
        UUID tenantId,
        String agentId,
        Trigger trigger,
        Map<String, Object> input,
        UUID correlationId) {
}
