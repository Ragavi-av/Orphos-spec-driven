package io.orphos.eip.registry.vo;

import io.orphos.eip.agent.spec.ToolDescriptor;

import java.util.List;

/**
 * MCP-server view returned by the registry (TO-BE design §6). The orchestrator uses
 * {@code tools} to resolve a tool <em>name</em> to this server's {@code mcpEndpoint} (invariant I2),
 * gated by {@code enabled} and filtered by heartbeat {@code status}.
 */
public record McpServerSummaryDto(
        String serverId,
        String mcpEndpoint,
        List<ToolDescriptor> tools,
        String healthUrl,
        String status,
        boolean enabled) {
}
