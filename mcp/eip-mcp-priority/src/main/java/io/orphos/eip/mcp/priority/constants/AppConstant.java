package io.orphos.eip.mcp.priority.constants;

/**
 * Central home for the literal values used across the priority MCP server, so tool names shared
 * between {@code PriorityTools} and {@code McpServerRegistrar} (design §6/§14: registration must
 * mirror the {@code @Tool} names) stay in sync by construction rather than by comment.
 */
public final class AppConstant {

    private AppConstant() {}

    // MCP tool names (must mirror between PriorityTools and McpServerRegistrar)
    public static final String TOOL_GET_QUEUE = "priority.get_queue";
    public static final String TOOL_GET_SLA = "priority.get_sla";
    public static final String TOOL_GET_ALERTS = "priority.get_alerts";

    // Tool descriptions exposed via @Tool annotations
    public static final String TOOL_GET_QUEUE_DESCRIPTION =
            "Returns the current prioritized work queue (EPQ-scored incidents/issues) for the tenant.";
    public static final String TOOL_GET_SLA_DESCRIPTION =
            "Returns SLA metrics and breach status for the tenant's tracked work.";
    public static final String TOOL_GET_ALERTS_DESCRIPTION =
            "Returns active priority alerts (e.g. overdue or at-risk items) for the tenant.";

    // Short descriptions advertised to platform-registry at self-registration
    public static final String TOOL_GET_QUEUE_SHORT_DESCRIPTION = "Current prioritized work queue";
    public static final String TOOL_GET_SLA_SHORT_DESCRIPTION = "SLA metrics and breach status";
    public static final String TOOL_GET_ALERTS_SHORT_DESCRIPTION = "Active priority alerts";

    // Upstream priority-engine API paths
    public static final String PRIORITY_QUEUE_PATH = "/api/priority/queue";
    public static final String PRIORITY_SLA_PATH = "/api/priority/sla";
    public static final String PRIORITY_ALERTS_PATH = "/api/priority/alerts";

    // platform-registry self-registration
    public static final String MCP_SERVER_REGISTER_PATH = "/api/platform/mcp-servers/register";

    // eip.mcp.priority configuration
    public static final String CONFIG_PREFIX = "eip.mcp.priority";
    public static final String DEFAULT_SERVER_ID = "eip-mcp-priority";
    public static final String DEFAULT_MCP_ENDPOINT = "http://eip-mcp-priority:8080/mcp";
    public static final String DEFAULT_HEALTH_URL = "http://eip-mcp-priority:8080/actuator/health";
    public static final String DEFAULT_REGISTRY_BASE_URL = "http://eip-platform-registry:8080";
    public static final String DEFAULT_PRIORITY_ENGINE_BASE_URL = "http://eip-priority-engine:8080";

    // Error codes carried by EIPMCPException (see exception package)
    public static final String ERROR_CODE_UPSTREAM_STATUS = "PRIORITY_ENGINE_HTTP_ERROR";
    public static final String ERROR_CODE_UPSTREAM_UNAVAILABLE = "PRIORITY_ENGINE_UNAVAILABLE";
    public static final String ERROR_CODE_RESPONSE_UNREADABLE = "PRIORITY_ENGINE_RESPONSE_UNREADABLE";
}
