package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.service.AuditService;
import io.orphos.eip.admin.util.CurrentUser;
import io.orphos.eip.admin.vo.EventDtos;
import io.orphos.eip.admin.repository.EventRepository;
import io.orphos.eip.admin.util.EventCatalog;
import io.orphos.eip.admin.service.EventNotificationService;
import io.orphos.eip.common.tenant.TenantContextHolder;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Admin surface for the Communications → Event Alerts tab: notification-rule
 * CRUD, the event-type catalog for dropdowns, the recent event log, and a
 * test-fire endpoint so admins can verify SMTP + rule wiring end to end.
 *
 * <p>Tenant-scoped like the sibling email endpoints — a platform admin
 * manages a specific tenant's rules via X-Tenant-Id impersonation.</p>
 */
@RestController
@RequestMapping("/api/admin/events")
@PreAuthorize("hasAnyRole('admin', 'platform_admin')")
public class AdminEventsController {

    private final EventRepository repo;
    private final EventNotificationService service;
    private final AuditService audit;

    public AdminEventsController(EventRepository repo, EventNotificationService service,
                                 AuditService audit) {
        this.repo = repo;
        this.service = service;
        this.audit = audit;
    }

    @GetMapping("/types")
    public List<EventDtos.EventTypeInfo> types() {
        return EventCatalog.catalog();
    }

    @GetMapping("/rules")
    public List<EventDtos.NotificationRuleView> listRules() {
        return repo.listRules().stream().map(AdminEventsController::toRuleView).toList();
    }

    @PutMapping("/rules")
    public ResponseEntity<?> upsertRule(@Valid @RequestBody EventDtos.NotificationRuleRequest req,
                                        @RequestParam(required = false) String id) {
        if (!EventDtos.AUDIENCES.contains(req.audience())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "audience must be one of " + EventDtos.AUDIENCES));
        }
        if (EventDtos.AUDIENCE_ROLE.equals(req.audience())
                && (req.roleName() == null || req.roleName().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "roleName is required when audience is ROLE"));
        }
        Map<String, Object> row = new java.util.HashMap<>();
        if (id != null && !id.isBlank()) row.put("id", id);
        row.put("eventType", req.eventType().trim());
        row.put("audience", req.audience());
        row.put("roleName", req.roleName() == null ? "" : req.roleName().trim());
        row.put("templateCode", req.templateCode() == null ? "" : req.templateCode().trim());
        row.put("extraRecipients", req.extraRecipients() == null ? List.of() : req.extraRecipients());
        row.put("enabled", req.enabled());
        String savedId = repo.upsertRule(row);
        audit.record(CurrentUser.key(), "EVENT_RULE_UPSERT", savedId,
                Map.of("eventType", req.eventType(), "audience", req.audience()));
        return ResponseEntity.ok(repo.findRule(savedId).map(AdminEventsController::toRuleView)
                .orElseThrow(() -> new IllegalStateException("rule not persisted")));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable String id) {
        repo.deleteRule(id);
        audit.record(CurrentUser.key(), "EVENT_RULE_DELETE", id, Map.of());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/log")
    public List<EventDtos.EventLogView> log(@RequestParam(defaultValue = "100") int limit) {
        List<EventDtos.EventLogView> out = new ArrayList<>();
        for (Map<String, Object> e : repo.recentEvents(limit)) {
            out.add(new EventDtos.EventLogView(
                    str(e, "id"), str(e, "eventType"), str(e, "severity"), str(e, "source"),
                    str(e, "subject"), str(e, "message"),
                    e.get("recipientCount") instanceof Number n ? n.intValue() : 0,
                    str(e, "dispatchStatus"), str(e, "createdAt")));
        }
        return out;
    }

    /**
     * Fires a synthetic event for the current tenant so an admin can verify a
     * rule + SMTP configuration end to end without waiting for a real
     * failure.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> test(@RequestBody Map<String, String> body) {
        String eventType = Objects.toString(body.getOrDefault("eventType", EventCatalog.LOG_ALERT));
        service.dispatch(new EventDtos.EventIngestRequest(
                eventType,
                "INFO",
                "admin-console-service",
                "Test event fired from the Event Alerts tab",
                "This is a test of the event-communication framework. If you received this "
                        + "email, the rule and SMTP configuration for this tenant are working.",
                TenantContextHolder.requireTenantId().toString(),
                Map.of("firedBy", CurrentUser.key()),
                Instant.now().toString()));
        return ResponseEntity.ok(Map.of("status", "dispatched", "eventType", eventType));
    }

    private static EventDtos.NotificationRuleView toRuleView(Map<String, Object> r) {
        List<String> extras = new ArrayList<>();
        if (r.get("extraRecipients") instanceof List<?> xs) {
            for (Object x : xs) extras.add(Objects.toString(x, ""));
        }
        return new EventDtos.NotificationRuleView(
                str(r, "id"), str(r, "eventType"), str(r, "audience"), str(r, "roleName"),
                str(r, "templateCode"), extras,
                Boolean.TRUE.equals(r.get("enabled")),
                str(r, "createdAt"), str(r, "updatedAt"));
    }

    private static String str(Map<String, Object> m, String k) {
        return Objects.toString(m.getOrDefault(k, ""), "");
    }
}
