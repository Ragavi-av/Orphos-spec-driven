package io.orphos.eip.mcp.comms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the comms MCP server. Wraps the channel APIs of mail-insight-service. */
@ConfigurationProperties(prefix = "eip.mcp.comms")
public class CommsMcpProperties {

    private String serverId = "eip-mcp-comms";
    private String mcpEndpoint = "http://eip-mcp-comms:8080/mcp";
    private String healthUrl = "http://eip-mcp-comms:8080/actuator/health";
    private String registryBaseUrl = "http://eip-platform-registry:8093";
    private String internalToken = "";
    private boolean registerOnStartup = true;

    /** Upstream mail-insight-service (serves both the feed and the channel agent APIs). */
    private String mailInsightBaseUrl = "http://eip-mail-insight:8092";

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

    public String getMailInsightBaseUrl() { return mailInsightBaseUrl; }
    public void setMailInsightBaseUrl(String mailInsightBaseUrl) { this.mailInsightBaseUrl = mailInsightBaseUrl; }
}
