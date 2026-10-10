package io.orphos.eip.mcp.decision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the decision MCP server (TO-BE design §7, §14). */
@ConfigurationProperties(prefix = "eip.mcp.decision")
public class DecisionMcpProperties {

    private String serverId = "eip-mcp-decision";
    private String mcpEndpoint = "http://eip-mcp-decision:8080/mcp";
    private String healthUrl = "http://eip-mcp-decision:8080/actuator/health";
    private String registryBaseUrl = "http://eip-platform-registry:8080";
    private String internalToken = "";
    private boolean registerOnStartup = true;

    /** Upstream decision-log-service base URL (wrapped, not re-implemented). */
    private String decisionLogBaseUrl = "http://eip-decision-log:8080";

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

    public String getDecisionLogBaseUrl() { return decisionLogBaseUrl; }
    public void setDecisionLogBaseUrl(String decisionLogBaseUrl) { this.decisionLogBaseUrl = decisionLogBaseUrl; }
}
