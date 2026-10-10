package io.orphos.eip.agent.runtime;

import java.util.List;

/**
 * A live MCP tool server as discovered from the registry's {@code /mcp-servers/active} endpoint
 * (TO-BE design §6). The gateway builds one MCP client per server and resolves tool names against
 * the union of every server's advertised tools (invariant I2); the per-tool write/approval flags
 * drive the write-path (design §8).
 *
 * @param serverId    stable server slug
 * @param mcpEndpoint MCP-over-Streamable-HTTP endpoint to connect to
 * @param tools       the tools this server advertises, with their write/approval classification
 */
public record DiscoveredServer(String serverId, String mcpEndpoint, List<DiscoveredTool> tools) {

    /** Names of tools that mutate state (design §8: routed through approval for ACT_WITH_APPROVAL). */
    public List<String> writeToolNames() {
        return tools.stream().filter(DiscoveredTool::write).map(DiscoveredTool::name).toList();
    }
}
