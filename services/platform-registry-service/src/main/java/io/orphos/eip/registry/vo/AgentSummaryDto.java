package io.orphos.eip.registry.vo;

import io.orphos.eip.agent.spec.AgentManifest;

import java.util.List;

/**
 * Agent view returned by the registry (TO-BE design §6). Carries the manifest plus the runtime
 * facts the orchestrator/admin UI need: heartbeat {@code status}, whether the agent is
 * {@code enabled} for the tenant, and {@code degraded} + {@code missingCapabilities} when some
 * required tool is not advertised by any healthy, enabled MCP server for the tenant.
 */
public record AgentSummaryDto(
        String agentId,
        String displayName,
        String version,
        AgentManifest manifest,
        String baseUrl,
        String healthUrl,
        String status,
        boolean enabled,
        boolean degraded,
        List<String> missingCapabilities) {
}
