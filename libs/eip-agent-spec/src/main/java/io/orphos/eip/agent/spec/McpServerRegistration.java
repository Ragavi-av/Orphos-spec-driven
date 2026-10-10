package io.orphos.eip.agent.spec;

import java.util.List;

/**
 * Self-registration payload an MCP tool server POSTs to the registry at startup
 * (TO-BE design §5.2, §6). The advertised {@link ToolDescriptor tools} are how the orchestrator
 * resolves a tool <em>name</em> to a live server (invariant I2).
 *
 * @param serverId    stable slug, e.g. {@code "eip-mcp-priority"}
 * @param mcpEndpoint the server's MCP-over-Streamable-HTTP endpoint
 * @param tools       the tools this server advertises
 * @param healthUrl   URL the registry heartbeat polls to mark the server UP/DOWN
 */
public record McpServerRegistration(
        String serverId,
        String mcpEndpoint,
        List<ToolDescriptor> tools,
        String healthUrl) {
}
