package io.orphos.eip.mcp.priority.config;

import io.orphos.eip.mcp.priority.constants.AppConstant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the priority MCP server (TO-BE design §7). {@code priorityEngine.baseUrl} is
 * the upstream deterministic engine this server wraps; the {@code registry*} + {@code server*}
 * values drive self-registration so the orchestrator can discover this server.
 */
@ConfigurationProperties(prefix = AppConstant.CONFIG_PREFIX)
public class PriorityMcpProperties {

    /** Stable server slug advertised at registration. */
    private String serverId = AppConstant.DEFAULT_SERVER_ID;

    /** This server's MCP-over-Streamable-HTTP endpoint (what the gateway connects to). */
    private String mcpEndpoint = AppConstant.DEFAULT_MCP_ENDPOINT;

    /** This server's health URL the registry heartbeat polls. */
    private String healthUrl = AppConstant.DEFAULT_HEALTH_URL;

    /** platform-registry base URL for self-registration. */
    private String registryBaseUrl = AppConstant.DEFAULT_REGISTRY_BASE_URL;

    /** Shared internal-service token. */
    private String internalToken = "";

    private boolean registerOnStartup = true;

    /** Upstream priority-engine-service base URL (wrapped, not re-implemented). */
    private String priorityEngineBaseUrl = AppConstant.DEFAULT_PRIORITY_ENGINE_BASE_URL;

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    public String getMcpEndpoint() { return mcpEndpoint; }
    public void setMcpEndpoint(String mcpEndpoint) { this.mcpEndpoint = mcpEndpoint; }

    public String getHealthUrl() { return healthUrl; }
    public void setHealthUrl(String healthUrl) { this.healthUrl = healthUrl; }

    public String getRegistryBaseUrl() { return registryBaseUrl; }
    public void setRegistryBaseUrl(String registryBaseUrl) { this.registryBaseUrl = registryBaseUrl; }

    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public boolean isRegisterOnStartup() { return registerOnStartup; }
    public void setRegisterOnStartup(boolean registerOnStartup) { this.registerOnStartup = registerOnStartup; }

    public String getPriorityEngineBaseUrl() { return priorityEngineBaseUrl; }
    public void setPriorityEngineBaseUrl(String priorityEngineBaseUrl) { this.priorityEngineBaseUrl = priorityEngineBaseUrl; }
}
