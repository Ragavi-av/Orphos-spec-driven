package io.orphos.eip.mcp.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the ingest MCP server (TO-BE design §7, §14). Wraps doc-ingest + okg-ingest. */
@ConfigurationProperties(prefix = "eip.mcp.ingest")
public class IngestMcpProperties {

    private String serverId = "eip-mcp-ingest";
    private String mcpEndpoint = "http://eip-mcp-ingest:8080/mcp";
    private String healthUrl = "http://eip-mcp-ingest:8080/actuator/health";
    private String registryBaseUrl = "http://eip-platform-registry:8093";
    private String internalToken = "";
    private boolean registerOnStartup = true;

    /** Upstream doc-ingest-service (ingestion sources / jobs). */
    private String docIngestBaseUrl = "http://eip-doc-ingest:8091";
    /** Upstream okg-ingest-service (OKG/Jira sync). */
    private String okgIngestBaseUrl = "http://eip-okg-ingest:8081";

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

    public String getDocIngestBaseUrl() { return docIngestBaseUrl; }
    public void setDocIngestBaseUrl(String docIngestBaseUrl) { this.docIngestBaseUrl = docIngestBaseUrl; }

    public String getOkgIngestBaseUrl() { return okgIngestBaseUrl; }
    public void setOkgIngestBaseUrl(String okgIngestBaseUrl) { this.okgIngestBaseUrl = okgIngestBaseUrl; }
}
