package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.vo.EventDtos;
import io.orphos.eip.admin.service.EventNotificationService;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Service-to-service event ingestion for the event-communication framework.
 *
 * <p>Not JWT-gated — guarded by the shared {@code X-Internal-Token} (same
 * pattern as nl-query-service's InternalCompletionController and the
 * tenant-discovery endpoints). The path is permitAll in {@code SecurityConfig};
 * with a blank/missing token every request is rejected, so the endpoint is
 * fail-closed until the secret is configured.</p>
 */
@RestController
@RequestMapping("/api/internal/events")
public class InternalEventController {

    private static final Logger log = LogManager.getLogger(InternalEventController.class);

    private final EventNotificationService service;
    private final String internalToken;

    public InternalEventController(
            EventNotificationService service,
            @Value("${admin.events.internal-token:${eip.security.tenant-discovery.service-token:}}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @PostMapping
    public ResponseEntity<?> ingest(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody EventDtos.EventIngestRequest req) {
        if (internalToken.isEmpty() || token == null || !internalToken.equals(token.trim())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid internal token"));
        }
        try {
            service.dispatch(req);
        } catch (Exception e) {
            // Dispatch is best-effort; never bubble a 5xx back to producers.
            log.error("Event {} dispatch failed: {}", req.eventType(), e.getMessage(), e);
        }
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }
}
