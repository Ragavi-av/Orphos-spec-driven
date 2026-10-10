package io.orphos.eip.mcp.priority.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mcp.priority.constants.AppConstant;
import io.orphos.eip.mcp.priority.exception.EIPMCPException;
import io.orphos.eip.mcp.priority.service.PriorityService;
import io.orphos.eip.mcp.spec.McpTenant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * The priority-engine capabilities exposed as MCP tools (TO-BE design §14). Each tool wraps the
 * corresponding read-only priority-engine endpoint; the tenant is resolved securely from the
 * authenticated principal via {@link McpTenant} (design §5.1, §9) — never trusted from a bare header
 * — and forwarded upstream. All three tools are read-only — no approval required.
 *
 * <p>This layer is deliberately thin: resolve the tenant, delegate to {@link PriorityService}, and
 * turn the result — success or failure — into the string the planner receives. No tool throws.
 */
@Component
public class PriorityTools {

    private static final Logger log = LogManager.getLogger(PriorityTools.class);

    private final PriorityService priorityService;
    private final ObjectMapper mapper;

    public PriorityTools(PriorityService priorityService, ObjectMapper mapper) {
        this.priorityService = priorityService;
        this.mapper = mapper;
    }

    @Tool(name = AppConstant.TOOL_GET_QUEUE, description = AppConstant.TOOL_GET_QUEUE_DESCRIPTION)
    public String getQueue() {
        return respond(AppConstant.TOOL_GET_QUEUE, () -> priorityService.getQueue(tenant()));
    }

    @Tool(name = AppConstant.TOOL_GET_SLA, description = AppConstant.TOOL_GET_SLA_DESCRIPTION)
    public String getSla() {
        return respond(AppConstant.TOOL_GET_SLA, () -> priorityService.getSla(tenant()));
    }

    @Tool(name = AppConstant.TOOL_GET_ALERTS, description = AppConstant.TOOL_GET_ALERTS_DESCRIPTION)
    public String getAlerts() {
        return respond(AppConstant.TOOL_GET_ALERTS, () -> priorityService.getAlerts(tenant()));
    }

    /**
     * Runs one tool call and renders its value object as JSON. Any failure degrades to a short
     * explanatory string rather than propagating, so a priority-engine outage can't crash the
     * planner loop (design §14).
     */
    private String respond(String toolName, Callable<Object> call) {
        try {
            return mapper.writeValueAsString(call.call());
        } catch (EIPMCPException e) {
            log.debug("{} failed [{}]: {}", toolName, e.getError().getCode(), e.getMessage());
            return e.getMessage();
        } catch (JsonProcessingException e) {
            log.warn("{} result could not be serialized: {}", toolName, e.getMessage());
            return "priority result could not be serialized (" + e.getMessage() + ")";
        } catch (Exception e) {
            log.warn("{} failed unexpectedly: {}", toolName, e.getMessage(), e);
            return "priority tool failed unexpectedly (" + e.getMessage() + ")";
        }
    }

    /** The tenant this call may act on, bound to the authenticated principal (design §9). */
    private String tenant() {
        return McpTenant.requireTenantId();
    }
}
