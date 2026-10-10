package io.orphos.eip.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the caller's IP into Log4j2's ThreadContext (MDC) for the lifetime of the request, so every
 * log line emitted while handling an HTTP request carries "clientIpAddress" in the JSON output
 * (see log4j2-json-layout.json).
 *
 * <p>Ordered first so the IP is already in the MDC by the time the rate-limit, tenant-resolution
 * and access-enforcement filters run — those are exactly the filters whose rejections are worth
 * attributing to a caller. Cleared in a finally block so it can't leak onto a later, unrelated
 * request served by the same servlet-container worker thread.</p>
 *
 * <p>Lives in {@code eip-common} and is registered by {@link ClientIpLoggingAutoConfiguration};
 * it used to be copy-pasted, byte-identical, into all 30 deployable modules. Deliberately NOT
 * annotated {@code @Component}: component scanning in a service never reaches this package.</p>
 *
 * <p>{@link #MDC_KEY} is the contract with the log layout, which resolves the {@code clientIp}
 * MDC key — changing one without the other silently empties the field.</p>
 */
public class ClientIpLoggingFilter extends OncePerRequestFilter implements Ordered {

    /** MDC key read by log4j2-json-layout.json's "clientIpAddress" resolver. */
    public static final String MDC_KEY = "clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ThreadContext.put(MDC_KEY, request.getRemoteAddr());
        try {
            chain.doFilter(request, response);
        } finally {
            ThreadContext.remove(MDC_KEY);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
