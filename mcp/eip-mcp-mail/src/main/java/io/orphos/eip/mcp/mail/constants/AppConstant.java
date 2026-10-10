package io.orphos.eip.mcp.mail.constants;

/**
 * Central home for the literal values used across the mail MCP server, so tool names shared between
 * {@code MailTools} and {@code McpServerRegistrar} (design §6/§14: registration must mirror the
 * {@code @Tool} names) stay in sync by construction rather than by comment.
 */
public final class AppConstant {

    private AppConstant() {}

    // MCP tool names (must mirror between MailTools and McpServerRegistrar)
    public static final String TOOL_LIST_TASKS = "mail.list_tasks";
    public static final String TOOL_LIST_NOTIFICATIONS = "mail.list_notifications";
    public static final String TOOL_DRAFT_REPLY = "mail.draft_reply";
    public static final String TOOL_PREPARE_SEND = "mail.prepare_send";
    public static final String TOOL_SEND_EMAIL = "mail.send_email";

    // Tool descriptions exposed via @Tool annotations
    public static final String TOOL_LIST_TASKS_DESCRIPTION =
            "Lists the tenant's mail-derived tasks. Optionally filter by status and sort.";
    public static final String TOOL_LIST_NOTIFICATIONS_DESCRIPTION =
            "Lists the tenant's mail notifications. Optionally filter by level (ALERT/WARNING/INFORMATION).";
    public static final String TOOL_DRAFT_REPLY_DESCRIPTION =
            "Drafts a reply or meeting for a mail item and returns the editable proposal PLUS a "
                    + "confirmationToken. Does NOT send. To send, call mail.send_email with the SAME action, "
                    + "subject and bodyText returned here and this confirmationToken.";
    public static final String TOOL_PREPARE_SEND_DESCRIPTION =
            "Issues a confirmationToken binding a send to this exact item/action/subject/bodyText. "
                    + "Side-effect-free. Used by the approval edit-before-approve flow to re-mint a token after a "
                    + "human edits a drafted message; it is not part of any agent's toolset.";
    public static final String TOOL_SEND_EMAIL_DESCRIPTION =
            "Sends a reply or meeting for a mail item. WRITE + approval. REQUIRES a confirmationToken "
                    + "from a prior mail.draft_reply for the SAME item, and the action/subject/bodyText must match "
                    + "what was drafted — a send without a matching draft token is refused.";

    // Short descriptions advertised to platform-registry at self-registration
    public static final String TOOL_LIST_TASKS_SHORT_DESCRIPTION = "List mail-derived tasks";
    public static final String TOOL_LIST_NOTIFICATIONS_SHORT_DESCRIPTION = "List mail notifications";
    public static final String TOOL_DRAFT_REPLY_SHORT_DESCRIPTION = "Draft a reply/meeting (no send)";
    public static final String TOOL_PREPARE_SEND_SHORT_DESCRIPTION =
            "Re-mint a send confirmation token (approval edit flow)";
    public static final String TOOL_SEND_EMAIL_SHORT_DESCRIPTION = "Send a drafted reply/meeting";

    /** The {@code mail.send_email} argument the re-minted confirmation token is written into. */
    public static final String SEND_EMAIL_TOKEN_ARGUMENT = "confirmationToken";

    // Upstream mail-insight API paths
    public static final String MAIL_TASKS_PATH = "/api/mail-insight/tasks";
    public static final String MAIL_NOTIFICATIONS_PATH = "/api/mail-insight/notifications";
    public static final String MAIL_ITEMS_PATH = "/api/mail-insight/items/";
    public static final String MAIL_AGENT_DRAFT_SUFFIX = "/agent/draft";
    public static final String MAIL_AGENT_SEND_SUFFIX = "/agent/send";

    // platform-registry self-registration
    public static final String MCP_SERVER_REGISTER_PATH = "/api/platform/mcp-servers/register";

    // Draft/send contract
    public static final String ACTION_REPLY = "REPLY";
    public static final String ACTION_MEETING = "MEETING";

    // Query parameter names accepted by the read tools
    public static final String PARAM_STATUS = "status";
    public static final String PARAM_SORT = "sort";
    public static final String PARAM_LEVEL = "level";

    // eip.mcp.mail configuration
    public static final String CONFIG_PREFIX = "eip.mcp.mail";
    public static final String DEFAULT_SERVER_ID = "eip-mcp-mail";
    public static final String DEFAULT_MCP_ENDPOINT = "http://eip-mcp-mail:8080/mcp";
    public static final String DEFAULT_HEALTH_URL = "http://eip-mcp-mail:8080/actuator/health";
    public static final String DEFAULT_REGISTRY_BASE_URL = "http://eip-platform-registry:8093";
    public static final String DEFAULT_MAIL_INSIGHT_BASE_URL = "http://eip-mail-insight:8092";

    // Error codes carried by EIPMCPException (see exception package)
    public static final String ERROR_CODE_UPSTREAM_STATUS = "MAIL_INSIGHT_HTTP_ERROR";
    public static final String ERROR_CODE_UPSTREAM_UNAVAILABLE = "MAIL_INSIGHT_UNAVAILABLE";
    public static final String ERROR_CODE_RESPONSE_UNREADABLE = "MAIL_INSIGHT_RESPONSE_UNREADABLE";
}
