package io.orphos.eip.registry.entity;

import java.time.Instant;

/**
 * A registered MCP tool server (TO-BE design §6). A server self-registers its MCP endpoint and the
 * tools it advertises; the orchestrator resolves a tool <em>name</em> to whichever healthy server
 * advertises it (invariant I2) via {@code GET /api/platform/mcp-servers/active}.
 *
 * <p>Persisted as the {@code McpServer} document type in ArcadeDB, keyed by a unique
 * {@code serverId} index — a plain POJO; the row mapping lives in {@code McpServerRepository}.
 * The advertised tools are stored as {@code toolsJson} (a JSON array of
 * {@link io.orphos.eip.agent.spec.ToolDescriptor}).</p>
 */
public class McpServerDoc {

    /** Stable slug: "eip-mcp-priority". */
    private String serverId;

    /** MCP-over-Streamable-HTTP endpoint. */
    private String mcpEndpoint;

    /** JSON array of advertised ToolDescriptors. */
    private String toolsJson;

    /** Absolute health-probe URL. */
    private String healthUrl;

    /** Heartbeat status: UP / DOWN / UNKNOWN (owned by the heartbeat poller). */
    private String status = "UNKNOWN";

    private String lastSeenAt;

    private Instant registeredAt = Instant.now();

    public McpServerDoc() {}

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    public String getMcpEndpoint() { return mcpEndpoint; }
    public void setMcpEndpoint(String mcpEndpoint) { this.mcpEndpoint = mcpEndpoint; }

    public String getToolsJson() { return toolsJson; }
    public void setToolsJson(String toolsJson) { this.toolsJson = toolsJson; }

    public String getHealthUrl() { return healthUrl; }
    public void setHealthUrl(String healthUrl) { this.healthUrl = healthUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(String lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
}
