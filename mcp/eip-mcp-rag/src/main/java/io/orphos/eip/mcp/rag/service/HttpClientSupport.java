package io.orphos.eip.mcp.rag.service;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.mcp.rag.config.RagMcpProperties;
import io.orphos.eip.mcp.spec.McpHeaders;
import io.orphos.eip.mcp.spec.McpTracePropagator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Thin HTTP forwarder to upstream services (TO-BE design §14: wrap, don't re-implement). Propagates
 * the tenant + internal-service token. Reads degrade to a short message; the base URL is passed per
 * call because this server fronts more than one upstream.
 *
 * <p>Two clients rather than one: RestTemplate fixes its read timeout per instance, and the
 * search/answer POSTs are LLM-backed so they need considerably longer than the plain GETs. Both are
 * lenient (they return 4xx/5xx instead of throwing) so the status-code branches below can inspect
 * {@code ResponseEntity.getStatusCode()} directly.</p>
 *
 * <p>Every {@code RagTools} method is read-only (search/get/answer never mutate upstream state, per
 * that class's javadoc), so unlike the write-capable MCP clients elsewhere in this repo, retrying is
 * safe here regardless of verb — a repeated GET or a repeated "search"/"answer" POST can never
 * duplicate a side effect, only redo an idempotent lookup.</p>
 */
@Component
public class HttpClientSupport {

    private static final Logger log = LogManager.getLogger(HttpClientSupport.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GET_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POST_READ_TIMEOUT = Duration.ofSeconds(60);

    private final RagMcpProperties props;
    private final RestTemplate getClient;
    private final RestTemplate postClient;
    private final Retry getRetry;
    private final Retry postRetry;

    public HttpClientSupport(RagMcpProperties props, EipRestTemplates restTemplates, EipRetries retries,
                             McpTracePropagator tracing) {
        this.props = props;
        this.getClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, GET_READ_TIMEOUT));
        this.postClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, POST_READ_TIMEOUT));
        this.getRetry = retries.retry("rag-upstream-read", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(500)))
                .build());
        // A shorter attempt count for the LLM-backed search/answer path: each retry redoes an
        // expensive call, so this trades a little less resilience for not doubling worst-case
        // latency/cost on a slow-but-eventually-failing nl-query/doc-ingest call.
        this.postRetry = retries.retry("rag-upstream-search", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofSeconds(1)))
                .build());
    }

    /** GET a read endpoint; returns the body, or a short message on failure. */
    public String get(String baseUrl, String path, String tenantId) {
        return exchange(getClient, getRetry, HttpMethod.GET, baseUrl, path,
                new HttpEntity<>(headers(tenantId, null)));
    }

    /** POST a JSON read endpoint (search / answer); returns the body, or a short message on failure. */
    public String postJson(String baseUrl, String path, String tenantId, String bodyJson) {
        return exchange(postClient, postRetry, HttpMethod.POST, baseUrl, path,
                new HttpEntity<>(bodyJson, headers(tenantId, MediaType.APPLICATION_JSON)));
    }

    private String exchange(RestTemplate client, Retry retry, HttpMethod method, String baseUrl, String path,
                            HttpEntity<?> entity) {
        try {
            return EipResilienceDecorators.call(
                    () -> doExchange(client, method, baseUrl, path, entity),
                    null, null, retry);
        } catch (Exception e) {
            // warn (an outage is invisible at the INFO root level) and with the throwable itself,
            // which is what fills errorClassName/errorMessage/stackTrace in the JSON layout.
            log.warn("{} {} failed: {}", method, path, e.getMessage(), e);
            return "upstream unavailable (" + e.getMessage() + ")";
        }
    }

    private String doExchange(RestTemplate client, HttpMethod method, String baseUrl, String path,
                              HttpEntity<?> entity) {
        ResponseEntity<String> res = client.exchange(strip(baseUrl) + path, method, entity, String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            // Thrown (rather than returned) so the retry above sees this as a failure — a 5xx
            // from a flaky upstream is exactly what the retry exists to smooth over.
            throw new IllegalStateException(
                    "upstream returned HTTP " + res.getStatusCode().value() + " for " + path);
        }
        // An empty 2xx body arrived as "" from the JDK client; keep that contract so callers never see null.
        return res.getBody() == null ? "" : res.getBody();
    }

    private HttpHeaders headers(String tenantId, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        if (tenantId != null && !tenantId.isBlank()) headers.set(McpHeaders.TENANT_ID, tenantId);
        if (contentType != null) headers.setContentType(contentType);
        return headers;
    }

    private static String strip(String b) {
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
