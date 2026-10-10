package io.orphos.eip.mcp.priority.service;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.mcp.priority.config.PriorityMcpProperties;
import io.orphos.eip.mcp.priority.constants.AppConstant;
import io.orphos.eip.mcp.priority.exception.EIPMCPException;
import io.orphos.eip.mcp.priority.exception.Error;
import io.orphos.eip.mcp.spec.McpHeaders;
import io.orphos.eip.mcp.spec.McpTracePropagator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Thin HTTP client that forwards MCP tool calls to the upstream priority-engine-service
 * (TO-BE design §14: MCP servers <em>wrap</em> existing capabilities, they do not re-implement the
 * deterministic engine). The tenant is propagated with the platform's {@code X-Tenant-Id} header
 * plus the internal-service token, so priority-engine scopes its data exactly as for any other
 * internal caller.
 *
 * <p>Failures raise {@link EIPMCPException} rather than being returned as ordinary result text, so
 * a caller can tell an upstream outage apart from real data. Turning that back into a graceful,
 * non-throwing tool result is {@code PriorityTools}' job (design §14: a tool call must not throw
 * into the planner loop).
 *
 * <p>The network send retries once (a GET is idempotent). Only the network I/O is retried — the
 * non-2xx status check stays outside the retry so the distinct {@code ERROR_CODE_UPSTREAM_STATUS}
 * vs {@code ERROR_CODE_UPSTREAM_UNAVAILABLE} classification below is unchanged.</p>
 */
@Component
public class PriorityEngineClient {

    private static final Logger log = LogManager.getLogger(PriorityEngineClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final PriorityMcpProperties props;
    private final Retry retry;
    private final RestTemplate http;

    public PriorityEngineClient(PriorityMcpProperties props, EipRetries retries, EipRestTemplates restTemplates,
                                McpTracePropagator tracing) {
        this.props = props;
        this.retry = retries.retry("priority-engine-read", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(500)))
                .build());
        this.http = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, READ_TIMEOUT));
    }

    /**
     * GET {@code path} on priority-engine for {@code tenantId}, returning the raw response body.
     *
     * @throws EIPMCPException on a non-2xx response, or when priority-engine cannot be reached.
     */
    public String get(String path, String tenantId) throws EIPMCPException {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        if (tenantId != null && !tenantId.isBlank()) {
            headers.set(McpHeaders.TENANT_ID, tenantId);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> res;
        try {
            res = EipResilienceDecorators.call(() -> sendGet(path, entity), null, null, retry);
        } catch (Exception e) {
            // warn (an outage is invisible at the INFO root level) and with the throwable itself,
            // which is what fills errorClassName/errorMessage/stackTrace in the JSON layout.
            log.warn("priority-engine {} failed: {}", path, e.getMessage(), e);
            throw new EIPMCPException(
                    new Error("priority-engine is currently unavailable (" + e.getMessage() + ")",
                            AppConstant.ERROR_CODE_UPSTREAM_UNAVAILABLE),
                    e);
        }

        if (!res.getStatusCode().is2xxSuccessful()) {
            log.warn("priority-engine {} returned {}", path, res.getStatusCode().value());
            throw new EIPMCPException(new Error(
                    "priority-engine returned HTTP " + res.getStatusCode().value() + " for " + path,
                    AppConstant.ERROR_CODE_UPSTREAM_STATUS));
        }
        return res.getBody();
    }

    private ResponseEntity<String> sendGet(String path, HttpEntity<Void> entity) {
        return http.exchange(base() + path, HttpMethod.GET, entity, String.class);
    }

    private String base() {
        String b = props.getPriorityEngineBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
