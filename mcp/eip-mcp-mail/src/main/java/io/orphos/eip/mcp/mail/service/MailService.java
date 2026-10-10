package io.orphos.eip.mcp.mail.service;

import io.orphos.eip.mcp.mail.exception.EIPMCPException;
import io.orphos.eip.mcp.mail.model.DraftReplyVo;
import io.orphos.eip.mcp.mail.model.MailListVo;

/**
 * The mail capabilities this server exposes, in typed form. Sits between the MCP tool surface
 * ({@code MailTools}) and the transport ({@code MailInsightClient}): it owns endpoint selection,
 * request shaping, and the draft-then-send token contract, leaving the tools layer with only tenant
 * resolution and result formatting.
 *
 * <p>Note the deliberate asymmetry in the signatures: the read and draft methods declare the checked
 * {@link EIPMCPException} because they are required to degrade gracefully, while
 * {@link #sendEmail} declares nothing and fails unchecked — a refused or failed send must reach the
 * caller as an error, never as ordinary result text.
 */
public interface MailService {

    /** The tenant's mail-derived tasks, optionally filtered by status and sorted. */
    MailListVo listTasks(String tenantId, String status, String sort) throws EIPMCPException;

    /** The tenant's mail notifications, optionally filtered by level. */
    MailListVo listNotifications(String tenantId, String level) throws EIPMCPException;

    /**
     * Drafts a reply or meeting and mints the confirmation token binding a later send to exactly
     * this content. Sends nothing. When the upstream draft carries no {@code subject} or
     * {@code bodyText} there is nothing to bind, so the returned VO has a null
     * {@code confirmationToken} and the caller forwards the draft unchanged.
     */
    DraftReplyVo draftReply(String tenantId, String itemId, String action, String instruction)
            throws EIPMCPException;

    /** Re-mints a confirmation token for already-known content. Side-effect-free. */
    String prepareSend(String tenantId, String itemId, String action, String subject, String bodyText);

    /**
     * Sends a drafted reply or meeting.
     *
     * @throws SecurityException if {@code confirmationToken} was not issued for this exact
     *                           tenant/item/action/subject/body — the send is refused, not attempted.
     * @throws IllegalStateException if mail-insight rejects or cannot process the send.
     */
    String sendEmail(String tenantId, String itemId, String action, String to, String subject,
                     String bodyText, String confirmationToken);
}
