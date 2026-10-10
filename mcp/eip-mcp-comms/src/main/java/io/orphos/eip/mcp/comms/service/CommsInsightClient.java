package io.orphos.eip.mcp.comms.service;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.mcp.comms.config.CommsMcpProperties;
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
 * Thin HTTP client forwarding to mail-insight-service. Reads and drafting degrade to a short
 * message; sending ({@link #postStrict}) surfaces non-2xx so the approval flow can mark the action
 * FAILED. Mirrors the mail MCP server's MailInsightClient.
 *
 * <p>Only {@link #get} retries (idempotent read); drafting ({@link #post}) and sending
 * ({@link #postStrict}) do not, since a retried draft/send request could duplicate a side effect
 * on mail-insight's side.</p>
 */
@Component
public class CommsInsightClient {

    private static final Logger log = LogManager.getLogger(CommsInsightClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GET_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POST_READ_TIMEOUT = Duration.ofSeconds(60);

    private final CommsMcpProperties props;
    private final Retry getRetry;
    private final RestTemplate getClient;
    private final RestTemplate postClient;

    public CommsInsightClient(CommsMcpProperties props, EipRetries retries, EipRestTemplates restTemplates,
                              McpTracePropagator tracing) {
        this.props = props;
        this.getRetry = retries.retry("mail-insight-read", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(500)))
                .build());
        this.getClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, GET_READ_TIMEOUT));
        this.postClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, POST_READ_TIMEOUT));
    }

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
            log.warn("mail-insight GET {} failed: {}", path, e.getMessage(), e);
            return "mail-insight is currently unavailable (" + e.getMessage() + ")";
        }
    }

    private String sendGet(String path, HttpEntity<Void> entity) {
        ResponseEntity<String> res = getClient.exchange(base() + path, HttpMethod.GET, entity, String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("mail-insight returned HTTP " + res.getStatusCode().value() + " for " + path);
        }
        return res.getBody() == null ? "" : res.getBody();
    }

    /** POST that degrades to a message (drafting — produces a proposal, sends nothing). */
    public String post(String path, String tenantId, String bodyJson) {
        try {
            return doPost(path, tenantId, bodyJson).getBody();
        } catch (Exception e) {
            // warn (an outage is invisible at the INFO root level) and with the throwable itself,
            // which is what fills errorClassName/errorMessage/stackTrace in the JSON layout.
            log.warn("mail-insight POST {} failed: {}", path, e.getMessage(), e);
            return "mail-insight is currently unavailable (" + e.getMessage() + ")";
        }
    }

    /**
     * POST that throws on failure (sending — must not be silently lost).
     *
     * <p>Only the send is guarded, so a transport failure is reported as such; the status check that
     * follows is left outside the catch so its {@code IllegalStateException} is not re-wrapped in a
     * second one that reads like a connection failure.</p>
     */
    public String postStrict(String path, String tenantId, String bodyJson) {
        ResponseEntity<String> res;
        try {
            res = doPost(path, tenantId, bodyJson);
        } catch (Exception e) {
            throw new IllegalStateException("mail-insight POST " + path + " failed: " + e.getMessage(), e);
        }
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "mail-insight POST " + path + " returned HTTP " + res.getStatusCode().value());
        }
        return res.getBody();
    }

    private ResponseEntity<String> doPost(String path, String tenantId, String bodyJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        if (tenantId != null && !tenantId.isBlank()) headers.set(McpHeaders.TENANT_ID, tenantId);
        HttpEntity<String> entity = new HttpEntity<>(bodyJson == null ? "{}" : bodyJson, headers);
        return postClient.exchange(base() + path, HttpMethod.POST, entity, String.class);
    }

    private String base() {
        String b = props.getMailInsightBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
