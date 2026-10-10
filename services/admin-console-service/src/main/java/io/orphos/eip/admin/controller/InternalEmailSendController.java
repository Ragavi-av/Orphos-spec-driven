package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.vo.EmailDtos;
import io.orphos.eip.admin.service.EmailSendService;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service outbound email through a tenant's active SMTP
 * configuration (the Communications tab settings).
 *
 * <p>Built for the email-task agent in mail-insight-service: an executive
 * asks for a reply or a meeting invite from a dashboard notification, the
 * agent drafts it, and delivery happens here so every outbound mail uses the
 * tenant-administered SMTP config and lands in the same delivery analytics.</p>
 *
 * <p>Not JWT-gated — guarded by the shared {@code X-Internal-Token} (same
 * contract as {@code /api/internal/events}; fail-closed when the token is
 * unset). The caller supplies the tenant explicitly since there is no user
 * JWT on this hop.</p>
 */
@RestController
@RequestMapping("/api/internal/email")
public class InternalEmailSendController {

    private static final Logger log = LogManager.getLogger(InternalEmailSendController.class);

    /** One agent send may address a handful of attendees, never a blast. */
    private static final int MAX_RECIPIENTS = 20;

    private final EmailSendService sender;
    private final String internalToken;

    public InternalEmailSendController(
            EmailSendService sender,
            @Value("${admin.events.internal-token:${eip.security.tenant-discovery.service-token:}}") String internalToken) {
        this.sender = sender;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public record InternalSendRequest(
            @NotBlank String tenantId,
            @NotEmpty List<String> to,
            List<String> cc,
            @NotBlank String subject,
            String bodyText,
            String bodyHtml,
            String replyTo,
            String inReplyTo,
            String icsContent,
            String icsMethod,
            String sourceTag) {}

    @PostMapping("/send")
    public ResponseEntity<?> send(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @jakarta.validation.Valid @RequestBody InternalSendRequest req) {
        if (internalToken.isEmpty() || token == null || !internalToken.equals(token.trim())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid internal token"));
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(req.tenantId().trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed tenantId"));
        }
        int recipientCount = req.to().size() + (req.cc() == null ? 0 : req.cc().size());
        if (recipientCount > MAX_RECIPIENTS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "too many recipients (max " + MAX_RECIPIENTS + ")"));
        }

        String sourceTag = req.sourceTag() == null || req.sourceTag().isBlank()
                ? "agent-send" : req.sourceTag();
        try {
            EmailDtos.DeliveryResult result = TenantContextHolder.callWith(
                    TenantContext.of(tenantId, null),
                    () -> sender.sendMessage(sourceTag, new EmailSendService.OutboundMessage(
                            req.to(), req.cc(), req.subject(), req.bodyText(), req.bodyHtml(),
                            req.replyTo(), req.inReplyTo(), req.icsContent(), req.icsMethod())));
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            // "no active SMTP configuration" and friends — actionable by an admin.
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Internal send failed [tenant={}, source={}]: {}", tenantId, sourceTag, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "send failed: " + e.getMessage()));
        }
    }
}
