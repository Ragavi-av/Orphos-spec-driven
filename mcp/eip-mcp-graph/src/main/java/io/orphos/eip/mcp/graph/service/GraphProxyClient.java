package io.orphos.eip.mcp.graph.service;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.mcp.graph.config.GraphMcpProperties;
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
import java.util.List;

/**
 * Thin HTTP client forwarding SPARQL queries to graph-proxy-service (TO-BE design §14). The query is
 * the raw request body with {@code Content-Type: application/sparql-query}, exactly as graph-proxy
 * expects; tenant + internal token are propagated. Read-only — degrades to a short message.
 *
 * <p>One retry covers a single transient blip (a SPARQL read is idempotent, so this is safe to
 * retry — including on a non-2xx response, since a query never has side effects).</p>
 */
@Component
public class GraphProxyClient {

    private static final Logger log = LogManager.getLogger(GraphProxyClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);
    private static final MediaType SPARQL_QUERY = MediaType.valueOf("application/sparql-query");

    private final GraphMcpProperties props;
    private final Retry retry;
    private final RestTemplate http;

    public GraphProxyClient(GraphMcpProperties props, EipRetries retries, EipRestTemplates restTemplates,
                            McpTracePropagator tracing) {
        this.props = props;
        this.retry = retries.retry("graph-proxy-read", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(500)))
                .build());
        this.http = tracing.apply(restTemplates.createLenient(CONNECT_TIMEOUT, READ_TIMEOUT));
    }

    /** Runs a read SPARQL query; returns the SPARQL-results JSON body, or a short message on failure. */
    public String query(String sparql, String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(SPARQL_QUERY);
        headers.setAccept(List.of(MediaType.valueOf("application/sparql-results+json")));
        headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        if (tenantId != null && !tenantId.isBlank()) headers.set(McpHeaders.TENANT_ID, tenantId);
        HttpEntity<String> entity = new HttpEntity<>(sparql, headers);
        try {
            return EipResilienceDecorators.call(() -> sendQuery(entity), null, null, retry);
        } catch (Exception e) {
            // warn (an outage is invisible at the INFO root level) and with the throwable itself,
            // which is what fills errorClassName/errorMessage/stackTrace in the JSON layout.
            log.warn("graph-proxy SPARQL failed: {}", e.getMessage(), e);
            return "graph-proxy is currently unavailable (" + e.getMessage() + ")";
        }
    }

    private String sendQuery(HttpEntity<String> entity) {
        ResponseEntity<String> res = http.exchange(base() + "/api/sparql", HttpMethod.POST, entity, String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("graph-proxy returned HTTP " + res.getStatusCode().value()
                    + " (" + truncate(res.getBody()) + ")");
        }
        return res.getBody();
    }

    private String base() {
        String b = props.getGraphProxyBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
