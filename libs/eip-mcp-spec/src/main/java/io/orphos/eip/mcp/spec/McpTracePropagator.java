package io.orphos.eip.mcp.spec;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

/**
 * Outbound trace-context propagation for MCP servers (TO-BE design §5.1 — the same "reuse the
 * platform's existing headers" discipline as {@link McpHeaders}, applied to tracing).
 *
 * <p>Every MCP server builds its upstream clients through {@code EipRestTemplates}, which
 * deliberately uses a bare {@code new RestTemplateBuilder()} so it stays usable outside a Spring
 * context. A bare builder never has Spring Boot's observation customizer applied, so the resulting
 * {@code RestTemplate} sends <em>no</em> {@code traceparent} header: the trace that Micrometer
 * started for the inbound {@code /mcp} call dies at this server's boundary, and the upstream
 * service (mail-insight, graph-proxy, …) logs an unrelated traceId. {@link #apply(RestTemplate)}
 * closes that gap by adding an interceptor that injects the current span's context on every
 * request, so one MCP tool call is one trace end to end.</p>
 *
 * <p>Injection uses the configured {@link Propagator}, so the wire format follows whatever
 * {@code management.tracing.propagation.*} selects (W3C by default) rather than being hardcoded
 * here. When tracing is switched off — {@code management.tracing.enabled=false}, or an MCP server
 * running without actuator on the classpath — no {@code Tracer}/{@code Propagator} bean exists and
 * this degrades to a no-op rather than failing the call: see {@link #isEnabled()}.</p>
 *
 * <p>The traceId/spanId that appear in the Log4j2 JSON output are a separate mechanism and need no
 * help from this class: Micrometer's {@code Slf4JEventListener} writes them into the SLF4J MDC on
 * every scope change, and {@code log4j-slf4j2-impl} maps that MDC onto Log4j2's {@code
 * ThreadContext}, which {@code log4j2-json-layout.json} reads through its {@code mdc} resolver.</p>
 */
public final class McpTracePropagator {

    private final Tracer tracer;
    private final Propagator propagator;

    /**
     * @param tracer     the current tracer, or {@code null} when tracing is not configured
     * @param propagator the configured propagator, or {@code null} when tracing is not configured
     */
    public McpTracePropagator(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** Whether a tracer and propagator are both available, i.e. whether injection will do anything. */
    public boolean isEnabled() {
        return tracer != null && propagator != null;
    }

    /**
     * Adds trace-context injection to {@code template} and returns it, so a client can wrap the
     * factory call inline:
     * {@code this.http = tracing.apply(restTemplates.createLenient(CONNECT, READ));}
     *
     * <p>Idempotent in effect but not in intent — call it once per {@code RestTemplate}, at
     * construction.</p>
     */
    public RestTemplate apply(RestTemplate template) {
        if (template != null && isEnabled()) {
            template.getInterceptors().add(interceptor());
        }
        return template;
    }

    /**
     * The interceptor {@link #apply(RestTemplate)} installs, for callers that assemble their own
     * interceptor chain.
     */
    public ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            Span current = isEnabled() ? tracer.currentSpan() : null;
            if (current != null) {
                propagator.inject(current.context(), request.getHeaders(),
                        (headers, key, value) -> headers.set(key, value));
            }
            return execution.execute(request, body);
        };
    }
}
