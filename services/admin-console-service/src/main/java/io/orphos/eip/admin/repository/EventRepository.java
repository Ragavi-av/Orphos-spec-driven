package io.orphos.eip.admin.repository;

import io.orphos.eip.admin.util.Ids;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence for the event-communication framework.
 *
 * <p>Two document types: {@code NotificationRule} (which audience gets an
 * email for which event type) and {@code PlatformEvent} (the append-only log
 * of received events with their dispatch outcome).</p>
 *
 * <p>Every method stamps {@code tenantId} on inserts and filters by it on
 * reads/updates/deletes — same contract as {@code EmailRepository}. The one
 * deliberate exception is {@link #listEnabledRulesAllTenants(String)}, used
 * only by the dispatcher for platform-scoped events (SERVICE_DOWN) that must
 * fan out to every tenant that subscribed; it is never exposed via a
 * controller.</p>
 */
@Repository
public class EventRepository {

    private final ArcadeDbTemplate arcade;

    public EventRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    // ----- NotificationRule -----

    public List<Map<String, Object>> listRules() {
        return arcade.query(
                "SELECT FROM NotificationRule WHERE tenantId = :tenantId ORDER BY eventType, audience",
                Map.of("tenantId", currentTenantId()));
    }

    public List<Map<String, Object>> listEnabledRulesForEvent(String eventType) {
        return arcade.query(
                "SELECT FROM NotificationRule WHERE tenantId = :tenantId AND eventType = :et AND enabled = true",
                Map.of("tenantId", currentTenantId(), "et", eventType));
    }

    /**
     * Cross-tenant read for platform-scoped events only (no tenant on the
     * inbound event). Callers bind each rule's own tenant context before
     * resolving recipients or sending.
     */
    public List<Map<String, Object>> listEnabledRulesAllTenants(String eventType) {
        return arcade.query(
                "SELECT FROM NotificationRule WHERE eventType = :et AND enabled = true",
                Map.of("et", eventType));
    }

    public Optional<Map<String, Object>> findRule(String id) {
        var rows = arcade.query(
                "SELECT FROM NotificationRule WHERE id = :id AND tenantId = :tenantId LIMIT 1",
                Map.of("id", id, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public String upsertRule(Map<String, Object> r) {
        String id = Objects.toString(r.get("id"), null);
        boolean exists = id != null && findRule(id).isPresent();
        if (id == null) id = Ids.newId();
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("tenantId", currentTenantId());
        m.put("et", r.get("eventType"));
        m.put("aud", r.get("audience"));
        m.put("role", Objects.toString(r.getOrDefault("roleName", ""), ""));
        m.put("tc", Objects.toString(r.getOrDefault("templateCode", ""), ""));
        m.put("xr", r.getOrDefault("extraRecipients", List.of()));
        m.put("en", r.getOrDefault("enabled", true));
        m.put("now", Instant.now().toString());
        if (!exists) {
            arcade.commandNoResult(
                    "INSERT INTO NotificationRule SET id = :id, tenantId = :tenantId, eventType = :et, "
                            + "audience = :aud, roleName = :role, templateCode = :tc, "
                            + "extraRecipients = :xr, enabled = :en, createdAt = :now, updatedAt = :now",
                    m);
        } else {
            arcade.commandNoResult(
                    "UPDATE NotificationRule SET eventType = :et, audience = :aud, roleName = :role, "
                            + "templateCode = :tc, extraRecipients = :xr, enabled = :en, updatedAt = :now "
                            + "WHERE id = :id AND tenantId = :tenantId",
                    m);
        }
        return id;
    }

    public void deleteRule(String id) {
        arcade.commandNoResult(
                "DELETE FROM NotificationRule WHERE id = :id AND tenantId = :tenantId",
                Map.of("id", id, "tenantId", currentTenantId()));
    }

    // ----- PlatformEvent (event log) -----

    public String recordEvent(String eventType, String severity, String source,
                              String subject, String message, Map<String, Object> attributes) {
        String id = Ids.newId();
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("tenantId", currentTenantId());
        m.put("et", eventType);
        m.put("sev", severity);
        m.put("src", source == null ? "" : source);
        m.put("sub", subject == null ? "" : subject);
        m.put("msg", message == null ? "" : message);
        m.put("attrs", attributes == null ? Map.of() : attributes);
        m.put("now", Instant.now().toString());
        arcade.commandNoResult(
                "INSERT INTO PlatformEvent SET id = :id, tenantId = :tenantId, eventType = :et, "
                        + "severity = :sev, source = :src, subject = :sub, message = :msg, "
                        + "attributes = :attrs, recipientCount = 0, dispatchStatus = 'RECEIVED', "
                        + "createdAt = :now",
                m);
        return id;
    }

    public void markDispatched(String eventId, int recipientCount, String dispatchStatus) {
        arcade.commandNoResult(
                "UPDATE PlatformEvent SET recipientCount = :rc, dispatchStatus = :ds "
                        + "WHERE id = :id AND tenantId = :tenantId",
                Map.of("id", eventId, "tenantId", currentTenantId(),
                        "rc", recipientCount, "ds", dispatchStatus));
    }

    public List<Map<String, Object>> recentEvents(int limit) {
        return arcade.query(
                "SELECT FROM PlatformEvent WHERE tenantId = :tenantId "
                        + "ORDER BY createdAt DESC LIMIT " + Math.max(1, Math.min(limit, 500)),
                Map.of("tenantId", currentTenantId()));
    }

    // ----- Helpers -----

    private static String currentTenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }
}
