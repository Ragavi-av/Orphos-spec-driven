package io.orphos.eip.mcp.ingest.service;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.mcp.ingest.config.IngestMcpProperties;
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
 * Thin HTTP forwarder to the ingestion upstreams (TO-BE design §14). Base URL is passed per call
 * because this server fronts two upstreams (doc-ingest, okg-ingest). Reads degrade to a short
 * message; writes ({@link #postStrict}) surface non-2xx so the approval flow can mark them FAILED.
 *
 * <p>{@link #get} retries once (a read is idempotent); {@link #postStrict} does not (a write must
 * not be silently retried — see its own javadoc).</p>
 */
@Component
public class IngestClient {

    private static final Logger log = LogManager.getLogger(IngestClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GET_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POST_READ_TIMEOUT = Duration.ofSeconds(120);

    private final IngestMcpProperties props;
    private final Retry getRetry;
    private final RestTemplate getClient;
    private final RestTemplate postClient;

    public IngestClient(IngestMcpProperties props, EipRetries retries, EipRestTemplates restTemplates,
                        McpTracePropagator tracing) {
        this.props = props;
        this.getRetry = retries.retry("ingest-upstream-read", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(500)))
                .build());
        this.getClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, GET_READ_TIMEOUT));
        this.postClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, POST_READ_TIMEOUT));
    }

    /** GET a read endpoint; returns the body, or a short message on failure. */
    public String get(String baseUrl, String path, String tenantId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
            if (tenantId != null && !tenantId.isBlank()) headers.set(McpHeaders.TENANT_ID, tenantId);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            return EipResilienceDecorators.call(() -> sendGet(baseUrl, path, entity), null, null, getRetry);
        } catch (Exception e) {
            // warn (an outage is invisible at the INFO root level) and with the throwable itself,
            // which is what fills errorClassName/errorMessage/stackTrace in the JSON layout.
            log.warn("GET {} failed: {}", path, e.getMessage(), e);
            return "upstream unavailable (" + e.getMessage() + ")";
        }
    }

    private String sendGet(String baseUrl, String path, HttpEntity<Void> entity) {
        ResponseEntity<String> res = getClient.exchange(strip(baseUrl) + path, HttpMethod.GET, entity, String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("upstream returned HTTP " + res.getStatusCode().value() + " for " + path);
        }
        return res.getBody() == null ? "" : res.getBody();
    }

    /**
     * POST a write endpoint; returns the body on 2xx, throws otherwise so the write is not silently
     * lost.
     *
     * <p>Only the send is guarded, so a transport failure is reported as such; the status check that
     * follows is left outside the catch so its {@code IllegalStateException} — which carries the
     * admin-gating hint — is not re-wrapped in a second one that reads like a connection failure.</p>
     */
    public String postStrict(String baseUrl, String path, String tenantId, String bodyJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        if (tenantId != null && !tenantId.isBlank()) headers.set(McpHeaders.TENANT_ID, tenantId);
        HttpEntity<String> entity = new HttpEntity<>(bodyJson == null ? "{}" : bodyJson, headers);

        ResponseEntity<String> res;
        try {
            res = postClient.exchange(strip(baseUrl) + path, HttpMethod.POST, entity, String.class);
        } catch (Exception e) {
            throw new IllegalStateException("ingest POST " + path + " failed: " + e.getMessage(), e);
        }
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("ingest POST " + path + " returned HTTP "
                    + res.getStatusCode().value() + " (note: admin-gated endpoint may require elevated authorization)");
        }
        return res.getBody();
    }

    private static String strip(String b) {
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
