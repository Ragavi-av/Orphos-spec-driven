package io.orphos.eip.docingest.access;

import io.orphos.eip.docingest.ingestion.DocumentRegistry;
import io.orphos.eip.docingest.source.SourceConnectionRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service endpoint exposing this service's source systems — document
 * source connectors (LOCAL/S3/FTP/…) with their last sync time, plus an
 * aggregate row for ingested files (count + last ingested time) — for the
 * platform-core data-flow aggregation. Documents become RAG vector chunks, not
 * ontology-graph entities, so {@code ontologyEntities} is empty. Guarded by the
 * shared internal token.
 */
@RestController
@RequestMapping("/api/internal/sources")
public class InternalSourcesController {

    private final SourceConnectionRepository sources;
    private final DocumentRegistry documents;

    @Value("${eip.security.internal-service-token:${eip.security.tenant-discovery.service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}}")
    private String internalToken;

    public InternalSourcesController(SourceConnectionRepository sources, DocumentRegistry documents) {
        this.sources = sources;
        this.documents = documents;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> sourcesList(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {

        if (internalToken == null || internalToken.isBlank() || !constantTimeEquals(internalToken, token)) {
            return ResponseEntity.status(401).build();
        }
        UUID tid;
        try { tid = UUID.fromString(String.valueOf(tenantId).trim()); }
        catch (Exception e) { return ResponseEntity.badRequest().build(); }

        List<Map<String, Object>> out = TenantContextHolder.callWith(
                new TenantContext(tid, null, false), () -> collect(tid.toString()));
        return ResponseEntity.ok(out);
    }

    private List<Map<String, Object>> collect(String tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();

        // Source connectors (folder connections).
        for (Map<String, Object> s : sources.list()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("service", "doc-ingest");
            r.put("category", "Document Source");
            r.put("name", str(s.get("displayName")));
            r.put("kind", str(s.get("kind")));
            r.put("enabled", s.get("enabled"));
            r.put("status", str(s.get("lastSyncStatus")));
            r.put("lastRefreshAt", str(s.get("lastSyncAt")));
            r.put("count", null);
            r.put("ontologyEntities", List.of());
            out.add(r);
        }

        // Aggregate: ingested files (count + latest ingested time).
        try {
            List<DocumentRegistry.Entry> docs = documents.list(tenantId);
            Instant latest = null;
            for (DocumentRegistry.Entry e : docs) {
                Instant t = e.getCompletedAt() != null ? e.getCompletedAt() : e.getSubmittedAt();
                if (t != null && (latest == null || t.isAfter(latest))) latest = t;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("service", "doc-ingest");
            r.put("category", "Files");
            r.put("name", "Ingested documents");
            r.put("kind", "FILES");
            r.put("enabled", true);
            r.put("status", "");
            r.put("lastRefreshAt", latest == null ? "" : latest.toString());
            r.put("count", docs.size());
            r.put("ontologyEntities", List.of());
            out.add(r);
        } catch (Exception ignored) { /* registry optional */ }

        return out;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
