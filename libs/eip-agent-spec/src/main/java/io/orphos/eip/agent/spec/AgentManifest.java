package io.orphos.eip.agent.spec;

import java.util.List;

/**
 * Everything the platform needs to know about an agent, declared by the agent itself and
 * registered at startup (TO-BE design §5.2). The orchestrator resolves
 * {@link #requiredCapabilities()} / {@link #optionalCapabilities()} — which are <em>tool names</em>,
 * not module references — to whatever healthy MCP server advertises them, satisfying invariant I2
 * (no agent depends on a specific MCP-server module).
 *
 * @param agentId              stable slug, e.g. {@code "briefing"}, {@code "email-triage"}
 * @param version              manifest/version of the deployed agent
 * @param displayName          human-facing name for the admin UI
 * @param goalPromptKey        key resolved from the nl-query prompt store — the goal prompt is
 *                             never hardcoded in the agent
 * @param requiredCapabilities tool names the agent must have to function; if any is unavailable
 *                             for the tenant the registry marks the agent DEGRADED
 * @param optionalCapabilities tool names the agent can use when present but does not require
 * @param trigger              what fires the agent — {@link Trigger.Schedule}/{@link Trigger.Event}/{@link Trigger.OnDemand}
 * @param autonomy             how far it may act on its own
 * @param plannerUsageType     nl-query usage type for planner model assignment, e.g. {@code "agent-planning"}
 */
public record AgentManifest(
        String agentId,
        String version,
        String displayName,
        String goalPromptKey,
        List<String> requiredCapabilities,
        List<String> optionalCapabilities,
        Trigger trigger,
        Autonomy autonomy,
        String plannerUsageType) {
}
