package io.orphos.eip.mcp.mail.service;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.mcp.mail.config.MailMcpProperties;
import io.orphos.eip.mcp.mail.constants.AppConstant;
import io.orphos.eip.mcp.mail.exception.EIPMCPException;
import io.orphos.eip.mcp.mail.exception.Error;
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
 * Thin HTTP client forwarding to mail-insight-service (TO-BE design §14).
 *
 * <p>The two failure modes here are intentionally different. Reads and drafting raise the checked
 * {@link EIPMCPException}, which {@code MailTools} turns into a short explanatory string — a listing
 * that can't be fetched must not crash the planner loop. Sending ({@link #postStrict}) throws
 * <em>unchecked</em> and is never caught, so a failed send surfaces as a tool error and the approval
 * flow marks the ProposedAction FAILED rather than recording a send that never happened.
 *
 * <p>Only {@link #get} retries (idempotent read, network I/O only — the non-2xx classification below
 * stays outside the retry); drafting and sending do not, since a retried write could duplicate a
 * side effect on mail-insight's side.</p>
 */
@Component
public class MailInsightClient {

    private static final Logger log = LogManager.getLogger(MailInsightClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GET_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POST_READ_TIMEOUT = Duration.ofSeconds(60);

    private final MailMcpProperties props;
    private final Retry getRetry;
    private final RestTemplate getClient;
    private final RestTemplate postClient;

    public MailInsightClient(MailMcpProperties props, EipRetries retries, EipRestTemplates restTemplates,
                             McpTracePropagator tracing) {
        this.props = props;
        this.getRetry = retries.retry("mail-insight-read", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(500)))
                .build());
        this.getClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, GET_READ_TIMEOUT));
        this.postClient = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, POST_READ_TIMEOUT));
    }

    /**
     * GET a read endpoint and return the body.
     *
     * @throws EIPMCPException on a non-2xx response, or when mail-insight cannot be reached.
     */
    public String get(String path, String tenantId) throws EIPMCPException {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        if (tenantId != null && !tenantId.isBlank()) headers.set(McpHeaders.TENANT_ID, tenantId);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> res;
        try {
            res = EipResilienceDecorators.call(() -> sendGet(path, entity), null, null, getRetry);
        } catch (Exception e) {
            // warn (an outage is invisible at the INFO root level) and with the throwable itself,
            // which is what fills errorClassName/errorMessage/stackTrace in the JSON layout.
            log.warn("mail-insight GET {} failed: {}", path, e.getMessage(), e);
            throw unavailable(e);
        }
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new EIPMCPException(new Error(
                    "mail-insight returned HTTP " + res.getStatusCode().value() + " for " + path,
                    AppConstant.ERROR_CODE_UPSTREAM_STATUS));
        }
        return res.getBody();
    }

    private ResponseEntity<String> sendGet(String path, HttpEntity<Void> entity) {
        return getClient.exchange(base() + path, HttpMethod.GET, entity, String.class);
    }

    /**
     * POST for drafting — produces a proposal, sends nothing, so it may degrade.
     *
     * @throws EIPMCPException when mail-insight cannot be reached or answers non-2xx.
     */
    public String post(String path, String tenantId, String bodyJson) throws EIPMCPException {
        ResponseEntity<String> res;
        try {
            res = doPost(path, tenantId, bodyJson);
        } catch (Exception e) {
            // warn (an outage is invisible at the INFO root level) and with the throwable itself,
            // which is what fills errorClassName/errorMessage/stackTrace in the JSON layout.
            log.warn("mail-insight POST {} failed: {}", path, e.getMessage(), e);
            throw unavailable(e);
        }
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new EIPMCPException(new Error(
                    "mail-insight returned HTTP " + res.getStatusCode().value() + " for " + path,
                    AppConstant.ERROR_CODE_UPSTREAM_STATUS));
        }
        return res.getBody();
    }

    /**
     * POST that throws on failure (sending — must not be silently lost). Unchecked by design: see
     * the class comment.
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

    private static EIPMCPException unavailable(Exception cause) {
        return new EIPMCPException(
                new Error("mail-insight is currently unavailable (" + cause.getMessage() + ")",
                        AppConstant.ERROR_CODE_UPSTREAM_UNAVAILABLE),
                cause);
    }

    private String base() {
        String b = props.getMailInsightBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
