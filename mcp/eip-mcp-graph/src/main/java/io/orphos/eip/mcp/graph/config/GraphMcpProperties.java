package io.orphos.eip.mcp.graph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the graph MCP server (TO-BE design §7, §14). Wraps graph-proxy-service. */
@ConfigurationProperties(prefix = "eip.mcp.graph")
public class GraphMcpProperties {

    private String serverId = "eip-mcp-graph";
    private String mcpEndpoint = "http://eip-mcp-graph:8080/mcp";
    private String healthUrl = "http://eip-mcp-graph:8080/actuator/health";
    private String registryBaseUrl = "http://eip-platform-registry:8093";
    private String internalToken = "";
    private boolean registerOnStartup = true;

    /** Upstream graph-proxy-service (SPARQL over the tenant's graph). */
    private String graphProxyBaseUrl = "http://eip-graph-proxy:8080";

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

    public String getGraphProxyBaseUrl() { return graphProxyBaseUrl; }
    public void setGraphProxyBaseUrl(String graphProxyBaseUrl) { this.graphProxyBaseUrl = graphProxyBaseUrl; }
}
