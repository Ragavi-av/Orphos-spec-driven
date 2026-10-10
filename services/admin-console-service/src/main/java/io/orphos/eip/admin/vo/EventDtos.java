package io.orphos.eip.admin.vo;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * DTOs for the event-communication framework: internal event ingestion,
 * notification-rule CRUD (Admin → Communications → Event Alerts) and the
 * per-tenant event log.
 */
public final class EventDtos {

    private EventDtos() {}

    /** Audience levels a rule can target. */
    public static final String AUDIENCE_PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String AUDIENCE_TENANT_ADMIN   = "TENANT_ADMIN";
    public static final String AUDIENCE_ALL_USERS      = "ALL_USERS";
    public static final String AUDIENCE_ROLE           = "ROLE";

    public static final List<String> AUDIENCES = List.of(
            AUDIENCE_PLATFORM_ADMIN, AUDIENCE_TENANT_ADMIN, AUDIENCE_ALL_USERS, AUDIENCE_ROLE);

    /**
     * Event as posted by producer services to {@code /api/internal/events}.
     * {@code tenantId} absent = platform-scoped: matched against every
     * tenant's rules (e.g. SERVICE_DOWN).
     */
    public record EventIngestRequest(
            @NotBlank String eventType,
            String severity,
            String source,
            String subject,
            String message,
            String tenantId,
            Map<String, Object> attributes,
            String occurredAt) {

        public String severityOrDefault() {
            return severity == null || severity.isBlank() ? "WARNING" : severity;
        }
    }

    public record NotificationRuleRequest(
            @NotBlank String eventType,
            @NotBlank String audience,
            String roleName,
            String templateCode,
            List<String> extraRecipients,
            boolean enabled) {}

    public record NotificationRuleView(
            String id,
            String eventType,
            String audience,
            String roleName,
            String templateCode,
            List<String> extraRecipients,
            boolean enabled,
            String createdAt,
            String updatedAt) {}

    public record EventLogView(
            String id,
            String eventType,
            String severity,
            String source,
            String subject,
            String message,
            int recipientCount,
            String dispatchStatus,
            String createdAt) {}

    /** Catalog entry the UI uses to populate the event-type dropdown. */
    public record EventTypeInfo(String eventType, String label, String defaultTemplateCode) {}
}
