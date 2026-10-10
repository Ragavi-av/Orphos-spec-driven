package io.orphos.eip.admin.util;

import io.orphos.eip.admin.repository.EmailRepository;
import io.orphos.eip.admin.vo.EventDtos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Well-known event types plus the default Mustache template seeded (per
 * tenant, on first use) for each. Tenants can override wording afterwards via
 * the existing Email → Templates editor; the seeded rows are ordinary
 * {@code EmailTemplate} documents.
 *
 * <p>Unknown event types are accepted too (producers may add new ones without
 * a deploy here) — they fall back to the {@code event-generic} template.</p>
 */
public final class EventCatalog {

    public static final String USER_ADMIN_CREATED = "USER_ADMIN_CREATED";
    public static final String DOC_INGEST_FAILED  = "DOC_INGEST_FAILED";
    public static final String SERVICE_DOWN       = "SERVICE_DOWN";
    public static final String SERVICE_RECOVERED  = "SERVICE_RECOVERED";
    public static final String LOG_ALERT          = "LOG_ALERT";

    public static final String GENERIC_TEMPLATE = "event-generic";

    private record Seed(String label, String templateCode, String subject, String bodyHtml) {}

    private static final Map<String, Seed> CATALOG = buildCatalog();

    private EventCatalog() {}

    private static Map<String, Seed> buildCatalog() {
        Map<String, Seed> m = new LinkedHashMap<>();
        m.put(USER_ADMIN_CREATED, new Seed(
                "New user with admin rights",
                "event-user-admin-created",
                "[{{severity}}] Admin access granted: {{attributes.username}}",
                """
                <h3>Administrator access granted</h3>
                <p>{{message}}</p>
                <table>
                  <tr><td><b>User</b></td><td>{{attributes.username}}</td></tr>
                  <tr><td><b>Granted role(s)</b></td><td>{{attributes.roles}}</td></tr>
                  <tr><td><b>Granted by</b></td><td>{{attributes.grantedBy}}</td></tr>
                  <tr><td><b>When</b></td><td>{{occurredAt}}</td></tr>
                </table>
                <p>If this change was not expected, review it in Admin &rarr; User Management.</p>
                """));
        m.put(DOC_INGEST_FAILED, new Seed(
                "Document ingestion failed",
                "event-doc-ingest-failed",
                "[{{severity}}] Document ingestion failed: {{attributes.fileName}}",
                """
                <h3>Document ingestion failure</h3>
                <p>{{message}}</p>
                <table>
                  <tr><td><b>File</b></td><td>{{attributes.fileName}}</td></tr>
                  <tr><td><b>Document ID</b></td><td>{{attributes.documentId}}</td></tr>
                  <tr><td><b>Uploaded by</b></td><td>{{attributes.uploadedBy}}</td></tr>
                  <tr><td><b>Error</b></td><td>{{attributes.error}}</td></tr>
                  <tr><td><b>When</b></td><td>{{occurredAt}}</td></tr>
                </table>
                """));
        m.put(SERVICE_DOWN, new Seed(
                "Service down",
                "event-service-down",
                "[{{severity}}] Service DOWN: {{attributes.serviceName}}",
                """
                <h3>Service unavailable</h3>
                <p>{{message}}</p>
                <table>
                  <tr><td><b>Service</b></td><td>{{attributes.serviceName}}</td></tr>
                  <tr><td><b>Health endpoint</b></td><td>{{attributes.healthUrl}}</td></tr>
                  <tr><td><b>Detail</b></td><td>{{attributes.detail}}</td></tr>
                  <tr><td><b>Detected at</b></td><td>{{occurredAt}}</td></tr>
                </table>
                <p>Platform admins can inspect and restart services in Admin &rarr; Services.</p>
                """));
        m.put(SERVICE_RECOVERED, new Seed(
                "Service recovered",
                "event-service-recovered",
                "[INFO] Service recovered: {{attributes.serviceName}}",
                """
                <h3>Service recovered</h3>
                <p>{{message}}</p>
                <table>
                  <tr><td><b>Service</b></td><td>{{attributes.serviceName}}</td></tr>
                  <tr><td><b>Recovered at</b></td><td>{{occurredAt}}</td></tr>
                </table>
                """));
        m.put(LOG_ALERT, new Seed(
                "Log alert (error needing attention)",
                "event-log-alert",
                "[{{severity}}] {{subject}}",
                """
                <h3>{{subject}}</h3>
                <p>{{message}}</p>
                <table>
                  <tr><td><b>Source service</b></td><td>{{source}}</td></tr>
                  <tr><td><b>Severity</b></td><td>{{severity}}</td></tr>
                  <tr><td><b>When</b></td><td>{{occurredAt}}</td></tr>
                </table>
                """));
        return m;
    }

    public static List<EventDtos.EventTypeInfo> catalog() {
        return CATALOG.entrySet().stream()
                .map(e -> new EventDtos.EventTypeInfo(e.getKey(), e.getValue().label(), e.getValue().templateCode()))
                .toList();
    }

    /** Default template code for an event type ({@code event-generic} for unknown types). */
    public static String defaultTemplateCode(String eventType) {
        Seed s = CATALOG.get(eventType);
        return s == null ? GENERIC_TEMPLATE : s.templateCode();
    }

    /**
     * Returns the seed template row for {@code templateCode} in the shape
     * {@code EmailRepository.upsertTemplate} expects, or {@code null} when the
     * code is not one of ours (a tenant-authored template — never overwrite).
     */
    public static Map<String, Object> seedTemplate(String templateCode) {
        Seed s = CATALOG.values().stream()
                .filter(v -> v.templateCode().equals(templateCode))
                .findFirst().orElse(null);
        String subject;
        String bodyHtml;
        String name;
        if (s != null) {
            subject = s.subject();
            bodyHtml = s.bodyHtml();
            name = "Event alert: " + s.label();
        } else if (GENERIC_TEMPLATE.equals(templateCode)) {
            subject = "[{{severity}}] {{subject}}";
            bodyHtml = """
                    <h3>{{subject}}</h3>
                    <p>{{message}}</p>
                    <p><b>Event:</b> {{eventType}} &middot; <b>Source:</b> {{source}} &middot; <b>When:</b> {{occurredAt}}</p>
                    """;
            name = "Event alert: generic";
        } else {
            return null;
        }
        Map<String, Object> t = new java.util.HashMap<>();
        t.put("code", templateCode);
        t.put("name", name);
        t.put("subject", subject);
        t.put("bodyHtml", bodyHtml);
        t.put("bodyText", "");
        t.put("requiredVariables", List.of());
        return t;
    }
}
