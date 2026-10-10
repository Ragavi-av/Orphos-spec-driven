package io.orphos.eip.mcp.reasoning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the reasoning MCP server (TO-BE design §7, §14). Wraps okg-reasoning-service. */
@ConfigurationProperties(prefix = "eip.mcp.reasoning")
public class ReasoningMcpProperties {

    private String serverId = "eip-mcp-reasoning";
    private String mcpEndpoint = "http://eip-mcp-reasoning:8080/mcp";
    private String healthUrl = "http://eip-mcp-reasoning:8080/actuator/health";
    private String registryBaseUrl = "http://eip-platform-registry:8093";
    private String internalToken = "";
    private boolean registerOnStartup = true;

    /** Upstream okg-reasoning-service (insights + reasoning cycle). */
    private String okgReasoningBaseUrl = "http://eip-okg-reasoning:8088";

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

    public String getOkgReasoningBaseUrl() { return okgReasoningBaseUrl; }
    public void setOkgReasoningBaseUrl(String okgReasoningBaseUrl) { this.okgReasoningBaseUrl = okgReasoningBaseUrl; }
}
