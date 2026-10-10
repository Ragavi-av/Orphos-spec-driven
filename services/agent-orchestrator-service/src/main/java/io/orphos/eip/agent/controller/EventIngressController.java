package io.orphos.eip.agent.controller;

import io.orphos.eip.agent.component.EventRouter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * HTTP event ingress (TO-BE design §12 Phase 4) — the broker-free path to trigger event-driven
 * agents, and the way other services (or tests) publish agent events without Kafka. Same routing as
 * the Kafka listener via {@link EventRouter}. Service-to-service auth uses the internal token.
 */
@RestController
@RequestMapping("/api/orchestrator/events")
public class EventIngressController {

    private final EventRouter router;

    public EventIngressController(EventRouter router) {
        this.router = router;
    }

    /**
     * Publishes an event for the tenant in the {@code X-Tenant-Id} header. Body:
     * {@code {"eventType": "...", "payload": { ... }}}. Returns how many agents were fired.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_service_internal') or hasAuthority('ROLE_platform_admin')")
    public ResponseEntity<Map<String, Object>> publish(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        String eventType = String.valueOf(body.getOrDefault("eventType", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        int fired = router.route(tenantId, eventType, payload);
        return ResponseEntity.ok(Map.of("eventType", eventType, "agentsFired", fired));
    }
}
