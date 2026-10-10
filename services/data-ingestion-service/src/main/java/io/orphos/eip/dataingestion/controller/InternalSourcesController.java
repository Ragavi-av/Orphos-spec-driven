package io.orphos.eip.dataingestion.controller;

import io.orphos.eip.dataingestion.repository.DataSourceRepository;
import io.orphos.eip.dataingestion.repository.MappingRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Service-to-service endpoint exposing this service's data sources for the
 * platform-core "Ontology Sources / data flow" aggregation. Each datasource is
 * returned with the REAL ontology entities it populates — the distinct
 * {@code targetClass} values from its configured mappings — plus its last
 * connection-test timestamp.
 *
 * <p>Guarded by the shared internal token; tenant named via {@code X-Tenant-Id}.</p>
 */
@RestController
@RequestMapping("/api/internal/sources")
public class InternalSourcesController {

    private final DataSourceRepository dataSources;
    private final MappingRepository mappings;

    @Value("${eip.security.internal-service-token:${eip.security.tenant-discovery.service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}}")
    private String internalToken;

    public InternalSourcesController(DataSourceRepository dataSources, MappingRepository mappings) {
        this.dataSources = dataSources;
        this.mappings = mappings;
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
        for (Map<String, Object> ds : dataSources.list()) {
            String id = str(ds.get("id"));
            Set<String> entities = new LinkedHashSet<>();
            try {
                for (Map<String, Object> m : mappings.list(id)) {
                    String tc = str(m.get("targetClass"));
                    if (!tc.isBlank()) entities.add(tc);
                }
            } catch (Exception ignored) { /* mappings optional */ }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", "data-ingestion");
            row.put("category", "Database");
            row.put("name", str(ds.get("name")));
            row.put("kind", str(ds.get("kind")));
            row.put("enabled", ds.get("enabled"));
            row.put("status", str(ds.get("lastTestStatus")));
            row.put("lastRefreshAt", str(ds.get("lastTestAt")));
            row.put("count", entities.size());
            row.put("ontologyEntities", new ArrayList<>(entities));
            out.add(row);
        }
        return out;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
