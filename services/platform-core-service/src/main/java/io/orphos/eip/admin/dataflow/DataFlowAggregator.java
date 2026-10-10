package io.orphos.eip.admin.dataflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.admin.dbconnections.dto.DbConnectionDtos.ConnectionView;
import io.orphos.eip.admin.dbconnections.service.DbConnectionService;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregates the "Ontology Sources / data flow" view by fanning out to each
 * source-owning service's {@code GET /api/internal/sources} endpoint and merging
 * the normalized rows. Server-side fan-out (rather than a browser fan-out) is
 * used because some of these services are plugin-gated or unrouted from the
 * browser, and it gives one auth surface + one JSON shape.
 *
 * <p>Per-service failures are isolated: a down/slow service is reported in the
 * {@code services[]} status list and simply contributes no rows, so the view
 * degrades gracefully instead of erroring.</p>
 */
@Service
public class DataFlowAggregator {

    private static final Logger log = LogManager.getLogger(DataFlowAggregator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final DbConnectionService dbConnections;

    public DataFlowAggregator(DbConnectionService dbConnections) {
        this.dbConnections = dbConnections;
    }

    @Value("${eip.security.internal-service-token:${eip.security.tenant-discovery.service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}}")
    private String internalToken;

    @Value("${eip.dataflow.data-ingestion-url:${EIP_DATA_INGESTION_URL:http://eip-data-ingestion:8096}}")
    private String dataIngestionUrl;
    @Value("${eip.dataflow.doc-ingest-url:${EIP_DOC_INGEST_URL:http://eip-doc-ingest:8091}}")
    private String docIngestUrl;
    @Value("${eip.dataflow.mail-insight-url:${EIP_MAIL_INSIGHT_URL:http://eip-mail-insight:8092}}")
    private String mailInsightUrl;
    @Value("${eip.dataflow.okg-ingest-url:${EIP_OKG_INGEST_URL:http://eip-okg-ingest:8081}}")
    private String okgIngestUrl;

    private record Target(String key, String url) {}

    public Map<String, Object> aggregate() {
        Optional<TenantContext> ctx = TenantContextHolder.current();
        if (ctx.isEmpty()) {
            return Map.of("available", false,
                    "message", "Pick a tenant to view its data-flow sources.",
                    "sources", List.of(), "services", List.of());
        }
        String tenantId = ctx.get().tenantId().toString();

        List<Target> targets = List.of(
                new Target("data-ingestion", dataIngestionUrl),
                new Target("doc-ingest", docIngestUrl),
                new Target("mail-insight", mailInsightUrl),
                new Target("okg-ingest", okgIngestUrl));

        List<Map<String, Object>> sources = new ArrayList<>();
        List<Map<String, Object>> serviceStatus = new ArrayList<>();
        for (Target t : targets) {
            try {
                List<Map<String, Object>> rows = fetch(t.url(), tenantId);
                sources.addAll(rows);
                serviceStatus.add(status(t.key(), true, rows.size(), null));
            } catch (Exception e) {
                log.warn("data-flow: could not fetch sources from {} ({}): {}", t.key(), t.url(), e.getMessage());
                serviceStatus.add(status(t.key(), false, 0, e.getMessage()));
            }
        }

        // Platform infrastructure: the DB-connection registry (backing stores).
        // Platform-global (not tenant-scoped) and read locally — no HTTP. Only
        // name/type/last-test are exposed here; hosts/URLs/credentials are not.
        try {
            for (ConnectionView c : dbConnections.list()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("service", "platform-core");
                r.put("category", "Infrastructure");
                r.put("name", c.name());
                r.put("kind", c.type());
                r.put("enabled", true);
                r.put("status", c.lastTestStatus());
                r.put("lastRefreshAt", c.lastTestAt() == null ? "" : c.lastTestAt());
                r.put("count", null);
                r.put("ontologyEntities", List.of());
                sources.add(r);
            }
            serviceStatus.add(status("infrastructure", true, 0, null));
        } catch (Exception e) {
            log.warn("data-flow: could not read DB-connection registry: {}", e.getMessage());
            serviceStatus.add(status("infrastructure", false, 0, e.getMessage()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("sources", sources);
        out.put("services", serviceStatus);
        return out;
    }

    private List<Map<String, Object>> fetch(String baseUrl, String tenantId) throws Exception {
        if (baseUrl == null || baseUrl.isBlank()) return List.of();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(baseUrl.replaceAll("/+$", "") + "/api/internal/sources"))
                .timeout(Duration.ofSeconds(5))
                .header("X-Service-Token", internalToken)
                .header("X-Internal-Token", internalToken)
                .header("X-Tenant-Id", tenantId)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return MAPPER.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
    }

    private static Map<String, Object> status(String service, boolean ok, int count, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", service);
        m.put("ok", ok);
        m.put("count", count);
        if (error != null) m.put("error", error);
        return m;
    }
}
