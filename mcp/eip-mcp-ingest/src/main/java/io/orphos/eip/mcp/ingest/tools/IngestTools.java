package io.orphos.eip.mcp.ingest.tools;

import io.orphos.eip.mcp.ingest.config.IngestMcpProperties;
import io.orphos.eip.mcp.ingest.service.IngestClient;
import io.orphos.eip.mcp.spec.McpTenant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Ingestion capabilities as MCP tools (TO-BE design §14). {@code ingest.list_jobs} is read;
 * {@code ingest.run_job} and {@code okg.trigger_sync} are advertised as write + approval, so an
 * ACT_WITH_APPROVAL agent's call is captured as a ProposedAction and only reaches the upstream after
 * a human approves. Those upstream endpoints are admin-gated. Tenant resolved via {@link McpTenant}.
 */
@Component
public class IngestTools {

    private final IngestClient client;
    private final IngestMcpProperties props;

    public IngestTools(IngestClient client, IngestMcpProperties props) {
        this.client = client;
        this.props = props;
    }

    @Tool(name = "ingest.list_jobs",
            description = "Lists the tenant's document ingestion source connections and their last-sync status.")
    public String listJobs() {
        return client.get(props.getDocIngestBaseUrl(), "/api/doc-ingest/admin/sources", tenant());
    }

    @Tool(name = "ingest.run_job",
            description = "Triggers a sync of a document ingestion source by id. WRITE: requires human approval.")
    public String runJob(
            @ToolParam(description = "The ingestion source/connection id.") String sourceId) {
        String path = "/api/doc-ingest/admin/sources/"
                + URLEncoder.encode(sourceId == null ? "" : sourceId, StandardCharsets.UTF_8) + "/sync";
        return client.postStrict(props.getDocIngestBaseUrl(), path, tenant(), "{}");
    }

    @Tool(name = "okg.trigger_sync",
            description = "Triggers an OKG ingestion/sync (e.g. Jira) for the tenant. WRITE: requires human approval.")
    public String triggerSync() {
        return client.postStrict(props.getOkgIngestBaseUrl(), "/api/okg-ingest/admin/sync", tenant(), "{}");
    }

    private String tenant() {
        return McpTenant.requireTenantId();
    }
}
