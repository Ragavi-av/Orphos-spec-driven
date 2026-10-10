package io.orphos.eip.mcp.mail.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mcp.mail.constants.AppConstant;
import io.orphos.eip.mcp.mail.exception.EIPMCPException;
import io.orphos.eip.mcp.mail.model.DraftReplyVo;
import io.orphos.eip.mcp.mail.service.MailService;
import io.orphos.eip.mcp.spec.McpTenant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * Mail capabilities as MCP tools (TO-BE design §14). Listing and drafting are non-sending; only
 * {@code mail.send_email} is advertised as write + approval, so the draft-then-send contract is
 * preserved at the MCP boundary — an agent may draft freely but sending is captured as a
 * ProposedAction and executed only after a human approves. Tenant resolved via {@link McpTenant}.
 *
 * <p>This layer is deliberately thin: resolve the tenant, delegate to {@link MailService}, format
 * the result. Note that {@link #sendEmail} alone does <em>not</em> swallow failures — a refused or
 * failed send must reach the caller as an error so the approval flow can mark it FAILED, rather
 * than returning text that reads like a successful send.
 */
@Component
public class MailTools {

    private static final Logger log = LogManager.getLogger(MailTools.class);

    private final MailService mailService;
    private final ObjectMapper mapper;

    public MailTools(MailService mailService, ObjectMapper mapper) {
        this.mailService = mailService;
        this.mapper = mapper;
    }

    @Tool(name = AppConstant.TOOL_LIST_TASKS, description = AppConstant.TOOL_LIST_TASKS_DESCRIPTION)
    public String listTasks(
            @ToolParam(required = false, description = "Filter by status (e.g. OPEN, DONE).") String status,
            @ToolParam(required = false, description = "Sort key.") String sort) {
        return respond(AppConstant.TOOL_LIST_TASKS, () -> mailService.listTasks(tenant(), status, sort));
    }

    @Tool(name = AppConstant.TOOL_LIST_NOTIFICATIONS,
            description = AppConstant.TOOL_LIST_NOTIFICATIONS_DESCRIPTION)
    public String listNotifications(
            @ToolParam(required = false, description = "Notification level filter.") String level) {
        return respond(AppConstant.TOOL_LIST_NOTIFICATIONS, () -> mailService.listNotifications(tenant(), level));
    }

    @Tool(name = AppConstant.TOOL_DRAFT_REPLY, description = AppConstant.TOOL_DRAFT_REPLY_DESCRIPTION)
    public String draftReply(
            @ToolParam(description = "The mail item id.") String itemId,
            @ToolParam(required = false, description = "REPLY (default) or MEETING.") String action,
            @ToolParam(required = false, description = "Instruction guiding the draft.") String instruction) {
        return respond(AppConstant.TOOL_DRAFT_REPLY, () -> {
            DraftReplyVo drafted = mailService.draftReply(tenant(), itemId, action, instruction);
            // No token means there was no draft content to bind one to — forward the upstream
            // payload as-is rather than wrapping it in a contract the agent can't act on.
            return drafted.confirmationToken() == null ? drafted.draft() : drafted;
        });
    }

    @Tool(name = AppConstant.TOOL_PREPARE_SEND, description = AppConstant.TOOL_PREPARE_SEND_DESCRIPTION)
    public String prepareSend(
            @ToolParam(description = "The mail item id.") String itemId,
            @ToolParam(required = false, description = "REPLY (default) or MEETING.") String action,
            @ToolParam(required = false, description = "Subject line.") String subject,
            @ToolParam(required = false, description = "Body text.") String bodyText) {
        return mailService.prepareSend(tenant(), itemId, action, subject, bodyText);
    }

    /**
     * The one tool that may throw. A bad confirmation token raises {@link SecurityException} and a
     * rejected upstream send raises {@link IllegalStateException}; both propagate untouched.
     */
    @Tool(name = AppConstant.TOOL_SEND_EMAIL, description = AppConstant.TOOL_SEND_EMAIL_DESCRIPTION)
    public String sendEmail(
            @ToolParam(description = "The mail item id.") String itemId,
            @ToolParam(required = false, description = "REPLY (default) or MEETING.") String action,
            @ToolParam(description = "Comma-separated recipient addresses.") String to,
            @ToolParam(required = false, description = "Subject line (must match the draft).") String subject,
            @ToolParam(required = false, description = "Body text (must match the draft).") String bodyText,
            @ToolParam(description = "The confirmationToken returned by mail.draft_reply for this item.")
            String confirmationToken) {
        return mailService.sendEmail(tenant(), itemId, action, to, subject, bodyText, confirmationToken);
    }

    /**
     * Runs one non-sending tool call and renders its result as JSON. Failures degrade to a short
     * explanatory string rather than propagating, so a mail-insight outage can't crash the planner
     * loop (design §14).
     */
    private String respond(String toolName, Callable<Object> call) {
        try {
            return mapper.writeValueAsString(call.call());
        } catch (EIPMCPException e) {
            log.debug("{} failed [{}]: {}", toolName, e.getError().getCode(), e.getMessage());
            return e.getMessage();
        } catch (JsonProcessingException e) {
            log.warn("{} result could not be serialized: {}", toolName, e.getMessage());
            return "mail result could not be serialized (" + e.getMessage() + ")";
        } catch (Exception e) {
            log.warn("{} failed unexpectedly: {}", toolName, e.getMessage(), e);
            return "mail tool failed unexpectedly (" + e.getMessage() + ")";
        }
    }

    /** The tenant this call may act on, bound to the authenticated principal (design §9). */
    private String tenant() {
        return McpTenant.requireTenantId();
    }
}
