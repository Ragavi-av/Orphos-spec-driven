package io.orphos.eip.mcp.rag.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mcp.rag.config.RagMcpProperties;
import io.orphos.eip.mcp.rag.service.HttpClientSupport;
import io.orphos.eip.mcp.spec.McpTenant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG capabilities as MCP tools (TO-BE design §14). All read-only: document search + retrieval wrap
 * doc-ingest-service; grounded answering wraps nl-query-service's {@code /api/nl-query/ask}. The
 * tenant is resolved securely from the authenticated principal via {@link McpTenant}.
 */
@Component
public class RagTools {

    private final HttpClientSupport http;
    private final RagMcpProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public RagTools(HttpClientSupport http, RagMcpProperties props) {
        this.http = http;
        this.props = props;
    }

    @Tool(name = "rag.search_documents",
            description = "Semantic search over the tenant's ingested documents. Returns ranked passages with citations.")
    public String searchDocuments(
            @ToolParam(description = "Natural-language search query.") String query,
            @ToolParam(required = false, description = "Max results to return (default 5).") Integer topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("topK", topK == null ? 5 : topK);
        return http.postJson(props.getDocIngestBaseUrl(), "/api/doc-ingest/search", tenant(), writeJson(body));
    }

    @Tool(name = "rag.get_document",
            description = "Fetches a single ingested document (metadata + registry entry) by its id.")
    public String getDocument(
            @ToolParam(description = "The document id.") String documentId) {
        String path = "/api/doc-ingest/documents/" + URLEncoder.encode(documentId, StandardCharsets.UTF_8);
        return http.get(props.getDocIngestBaseUrl(), path, tenant());
    }

    @Tool(name = "rag.answer",
            description = "Answers a question grounded in the tenant's documents (RAG). Returns the answer plus a trust/grounding envelope.")
    public String answer(
            @ToolParam(description = "The question to answer (<= 500 chars).") String question) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        return http.postJson(props.getNlQueryBaseUrl(), "/api/nl-query/ask", tenant(), writeJson(body));
    }

    private String tenant() {
        return McpTenant.requireTenantId();
    }

    /**
     * Serializes a request body. Failing loudly rather than falling back to {@code "{}"}: an empty
     * body would send doc-ingest a search with no query, or nl-query a request with no question,
     * and the caller would read the resulting empty result set as a genuine answer.
     */
    private String writeJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the RAG request body: " + e.getMessage(), e);
        }
    }
}
