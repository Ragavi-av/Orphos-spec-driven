package io.orphos.eip.ingest.controller;

import io.orphos.eip.ingest.repository.OkgConnectionRepository;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service endpoint exposing this service's ontology sources (OKG
 * connections, e.g. Jira) for the platform-core data-flow aggregation. The
 * ontology entities reported are the OKG graph vertex types a successful sync
 * populates (Project / Issue / Person) — real graph schema, shown only once the
 * source has actually synced. Guarded by the shared internal token.
 */
@RestController
@RequestMapping("/api/internal/sources")
public class InternalSourcesController {

    private final OkgConnectionRepository connections;

    @Value("${eip.security.internal-service-token:${eip.security.tenant-discovery.service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}}")
    private String internalToken;

    public InternalSourcesController(OkgConnectionRepository connections) {
        this.connections = connections;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> sources(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {

        if (internalToken == null || internalToken.isBlank() || !constantTimeEquals(internalToken, token)) {
            return ResponseEntity.status(401).build();
        }
        UUID tid;
        try { tid = UUID.fromString(String.valueOf(tenantId).trim()); }
        catch (Exception e) { return ResponseEntity.badRequest().build(); }

        List<Map<String, Object>> out = TenantContextHolder.callWith(
                new TenantContext(tid, null, false), this::collect);
        return ResponseEntity.ok(out);
    }

    private List<Map<String, Object>> collect() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : connections.listConnections()) {
            String status = str(c.get("lastSyncStatus"));
            List<String> entities = "SUCCESS".equalsIgnoreCase(status)
                    ? List.of("Project", "Issue", "Person")   // OKG graph vertex types a Jira sync populates
                    : List.of();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", "okg-ingest");
            row.put("category", "Ontology Graph");
            row.put("name", str(c.get("source")));
            row.put("kind", str(c.get("source")));
            row.put("enabled", c.get("enabled"));
            row.put("status", status);
            row.put("lastRefreshAt", str(c.get("lastSyncAt")));
            row.put("count", projectKeyCount(c.get("projectKeys")));
            row.put("ontologyEntities", entities);
            out.add(row);
        }
        return out;
    }

    private static int projectKeyCount(Object projectKeys) {
        if (projectKeys instanceof List<?> l) return l.size();
        String s = str(projectKeys).trim();
        if (s.isEmpty()) return 0;
        int n = 0;
        for (String p : s.split(",")) if (!p.trim().isEmpty()) n++;
        return n;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
