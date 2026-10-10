package io.orphos.eip.mcp.reasoning.tools;

import io.orphos.eip.mcp.reasoning.service.OkgReasoningClient;
import io.orphos.eip.mcp.spec.McpTenant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * OKG reasoning capabilities as MCP tools (TO-BE design §14). {@code okg.get_insights} is read-only;
 * {@code okg.run_reasoning} triggers a reasoning cycle and is advertised as write + approval, so an
 * ACT_WITH_APPROVAL agent's call is captured as a ProposedAction and only reaches okg-reasoning after
 * a human approves. (The upstream cycle endpoint is admin-gated — see the reasoning client.) Tenant
 * is resolved securely from the authenticated principal.
 */
@Component
public class ReasoningTools {

    private final OkgReasoningClient client;

    public ReasoningTools(OkgReasoningClient client) {
        this.client = client;
    }

    @Tool(name = "okg.get_insights",
            description = "Returns the tenant's current OKG insights grouped by type (e.g. overdue issues, "
                    + "blocked issues, overloaded people).")
    public String getInsights() {
        return client.get("/api/okg-reasoning/insights", McpTenant.requireTenantId());
    }

    @Tool(name = "okg.run_reasoning",
            description = "Triggers an OKG reasoning cycle for the tenant and returns the cycle result. "
                    + "WRITE: requires human approval before it runs.")
    public String runReasoning() {
        return client.post("/api/okg-reasoning/admin/run", McpTenant.requireTenantId(), "{}");
    }
}
