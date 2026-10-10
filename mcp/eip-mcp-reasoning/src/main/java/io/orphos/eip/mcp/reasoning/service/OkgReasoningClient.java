package io.orphos.eip.mcp.reasoning.service;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.mcp.reasoning.config.ReasoningMcpProperties;
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
 * Thin HTTP client forwarding to okg-reasoning-service (TO-BE design §14). Reads degrade to a short
 * message; the write (reasoning cycle) surfaces non-2xx so the approval flow can mark it FAILED.
 *
 * <p>{@link #get} retries once, since a read has no double-apply risk; {@link #post} does not,
 * since it already documents that a non-2xx must surface so the caller can mark the action FAILED
 * rather than have a retry mask or duplicate it.</p>
 */
@Component
public class OkgReasoningClient {

    private static final Logger log = LogManager.getLogger(OkgReasoningClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GET_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POST_READ_TIMEOUT = Duration.ofSeconds(120);

    private final ReasoningMcpProperties props;
    private final Retry getRetry;
    private final RestTemplate getClient;
    private final RestTemplate postClient;

    public OkgReasoningClient(ReasoningMcpProperties props, EipRetries retries, EipRestTemplates restTemplates,
                              McpTracePropagator tracing) {
        this.props = props;
        // 1 retry only: each attempt already carries a 30s read timeout.
        this.getRetry = retries.retry("okg-reasoning-read", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(500)))
                .build());
        this.getClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, GET_READ_TIMEOUT));
        this.postClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, POST_READ_TIMEOUT));
    }

    /** GET a read endpoint; returns the body, or a short message on failure. */
    public String get(String path, String tenantId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
            if (tenantId != null && !tenantId.isBlank()) headers.set(McpHeaders.TENANT_ID, tenantId);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            return EipResilienceDecorators.call(() -> sendGet(path, entity), null, null, getRetry);
        } catch (Exception e) {
            // warn (an outage is invisible at the INFO root level) and with the throwable itself,
            // which is what fills errorClassName/errorMessage/stackTrace in the JSON layout.
            log.warn("okg-reasoning GET {} failed: {}", path, e.getMessage(), e);
            return "okg-reasoning is currently unavailable (" + e.getMessage() + ")";
        }
    }

    private String sendGet(String path, HttpEntity<Void> entity) {
        ResponseEntity<String> res = getClient.exchange(base() + path, HttpMethod.GET, entity, String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("okg-reasoning returned HTTP " + res.getStatusCode().value() + " for " + path);
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
    public String post(String path, String tenantId, String bodyJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        if (tenantId != null && !tenantId.isBlank()) headers.set(McpHeaders.TENANT_ID, tenantId);
        HttpEntity<String> entity = new HttpEntity<>(bodyJson == null ? "{}" : bodyJson, headers);

        ResponseEntity<String> res;
        try {
            res = postClient.exchange(base() + path, HttpMethod.POST, entity, String.class);
        } catch (Exception e) {
            throw new IllegalStateException("okg-reasoning POST " + path + " failed: " + e.getMessage(), e);
        }
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("okg-reasoning POST " + path + " returned HTTP "
                    + res.getStatusCode().value() + " (note: admin-gated endpoint may require elevated authorization)");
        }
        return res.getBody();
    }

    private String base() {
        String b = props.getOkgReasoningBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
