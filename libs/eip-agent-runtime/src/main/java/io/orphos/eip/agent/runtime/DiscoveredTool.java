package io.orphos.eip.agent.runtime;

/**
 * One tool advertised by a discovered MCP server, with the classification the write/approval path
 * needs (TO-BE design §5.1, §8).
 *
 * @param name             fully-qualified {@code capability.action} tool name
 * @param write            {@code true} if the tool mutates state
 * @param approvalRequired {@code true} if executing it must go through the approval store
 * @param prepareTool      companion tool that (re)issues this tool's confirmation token, or {@code null}
 * @param confirmationTokenField argument key the token goes into, or {@code null}
 */
public record DiscoveredTool(String name, boolean write, boolean approvalRequired,
                             String prepareTool, String confirmationTokenField) {
}
