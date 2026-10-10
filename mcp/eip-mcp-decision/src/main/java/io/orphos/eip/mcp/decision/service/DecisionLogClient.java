package io.orphos.eip.mcp.decision.service;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.mcp.decision.config.DecisionMcpProperties;
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
 * Thin HTTP client forwarding MCP tool calls to decision-log-service (TO-BE design §14: wrap, don't
 * re-implement). Propagates the tenant + internal-service token. Failures degrade to a short message
 * rather than throwing, except for the write path which surfaces non-2xx so the approval flow can
 * mark the action FAILED.
 *
 * <p>{@link #get} retries once (idempotent read); {@link #post} does not (write, must not be
 * silently retried).</p>
 */
@Component
public class DecisionLogClient {

    private static final Logger log = LogManager.getLogger(DecisionLogClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final DecisionMcpProperties props;
    private final Retry getRetry;
    private final RestTemplate http;

    public DecisionLogClient(DecisionMcpProperties props, EipRetries retries, EipRestTemplates restTemplates,
                             McpTracePropagator tracing) {
        this.props = props;
        this.getRetry = retries.retry("decision-log-read", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(500)))
                .build());
        this.http = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, READ_TIMEOUT));
    }

    /** GET a read endpoint; returns body, or a short message on failure. */
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
            log.warn("decision-log GET {} failed: {}", path, e.getMessage(), e);
            return "decision-log is currently unavailable (" + e.getMessage() + ")";
        }
    }

    private String sendGet(String path, HttpEntity<Void> entity) {
        ResponseEntity<String> res = http.exchange(base() + path, HttpMethod.GET, entity, String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("decision-log returned HTTP " + res.getStatusCode().value() + " for " + path);
        }
        return res.getBody() == null ? "" : res.getBody();
    }

    /**
     * POST a write endpoint. Returns the body on 2xx; throws on failure so the write is not silently
     * lost.
     *
     * <p>Only the send is guarded, so a transport failure is reported as such; the status check that
     * follows is left outside the catch so its {@code IllegalStateException} is not re-wrapped in a
     * second one that reads like a connection failure.</p>
     */
    public String post(String path, String tenantId, String bodyJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        if (tenantId != null && !tenantId.isBlank()) headers.set(McpHeaders.TENANT_ID, tenantId);
        HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);

        ResponseEntity<String> res;
        try {
            res = http.exchange(base() + path, HttpMethod.POST, entity, String.class);
        } catch (Exception e) {
            throw new IllegalStateException("decision-log POST " + path + " failed: " + e.getMessage(), e);
        }
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "decision-log POST " + path + " returned HTTP " + res.getStatusCode().value());
        }
        return res.getBody();
    }

    private String base() {
        String b = props.getDecisionLogBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
