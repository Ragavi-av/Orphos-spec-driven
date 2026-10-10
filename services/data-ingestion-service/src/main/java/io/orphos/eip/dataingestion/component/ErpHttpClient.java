package io.orphos.eip.dataingestion.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.dataingestion.dao.ConnectionTester;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.RestAuthType;
import io.orphos.eip.dataingestion.component.ErpTokenProvider;
import io.orphos.eip.dataingestion.vo.ErpConfig;
import io.orphos.eip.dataingestion.vo.ErpConnection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared outbound reader for enterprise-system (REST_OPENAPI / ZOHO_ERP) sources —
 * used by both metadata discovery (sampling one page to infer fields) and job
 * extraction (paging the full object). One page per call; the caller drives the
 * paging loop.
 *
 * <p>Uses the JDK {@link HttpClient} + Jackson idiom established across the
 * platform. Auth is resolved per request: {@code OAUTH2} mints/reuses a bearer
 * via {@link ErpTokenProvider}; other kinds reuse {@link ConnectionTester}'s
 * header logic.</p>
 */
@Component
public class ErpHttpClient {

    private static final Logger log = LogManager.getLogger(ErpHttpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ErpTokenProvider tokenProvider;
    private final int timeoutSeconds;
    private final HttpClient http;

    public ErpHttpClient(ErpTokenProvider tokenProvider,
                         @Value("${ingestion.erp.request-timeout-seconds:30}") int timeoutSeconds) {
        this.tokenProvider = tokenProvider;
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 15)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** One page of records from an object endpoint, plus paging continuation signals. */
    public record Page(List<Map<String, Object>> records, boolean hasMore, String nextCursor) {}

    /**
     * Fetch a single page.
     *
     * @param pageNumber 1-based page/offset value (ignored for CURSOR / NONE styles)
     * @param cursor     next-page token for CURSOR style (null on the first page)
     * @param extraQuery additional query params (e.g. the incremental filter); may be empty
     */
    public Page fetch(ErpConnection conn, ErpConfig.ErpObject obj, int pageNumber, String cursor,
                      Map<String, String> extraQuery) {
        ErpConfig cfg = conn.config();
        ErpConfig.Pagination pg = cfg.paginationOrNone();
        Map<String, String> query = new LinkedHashMap<>();
        if (extraQuery != null) query.putAll(extraQuery);

        switch (pg.styleOrNone()) {
            case "PAGE", "OFFSET" -> {
                if (pg.pageParam() != null && !pg.pageParam().isBlank()) query.put(pg.pageParam(), String.valueOf(pageNumber));
                if (pg.sizeParam() != null && !pg.sizeParam().isBlank()) query.put(pg.sizeParam(), String.valueOf(pg.pageSizeOrDefault()));
            }
            case "CURSOR" -> {
                if (cursor != null && !cursor.isBlank() && pg.cursorParam() != null && !pg.cursorParam().isBlank()) {
                    query.put(pg.cursorParam(), cursor);
                }
                if (pg.sizeParam() != null && !pg.sizeParam().isBlank()) query.put(pg.sizeParam(), String.valueOf(pg.pageSizeOrDefault()));
            }
            default -> { /* NONE — single page, no paging params */ }
        }

        String url = buildUrl(conn.baseUrl(), obj.path(), query);
        JsonNode root = get(conn, url);

        List<Map<String, Object>> records = extractRecords(root, obj.dataPath());
        boolean hasMore = extractBool(root, pg.moreFlagPath());
        String nextCursor = pg.cursorPath() == null ? null : textAtPath(root, pg.cursorPath());
        return new Page(records, hasMore, nextCursor);
    }

    private JsonNode get(ErpConnection conn, String url) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json")
                    .GET();
            applyAuth(rb, conn);
            HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = res.statusCode();
            if (status == 204) return MAPPER.createObjectNode();          // no content
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Source returned HTTP " + status + " for " + safeUrl(url));
            }
            if (res.body() == null || res.body().isBlank()) return MAPPER.createObjectNode();
            return MAPPER.readTree(res.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Source request failed: " + e.getMessage(), e);
        }
    }

    private void applyAuth(HttpRequest.Builder rb, ErpConnection conn) {
        if (conn.authType() == RestAuthType.OAUTH2) {
            String token = tokenProvider.token(conn.sourceId(), conn.config(), conn.secret());
            ConnectionTester.applyAuth(rb, RestAuthType.BEARER, null, null, token);
        } else {
            ConnectionTester.applyAuth(rb, conn.authType(), conn.authHeaderName(), conn.username(), conn.secret());
        }
    }

    // ── JSON extraction helpers ───────────────────────────────────────────────

    /** The record list at {@code dataPath} (dotted), coercing objects/arrays into a list of maps. */
    private static List<Map<String, Object>> extractRecords(JsonNode root, String dataPath) {
        JsonNode node = (dataPath == null || dataPath.isBlank()) ? root : nodeAtPath(root, dataPath);
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) return out;
        if (node.isArray()) {
            for (JsonNode el : node) if (el.isObject()) out.add(toFlatMap(el));
        } else if (node.isObject()) {
            out.add(toFlatMap(node));   // single-record response
        }
        return out;
    }

    /** Flatten a record to string-valued columns; nested objects/arrays become JSON text. */
    private static Map<String, Object> toFlatMap(JsonNode obj) {
        Map<String, Object> m = new LinkedHashMap<>();
        obj.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) m.put(e.getKey(), null);
            else if (v.isValueNode()) m.put(e.getKey(), v.asText());
            else m.put(e.getKey(), v.toString());   // nested object/array → JSON string
        });
        return m;
    }

    private static JsonNode nodeAtPath(JsonNode root, String dotted) {
        JsonNode n = root;
        for (String seg : dotted.split("\\.")) {
            if (n == null) return null;
            n = n.get(seg);
        }
        return n;
    }

    private static boolean extractBool(JsonNode root, String path) {
        if (path == null || path.isBlank()) return false;
        JsonNode n = nodeAtPath(root, path);
        return n != null && (n.asBoolean(false) || "true".equalsIgnoreCase(n.asText("")));
    }

    private static String textAtPath(JsonNode root, String path) {
        JsonNode n = nodeAtPath(root, path);
        return n == null || n.isNull() ? null : n.asText(null);
    }

    static String buildUrl(String base, String path, Map<String, String> query) {
        String url = joinUrl(base == null ? "" : base, path == null ? "" : path);
        if (query == null || query.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private static String joinUrl(String base, String path) {
        if (path.isBlank()) return base;
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    /** URL without query string — keeps any token in a query param out of logs/errors. */
    private static String safeUrl(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
