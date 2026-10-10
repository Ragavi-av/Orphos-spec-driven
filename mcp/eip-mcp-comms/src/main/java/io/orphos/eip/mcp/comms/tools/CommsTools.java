package io.orphos.eip.mcp.comms.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orphos.eip.mcp.comms.config.CommsMcpProperties;
import io.orphos.eip.mcp.comms.service.CommsInsightClient;
import io.orphos.eip.mcp.comms.service.DraftTokens;
import io.orphos.eip.mcp.spec.McpTenant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Communication-channel capabilities as MCP tools (TO-BE design §14). Listing and drafting are
 * non-sending; only {@code comms.send_message} is advertised as write + approval, so the
 * draft-then-send contract is preserved at the MCP boundary — an agent may draft freely but sending
 * is captured as a ProposedAction and executed only after a human approves. Reads are filtered to
 * communication-channel sources (Slack/Teams/…) so this server never surfaces email items.
 */
@Component
public class CommsTools {

    private static final Logger log = LogManager.getLogger(CommsTools.class);

    /** Feed filter: only channel-derived items, never EMAIL/DATA. */
    private static final String CHANNEL_SOURCES = "SLACK,MS_TEAMS,DISCORD,GOOGLE_CHAT";
    private static final String REPLY = "REPLY";

    private final CommsInsightClient client;
    private final DraftTokens tokens;
    private final ObjectMapper mapper = new ObjectMapper();

    public CommsTools(CommsInsightClient client, CommsMcpProperties props) {
        this.client = client;
        this.tokens = new DraftTokens(props.getInternalToken());
    }

    @Tool(name = "comms.list_tasks",
            description = "Lists the tenant's tasks derived from communication channels (Slack, Microsoft Teams, …). "
                    + "Optionally filter by status and sort.")
    public String listTasks(
            @ToolParam(required = false, description = "Filter by status (e.g. OPEN, DONE).") String status,
            @ToolParam(required = false, description = "Sort key (priority | criticality | dueDate).") String sort) {
        StringBuilder q = new StringBuilder("source=").append(enc(CHANNEL_SOURCES));
        appendParam(q, "status", status);
        appendParam(q, "sort", sort);
        return client.get("/api/mail-insight/tasks?" + q, tenant());
    }

    @Tool(name = "comms.list_notifications",
            description = "Lists the tenant's notifications derived from communication channels (Slack, Teams, …). "
                    + "Optionally filter by level (ALERT/WARNING/INFORMATION).")
    public String listNotifications(
            @ToolParam(required = false, description = "Notification level filter.") String level) {
        StringBuilder q = new StringBuilder("source=").append(enc(CHANNEL_SOURCES));
        appendParam(q, "level", level);
        return client.get("/api/mail-insight/notifications?" + q, tenant());
    }

    @Tool(name = "comms.draft_reply",
            description = "Drafts a reply for a channel item and returns the editable proposal PLUS a "
                    + "confirmationToken. Does NOT send. To send, call comms.send_message with the SAME bodyText "
                    + "returned here and this confirmationToken.")
    public String draftReply(
            @ToolParam(description = "The channel item id.") String itemId,
            @ToolParam(required = false, description = "Instruction guiding the draft.") String instruction) {
        ObjectNode body = mapper.createObjectNode();
        body.put("action", REPLY);
        if (instruction != null) body.put("instruction", instruction);
        String path = "/api/mail-insight/comms/items/" + enc(itemId) + "/agent/draft";
        String tenant = tenant();
        String draftJson = client.post(path, tenant, write(body));

        JsonNode d;
        try {
            d = mapper.readTree(draftJson);
        } catch (JsonProcessingException e) {
            // Upstream returned a non-draft body (e.g. the client's "unavailable" message) — pass it
            // through. Narrow to the parse alone: catching everything here used to hide a failure to
            // mint or serialize the token too, which silently returned an untokenized draft the agent
            // could not then send.
            log.debug("comms.draft_reply: upstream body for item {} is not JSON, forwarding as-is: {}",
                    itemId, e.getMessage());
            return draftJson;
        }
        if (d.hasNonNull("bodyText")) {
            String bodyText = d.path("bodyText").asText("");
            String token = tokens.issue(itemId, tenant, REPLY, "", bodyText);
            ObjectNode out = mapper.createObjectNode();
            out.put("confirmationToken", token);
            out.set("draft", d);
            out.put("nextStep", "Call comms.send_message with the bodyText exactly as drafted and confirmationToken.");
            return write(out);
        }
        return draftJson;
    }

    @Tool(name = "comms.prepare_send",
            description = "Issues a confirmationToken binding a send to this exact item/bodyText. Side-effect-free. "
                    + "Used by the approval edit-before-approve flow to re-mint a token after a human edits a drafted "
                    + "message; it is not part of any agent's toolset.")
    public String prepareSend(
            @ToolParam(description = "The channel item id.") String itemId,
            @ToolParam(required = false, description = "Body text.") String bodyText) {
        return tokens.issue(itemId, tenant(), REPLY, "", bodyText == null ? "" : bodyText);
    }

    @Tool(name = "comms.send_message",
            description = "Posts a reply back to the channel an item came from. WRITE + approval. REQUIRES a "
                    + "confirmationToken from a prior comms.draft_reply for the SAME item, and the bodyText must match "
                    + "what was drafted — a send without a matching draft token is refused.")
    public String sendMessage(
            @ToolParam(description = "The channel item id.") String itemId,
            @ToolParam(description = "Body text (must match the draft).") String bodyText,
            @ToolParam(description = "The confirmationToken returned by comms.draft_reply for this item.") String confirmationToken) {
        String tenant = tenant();
        if (!tokens.verify(confirmationToken, itemId, tenant, REPLY, "", bodyText == null ? "" : bodyText)) {
            throw new SecurityException("Refusing to send: no valid confirmationToken for item '" + itemId
                    + "'. Call comms.draft_reply first, then send with its confirmationToken and the exact drafted bodyText.");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("action", REPLY);
        if (bodyText != null) body.put("bodyText", bodyText);
        String path = "/api/mail-insight/comms/items/" + enc(itemId) + "/agent/send";
        return client.postStrict(path, tenant, write(body));
    }

    private String tenant() {
        return McpTenant.requireTenantId();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static void appendParam(StringBuilder sb, String key, String value) {
        if (key == null || value == null || value.isBlank()) return;
        sb.append('&').append(key).append('=').append(URLEncoder.encode(value.trim(), StandardCharsets.UTF_8));
    }

    /**
     * Serializes a JSON node. Failing loudly rather than falling back to {@code "{}"}: an empty body
     * on the send path would post a blank message to the channel, and an empty draft response would
     * strip the confirmationToken the agent needs to send at all — both reported as success.
     */
    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the comms request body: " + e.getMessage(), e);
        }
    }
}
