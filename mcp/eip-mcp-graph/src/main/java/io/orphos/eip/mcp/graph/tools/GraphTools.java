package io.orphos.eip.mcp.graph.tools;

import io.orphos.eip.mcp.graph.service.GraphProxyClient;
import io.orphos.eip.mcp.spec.McpTenant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Knowledge-graph query capability as an MCP tool (TO-BE design §14). Read-only: forwards a SPARQL
 * SELECT/ASK/CONSTRUCT query to graph-proxy-service, which scopes it to the tenant's graph. Updates
 * are rejected upstream. The tenant is resolved securely from the authenticated principal.
 */
@Component
public class GraphTools {

    private final GraphProxyClient client;

    public GraphTools(GraphProxyClient client) {
        this.client = client;
    }

    @Tool(name = "graph.sparql_query",
            description = "Runs a read-only SPARQL query (SELECT/ASK/CONSTRUCT/DESCRIBE) over the tenant's "
                    + "knowledge graph and returns SPARQL-results JSON. Update queries are not permitted.")
    public String sparqlQuery(
            @ToolParam(description = "A read-only SPARQL query string.") String query) {
        return client.query(query, McpTenant.requireTenantId());
    }
}
