package io.orphos.eip.agent.spec;

/**
 * Self-registration payload an agent POSTs to the registry at startup (TO-BE design §5.2, §6).
 * Mirrors plugin-manifest registration: the manifest plus where to reach and health-check the agent.
 *
 * @param manifest  the agent's declared {@link AgentManifest}
 * @param baseUrl   base URL the orchestrator posts {@code /invoke} to
 * @param healthUrl URL the registry heartbeat polls to mark the agent UP/DOWN
 */
public record AgentRegistration(
        AgentManifest manifest,
        String baseUrl,
        String healthUrl) {
}
