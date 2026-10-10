package io.orphos.eip.dataingestion.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Target-side writer for graph-vertex mappings: batches mapped rows and POSTs
 * them to okg-ingest-service's generic graph write API, which owns the ontology
 * upsert logic ({@code (tenantId, externalId)} key, vertex-type creation). This
 * is the "delegate to okg-ingest" half of the graph target — the data-ingestion
 * side never touches the graph schema directly.
 *
 * <p>Authenticates service-to-service with the shared {@code X-Internal-Token}
 * (mapped to {@code ROLE_service_internal} by okg-ingest's security filter) and
 * names the tenant in {@code X-Tenant-Id}, because a scheduler thread carries no
 * user JWT.</p>
 */
@Component
public class GraphTargetWriter {

    private static final Logger log = LogManager.getLogger(GraphTargetWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String internalToken;
    private final int requestTimeoutSeconds;

    public GraphTargetWriter(
            @Value("${ingestion.okg-ingest.base-url:http://eip-okg-ingest:8081}") String baseUrl,
            @Value("${eip.security.internal-service-token:}") String internalToken,
            @Value("${ingestion.okg-ingest.request-timeout-seconds:60}") int requestTimeoutSeconds) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.internalToken = internalToken;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    /** One vertex to upsert: its natural key plus mapped properties. */
    public record Vertex(String externalId, Map<String, Object> properties) {}

    /** One edge to create, endpoints named by their {@code externalId}s. */
    public record EdgeLink(String fromExternalId, String toExternalId) {}

    /**
     * Upsert a batch of vertices of one type. Returns how many okg-ingest reports
     * as written; the caller derives failures as {@code batch.size() - written}.
     *
     * @param ensureType create the vertex type + indexes if absent (send true on the first batch of a run)
     */
    public int upsertVertices(String vertexType, boolean ensureType, List<Vertex> batch) {
        if (batch == null || batch.isEmpty()) return 0;
        Map<String, Object> body = Map.of(
                "vertexType", vertexType,
                "ensureType", ensureType,
                "rows", batch.stream()
                        .map(v -> Map.of("externalId", v.externalId(),
                                "properties", v.properties() == null ? Map.of() : v.properties()))
                        .toList());
        return post("/api/okg-ingest/admin/graph/vertices/batch", body, "vertices " + vertexType);
    }

    /**
     * Create a batch of edges of one type between {@code fromType} and
     * {@code toType} vertices. Returns how many okg-ingest reports as created
     * (edges whose endpoints are missing are skipped, not counted).
     */
    public int upsertEdges(String edgeType, String fromType, String toType, boolean ensureType, List<EdgeLink> batch) {
        if (batch == null || batch.isEmpty()) return 0;
        Map<String, Object> body = Map.of(
                "edgeType", edgeType,
                "fromType", fromType,
                "toType", toType,
                "ensureType", ensureType,
                "links", batch.stream()
                        .map(l -> Map.of("fromExternalId", l.fromExternalId(),
                                "toExternalId", l.toExternalId()))
                        .toList());
        return post("/api/okg-ingest/admin/graph/edges/batch", body, "edges " + edgeType);
    }

    /** POST a graph write batch; returns the reported {@code written} count. */
    private int post(String path, Map<String, Object> body, String label) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException(
                    "Graph target requires eip.security.internal-service-token to call okg-ingest");
        }
        String tenantId = TenantContextHolder.requireTenantId().toString();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Tenant-Id", tenantId)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("okg-ingest graph write failed: HTTP " + res.statusCode()
                        + " " + truncate(res.body()));
            }
            JsonNode root = MAPPER.readTree(res.body());
            JsonNode warnings = root.path("warnings");
            if (warnings.isArray() && !warnings.isEmpty()) {
                log.debug("Graph write ({}) returned {} warnings (first: {})",
                        label, warnings.size(), warnings.get(0).asText(""));
            }
            return root.path("written").asInt(0);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("okg-ingest graph write call failed: " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
