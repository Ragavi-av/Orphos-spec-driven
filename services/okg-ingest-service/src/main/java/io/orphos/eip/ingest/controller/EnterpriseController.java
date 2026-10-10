package io.orphos.eip.ingest.controller;

import io.orphos.eip.ingest.vo.EnterpriseDtos.EnterpriseSummary;
import io.orphos.eip.ingest.vo.EnterpriseDtos.UpsertEnterpriseRequest;
import io.orphos.eip.ingest.vo.EnterpriseDtos.UpsertEnterpriseResponse;
import io.orphos.eip.ingest.service.EnterpriseGraphService;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for populating and inspecting the tenant's Enterprise ontology graph
 * (company, mission/vision, functional areas, industries, clients/engagements,
 * executives, org structure, branches, locations).
 *
 * <p>Gated to the same role set as the rest of the OKG admin surface
 * ({@link io.orphos.eip.ingest.controller.OkgConnectionController}). Writes are
 * routed to the current tenant's ArcadeDB database by {@link EnterpriseGraphService}.</p>
 */
@RestController
@RequestMapping("/api/okg-ingest/admin/enterprise")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class EnterpriseController {

    private static final Logger log = LogManager.getLogger(EnterpriseController.class);

    private final EnterpriseGraphService service;

    public EnterpriseController(EnterpriseGraphService service) {
        this.service = service;
    }

    /** Upsert the whole enterprise profile. Idempotent per {@code externalId}. */
    @PutMapping
    public ResponseEntity<UpsertEnterpriseResponse> upsert(@Valid @RequestBody UpsertEnterpriseRequest req) {
        try {
            return ResponseEntity.ok(service.upsert(req));
        } catch (IllegalArgumentException e) {
            log.warn("Enterprise upsert rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    new UpsertEnterpriseResponse("REJECTED", req.externalId(), 0, 0, java.util.List.of(e.getMessage())));
        } catch (Exception e) {
            log.warn("Enterprise upsert failed: {}", e.getMessage(), e);
            return ResponseEntity.status(502).body(
                    new UpsertEnterpriseResponse("FAILED", req.externalId(), 0, 0, java.util.List.of(e.getMessage())));
        }
    }

    /** Count-per-type summary for the current tenant — quick verification after a load. */
    @GetMapping
    public ResponseEntity<EnterpriseSummary> summary() {
        return ResponseEntity.ok(service.summary());
    }
}
