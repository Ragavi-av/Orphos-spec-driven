package io.orphos.eip.ingest.controller;

import io.orphos.eip.common.security.EipRoles;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.ingest.service.GraphWriteService;
import io.orphos.eip.ingest.vo.GraphWriteDtos.UpsertEdgesRequest;
import io.orphos.eip.ingest.vo.GraphWriteDtos.UpsertEdgesResponse;
import io.orphos.eip.ingest.vo.GraphWriteDtos.UpsertVerticesRequest;
import io.orphos.eip.ingest.vo.GraphWriteDtos.UpsertVerticesResponse;
import io.orphos.eip.ingest.vo.GraphWriteDtos.VertexTypesResponse;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Generic graph write API — used by data-ingestion jobs to land mapped ERP/DB
 * rows as ontology vertices/edges. Accepts both human data/AI-admins (tenant from
 * the JWT) and internal service callers (the shared {@code X-Internal-Token} maps
 * to {@code ROLE_service_internal}; those carry the tenant in {@code X-Tenant-Id}
 * because a scheduler thread has no user JWT).
 */
@RestController
@RequestMapping("/api/okg-ingest/admin/graph")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin', 'service_internal')")
public class GraphWriteController {

    private static final Logger log = LogManager.getLogger(GraphWriteController.class);

    private final GraphWriteService service;

    public GraphWriteController(GraphWriteService service) {
        this.service = service;
    }

    /** Upsert a batch of vertices of one type; creates the type on demand when {@code ensureType}. */
    @PostMapping("/vertices/batch")
    public ResponseEntity<UpsertVerticesResponse> upsertVertices(
            @Valid @RequestBody UpsertVerticesRequest req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        try {
            return ResponseEntity.ok(withTenant(tenantId, () -> service.upsertVertices(req)));
        } catch (IllegalArgumentException e) {
            log.warn("Vertex upsert rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    new UpsertVerticesResponse("REJECTED", req.vertexType(), 0, List.of(e.getMessage())));
        } catch (Exception e) {
            log.warn("Vertex upsert failed: {}", e.getMessage(), e);
            return ResponseEntity.status(502).body(
                    new UpsertVerticesResponse("FAILED", req.vertexType(), 0, List.of(e.getMessage())));
        }
    }

    /** Upsert a batch of edges between already-written vertices. */
    @PostMapping("/edges/batch")
    public ResponseEntity<UpsertEdgesResponse> upsertEdges(
            @Valid @RequestBody UpsertEdgesRequest req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        try {
            return ResponseEntity.ok(withTenant(tenantId, () -> service.upsertEdges(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new UpsertEdgesResponse("REJECTED", req.edgeType(), 0, List.of(e.getMessage())));
        } catch (Exception e) {
            log.warn("Edge upsert failed: {}", e.getMessage(), e);
            return ResponseEntity.status(502).body(
                    new UpsertEdgesResponse("FAILED", req.edgeType(), 0, List.of(e.getMessage())));
        }
    }

    /** List existing vertex types for the tenant — feeds the mapping UI's target picker. */
    @GetMapping("/vertex-types")
    public ResponseEntity<VertexTypesResponse> vertexTypes(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return ResponseEntity.ok(withTenant(tenantId, () -> new VertexTypesResponse(service.listVertexTypes())));
    }

    /**
     * Run {@code body} on the tenant context {@code TenantResolutionFilter} bound (the
     * caller's JWT tenant, or the header tenant of a verified platform admin / internal
     * caller). The raw {@code X-Tenant-Id} is honoured only as a fallback for an
     * internal-service caller with no bound context — a tenant user's header is ignored,
     * or a data admin in one tenant could write into another tenant's graph.
     */
    private <T> T withTenant(String tenantId, Callable<T> body) {
        if (TenantContextHolder.current().isPresent()) {
            return call(body);
        }
        if (tenantId != null && !tenantId.isBlank()
                && EipRoles.hasRole(SecurityContextHolder.getContext().getAuthentication(), EipRoles.SERVICE_INTERNAL)) {
            UUID tid;
            try {
                tid = UUID.fromString(tenantId.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid X-Tenant-Id: " + tenantId);
            }
            return TenantContextHolder.callWith(new TenantContext(tid, null, false), () -> call(body));
        }
        return call(body);
    }

    private static <T> T call(Callable<T> body) {
        try {
            return body.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
