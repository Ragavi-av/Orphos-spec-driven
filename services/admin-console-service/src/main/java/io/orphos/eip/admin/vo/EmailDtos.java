package io.orphos.eip.admin.vo;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class EmailDtos {
    private EmailDtos() {}

    // ----- SMTP config -----
    public record EmailConfigRequest(
            @NotBlank @Size(max = 60) String name,
            @NotBlank String host,
            @Min(1) int port,
            @NotBlank String fromAddress,
            @NotBlank String fromName,
            @NotBlank String username,
            // The raw password is accepted at write-time but never returned;
            // it is referenced indirectly afterwards.
            String password,
            // "STARTTLS" | "SSL" | "NONE"
            @NotBlank String security,
            boolean enabled
    ) {}

    public record EmailConfigView(
            String id, String name, String host, int port, String fromAddress,
            String fromName, String username, String security, boolean enabled,
            Instant updatedAt
    ) {}

    // ----- Template -----
    public record EmailTemplateRequest(
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 200_000) String bodyHtml,
            @Size(max = 200_000) String bodyText,
            List<String> requiredVariables
    ) {}

    public record EmailTemplateView(String id, String code, String name, String subject,
                                    String bodyHtml, String bodyText,
                                    List<String> requiredVariables, Instant updatedAt) {}

    public record TemplateValidationReport(boolean ok, List<String> issues,
                                           List<String> declaredVariables) {}

    public record TestEmailRequest(@Email @NotBlank String to, String templateCode,
                                   Map<String, Object> variables) {}

    public record DeliveryResult(boolean delivered, String message, Instant at) {}

    // ----- Preferences -----
    public record NotificationPrefRequest(
            String userKey,             // null = caller
            boolean systemAlerts,
            boolean briefingDigest,
            boolean securityAlerts,
            boolean marketingUpdates
    ) {}

    public record NotificationPrefView(String userKey, boolean systemAlerts,
                                        boolean briefingDigest, boolean securityAlerts,
                                        boolean marketingUpdates, Instant updatedAt) {}

    // ----- Analytics -----
    public record EmailAnalyticsView(long sent24h, long delivered24h, long bounced24h,
                                     long opened24h, Map<String, Long> byTemplate24h) {}

    public record EmailEventView(String id, String templateCode, String toHash,
                                  String status, String reason, Instant at) {}

    // ----- Backup / restore -----
    public record EmailBackupRequest(@NotBlank @Size(max = 120) String label) {}

    public record EmailBackupView(String id, String label, Instant createdAt) {}

    public record EmailBackupContent(EmailConfigView config, List<EmailTemplateView> templates,
                                     Instant takenAt) {}
}
