package io.orphos.eip.mcp.mail.config;

import io.orphos.eip.mcp.mail.constants.AppConstant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the mail MCP server (TO-BE design §7, §14). Wraps mail-insight-service. */
@ConfigurationProperties(prefix = AppConstant.CONFIG_PREFIX)
public class MailMcpProperties {

    private String serverId = AppConstant.DEFAULT_SERVER_ID;
    private String mcpEndpoint = AppConstant.DEFAULT_MCP_ENDPOINT;
    private String healthUrl = AppConstant.DEFAULT_HEALTH_URL;
    private String registryBaseUrl = AppConstant.DEFAULT_REGISTRY_BASE_URL;
    private String internalToken = "";
    private boolean registerOnStartup = true;

    /** Upstream mail-insight-service. */
    private String mailInsightBaseUrl = AppConstant.DEFAULT_MAIL_INSIGHT_BASE_URL;

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
