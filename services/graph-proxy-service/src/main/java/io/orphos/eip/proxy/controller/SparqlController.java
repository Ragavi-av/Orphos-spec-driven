package io.orphos.eip.proxy.controller;

import io.orphos.eip.common.security.EipRoles;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.proxy.component.SparqlTenantRewriter;
import io.orphos.eip.proxy.component.SparqlTenantRewriter.CrossTenantUpdateRejectedException;
import io.orphos.eip.proxy.component.SparqlTenantRewriter.InvalidSparqlException;
import io.orphos.eip.proxy.service.TenantInfoCache;
import io.orphos.eip.proxy.vo.TenantRoutingInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// RequestHeader is imported above via org.springframework.web.bind.annotation.*

/**
 * SPARQL passthrough to Fuseki, routed and (when needed) rewritten per tenant.
 *
 * <p><b>Tenant isolation model:</b>
 * <ul>
 *   <li><b>DEDICATED</b> — tenant has its own Fuseki dataset at
 *       {@code fusekiUrl/{tenant.dataset}/sparql}. SPARQL passes through
 *       unchanged.</li>
 *   <li><b>SHARED</b> — tenant uses the shared dataset (typically
 *       {@code orphos}) with one named graph per tenant
 *       ({@code urn:eip:tenant:{uuid}}). Incoming SPARQL is rewritten via
 *       {@link SparqlTenantRewriter} to add a {@code FROM} clause pointing
 *       at the calling tenant's graph; any user-supplied FROM clauses are
 *       stripped first. SPARQL Update is rejected for now — see the
 *       rewriter's docstring.</li>
 * </ul>
 *
 * <p>Routing decision is made per request by looking up the calling tenant
 * via {@link TenantInfoCache} (60s TTL Caffeine cache). A tenant not present
 * in the cache or admin-service produces HTTP 401 — no shared-default
 * fallback to prevent cross-tenant leakage.</p>
 */
@RestController
@RequestMapping("/api/sparql")
public class SparqlController {

    private static final Logger log = LogManager.getLogger(SparqlController.class);

    private final RestTemplate rest;
    private final TenantInfoCache tenantInfoCache;
    private final SparqlTenantRewriter rewriter;

    @Value("${fuseki.url:http://eip-fuseki:3030}")
    private String fusekiUrl;

    @Value("${fuseki.user:admin}")
    private String fusekiUser;

    @Value("${fuseki.password:}")
    private String fusekiPassword;

    public SparqlController(TenantInfoCache tenantInfoCache, SparqlTenantRewriter rewriter, RestTemplate rest) {
        this.tenantInfoCache = tenantInfoCache;
        this.rewriter = rewriter;
        this.rest = rest;
    }

    @PostMapping(consumes = {"application/sparql-query", "application/sparql-update"})
    public ResponseEntity<Object> executeSparql(
            @RequestBody String sparqlQuery,
            @RequestHeader(value = "Content-Type", required = false) String contentType) {
        UUID tenantId = TenantContextHolder.requireTenantId();
        boolean isUpdate = contentType != null && contentType.toLowerCase().contains("sparql-update");
        // Reads are open to any member; writing the tenant's graph is a data-administration
        // action (data_ai_admin, inherited by admin and platform_admin) or an internal call.
        // An update sent as application/sparql-query goes to Fuseki's query endpoint,
        // which does not execute updates.
        if (isUpdate && !EipRoles.hasAnyRole(SecurityContextHolder.getContext().getAuthentication(),
                EipRoles.DATA_AI_ADMIN, EipRoles.SERVICE_INTERNAL)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "SPARQL update requires the Data & AI admin role"));
        }

        Optional<TenantRoutingInfo> maybeInfo = tenantInfoCache.find(tenantId);
        if (maybeInfo.isEmpty()) {
            log.warn("SPARQL request rejected — tenant {} not resolvable via admin-service", tenantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Tenant not registered or admin-service unreachable"));
        }
        TenantRoutingInfo info = maybeInfo.get();
        if (!info.active()) {
            log.warn("SPARQL request rejected — tenant {} is not ACTIVE", tenantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Tenant is not active"));
        }

        String dataset = info.dataset();
        String upstreamSparql = sparqlQuery;

        if (info.isolationMode() == TenantRoutingInfo.IsolationMode.SHARED) {
            try {
                upstreamSparql = rewriter.rewriteForTenant(sparqlQuery, tenantId);
            } catch (CrossTenantUpdateRejectedException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", e.getMessage(), "code", "sparql_update_unsupported"));
            } catch (InvalidSparqlException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", e.getMessage(), "code", "sparql_parse_error"));
            }
            if (log.isDebugEnabled()) {
                log.debug("SPARQL rewritten for tenant {} (SHARED mode, dataset={})", tenantId, dataset);
            }
        }

        // SPARQL Update goes to /update; queries go to /sparql. Both share
        // the same dataset path prefix.
        String url = fusekiUrl + "/" + dataset + (isUpdate ? "/update" : "/sparql");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(isUpdate ? "application/sparql-update" : "application/sparql-query"));
        headers.set("Accept", "application/sparql-results+json");
        headers.set("X-Tenant-Id", tenantId.toString());
        headers.setBasicAuth(fusekiUser, fusekiPassword);

        HttpEntity<String> entity = new HttpEntity<>(upstreamSparql, headers);

        try {
            ResponseEntity<Object> response = rest.exchange(url, HttpMethod.POST, entity, Object.class);
            log.debug("SPARQL ok tenant={} dataset={} mode={} bytes={}",
                    tenantId, dataset, info.isolationMode(),
                    response.getBody() != null ? response.getBody().toString().length() : 0);
            return ResponseEntity.ok()
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("X-Tenant-Dataset", dataset)
                    .header("X-Tenant-Isolation", info.isolationMode().name())
                    .body(response.getBody());
        } catch (Exception e) {
            log.warn("SPARQL upstream failed tenant={} dataset={}: {}", tenantId, dataset, e.getMessage());
            return ResponseEntity.status(503).body(Map.of("error", "Fuseki unavailable: " + e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok(Map.of("status", "up", "fuseki", fusekiUrl));
    }
}
