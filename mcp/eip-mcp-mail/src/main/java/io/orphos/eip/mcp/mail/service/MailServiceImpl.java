package io.orphos.eip.mcp.mail.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orphos.eip.mcp.mail.constants.AppConstant;
import io.orphos.eip.mcp.mail.exception.EIPMCPException;
import io.orphos.eip.mcp.mail.exception.Error;
import io.orphos.eip.mcp.mail.model.DraftReplyVo;
import io.orphos.eip.mcp.mail.model.MailListVo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Maps each mail capability onto its upstream endpoint and enforces the draft-then-send contract
 * (TO-BE design §14; Jul-10 §5.1/§6.1: "send must require a prior draft and a confirmation token;
 * never a free-standing autonomous action").
 */
@Service
public class MailServiceImpl implements MailService {

    private static final Logger log = LogManager.getLogger(MailServiceImpl.class);

    private final MailInsightClient client;
    private final DraftTokens tokens;
    private final ObjectMapper mapper;

    public MailServiceImpl(MailInsightClient client, DraftTokens tokens, ObjectMapper mapper) {
        this.client = client;
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @Override
    public MailListVo listTasks(String tenantId, String status, String sort) throws EIPMCPException {
        String path = withQuery(AppConstant.MAIL_TASKS_PATH,
                AppConstant.PARAM_STATUS, status, AppConstant.PARAM_SORT, sort);
        return read(path, tenantId);
    }

    @Override
    public MailListVo listNotifications(String tenantId, String level) throws EIPMCPException {
        String path = withQuery(AppConstant.MAIL_NOTIFICATIONS_PATH,
                AppConstant.PARAM_LEVEL, level, null, null);
        return read(path, tenantId);
    }

    @Override
    public DraftReplyVo draftReply(String tenantId, String itemId, String action, String instruction)
            throws EIPMCPException {
        String act = normalizeAction(action);

        ObjectNode request = mapper.createObjectNode();
        request.put("action", act);
        if (instruction != null) {
            request.put("instruction", instruction);
        }

        String path = AppConstant.MAIL_ITEMS_PATH + enc(itemId) + AppConstant.MAIL_AGENT_DRAFT_SUFFIX;
        String draftJson = client.post(path, tenantId, writeOrEmpty(request));

        JsonNode draft;
        try {
            draft = mapper.readTree(draftJson);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable mail-insight draft response for item {}: {}", itemId, e.getMessage());
            throw new EIPMCPException(
                    new Error("mail-insight returned an unreadable draft for item " + itemId,
                            AppConstant.ERROR_CODE_RESPONSE_UNREADABLE),
                    e);
        }

        // Nothing to bind a token to — forward the upstream body untouched.
        if (!draft.hasNonNull("subject") && !draft.hasNonNull("bodyText")) {
            return new DraftReplyVo(null, draft, null);
        }

        String subject = draft.path("subject").asText("");
        String bodyText = draft.path("bodyText").asText("");
        String token = tokens.issue(itemId, tenantId, act, subject, bodyText);
        String nextStep = "Call " + AppConstant.TOOL_SEND_EMAIL + " with action='" + act
                + "', the subject and bodyText exactly as drafted, and confirmationToken.";
        return new DraftReplyVo(token, draft, nextStep);
    }

    @Override
    public String prepareSend(String tenantId, String itemId, String action, String subject, String bodyText) {
        return tokens.issue(itemId, tenantId, normalizeAction(action),
                subject == null ? "" : subject, bodyText == null ? "" : bodyText);
    }

    @Override
    public String sendEmail(String tenantId, String itemId, String action, String to, String subject,
                            String bodyText, String confirmationToken) {
        String act = normalizeAction(action);
        if (!tokens.verify(confirmationToken, itemId, tenantId, act,
                subject == null ? "" : subject, bodyText == null ? "" : bodyText)) {
            throw new SecurityException("Refusing to send: no valid confirmationToken for item '" + itemId
                    + "'. Call " + AppConstant.TOOL_DRAFT_REPLY + " first, then send with its confirmationToken "
                    + "and the exact drafted action/subject/bodyText.");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("action", act);
        ArrayNode recipients = body.putArray("to");
        if (to != null) {
            for (String addr : to.split(",")) {
                String a = addr.trim();
                if (!a.isEmpty()) {
                    recipients.add(a);
                }
            }
        }
        if (subject != null) body.put("subject", subject);
        if (bodyText != null) body.put("bodyText", bodyText);

        String path = AppConstant.MAIL_ITEMS_PATH + enc(itemId) + AppConstant.MAIL_AGENT_SEND_SUFFIX;
        return client.postStrict(path, tenantId, writeOrEmpty(body));
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private MailListVo read(String path, String tenantId) throws EIPMCPException {
        String body = client.get(path, tenantId);
        try {
            return mapper.readValue(body, MailListVo.class);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable mail-insight response for {}: {}", path, e.getMessage());
            throw new EIPMCPException(
                    new Error("mail-insight returned an unreadable response for " + path,
                            AppConstant.ERROR_CODE_RESPONSE_UNREADABLE),
                    e);
        }
    }

    /** REPLY unless MEETING was explicitly asked for — mirrors mail-insight's own normalization. */
    private static String normalizeAction(String action) {
        return (action == null || action.isBlank())
                ? AppConstant.ACTION_REPLY
                : action.trim().toUpperCase();
    }

    private static String withQuery(String path, String k1, String v1, String k2, String v2) {
        StringBuilder q = new StringBuilder();
        appendParam(q, k1, v1);
        appendParam(q, k2, v2);
        return q.isEmpty() ? path : path + "?" + q;
    }

    private static void appendParam(StringBuilder sb, String key, String value) {
        if (key == null || value == null || value.isBlank()) return;
        if (!sb.isEmpty()) sb.append('&');
        sb.append(key).append('=').append(URLEncoder.encode(value.trim(), StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String writeOrEmpty(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
