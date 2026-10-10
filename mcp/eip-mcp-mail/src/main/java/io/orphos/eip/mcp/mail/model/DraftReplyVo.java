package io.orphos.eip.mcp.mail.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What {@code mail.draft_reply} hands back: the drafted proposal plus the confirmation token that
 * binds a later {@code mail.send_email} to exactly this content (design §14 send-agent contract).
 *
 * <p>{@code draft} is deliberately a raw {@link JsonNode} rather than a typed record. It is
 * mail-insight's own draft payload forwarded verbatim, and the agent must echo its {@code subject}
 * and {@code bodyText} back byte-for-byte or the token check fails — so nothing in this server may
 * silently reshape or drop a field on the way through.
 */
public record DraftReplyVo(String confirmationToken, JsonNode draft, String nextStep) {
}
