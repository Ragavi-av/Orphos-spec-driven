package io.orphos.eip.mcp.decision.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mcp.decision.service.DecisionLogClient;
import io.orphos.eip.mcp.spec.McpTenant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * decision-log capabilities as MCP tools (TO-BE design §14). {@code decision.search_decisions} is
 * read-only; {@code decision.log_decision} is a write that records a decision. The write is
 * advertised as write+approval-required, so an ACT_WITH_APPROVAL agent's call is captured as a
 * ProposedAction and only reaches decision-log after a human approves (Phase 3 flow).
 */
@Component
public class DecisionTools {

    private final DecisionLogClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public DecisionTools(DecisionLogClient client) {
        this.client = client;
    }

    @Tool(name = "decision.search_decisions",
            description = "Searches the tenant's decision log. Optionally filter by a free-text query.")
    public String searchDecisions(
            @ToolParam(required = false, description = "Free-text search query; omit to list recent decisions.")
            String query) {
        String path = "/api/decisions";
        if (query != null && !query.isBlank()) {
            path += "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        }
        return client.get(path, tenant());
    }

    @Tool(name = "decision.log_decision",
            description = "Records a new decision in the decision log. WRITE: requires human approval before it runs.")
    public String logDecision(
            @ToolParam(description = "Short title of the decision.") String title,
            @ToolParam(description = "Summary / rationale of the decision.") String summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("summary", summary);
        return client.post("/api/decisions", tenant(), writeJson(body));
    }

    /** The tenant this call may act on, bound to the authenticated principal (design §9). */
    private String tenant() {
        return McpTenant.requireTenantId();
    }

    /**
     * Serializes a request body. Failing loudly rather than falling back to {@code "{}"}: an empty
     * body on the write path would post a decision with no title and no summary to decision-log and
     * report it as success.
     */
    private String writeJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the decision-log request body: "
                    + e.getMessage(), e);
        }
    }
}
