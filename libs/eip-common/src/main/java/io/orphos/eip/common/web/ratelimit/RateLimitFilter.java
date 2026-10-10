package io.orphos.eip.common.web.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.orphos.eip.common.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Servlet filter that throttles requests by client IP using Bucket4j.
 * One bucket per (rule, clientIp); both kept in-memory in a
 * {@link ConcurrentHashMap}. Suitable for MVP / single-instance deploys —
 * swap to a distributed backing store (Redis, Hazelcast) once we scale out.
 *
 * <p>On 429 we emit the standard {@code Retry-After} header (seconds) and
 * an {@link ApiError} body, so SPA error handling stays uniform.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LogManager.getLogger(RateLimitFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final List<RateLimitProperties.Rule> rules;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(List<RateLimitProperties.Rule> rules) {
        this.rules = rules;
    }

    @Override
    public int getOrder() {
        // Run before Spring Security so we don't waste auth cycles on
        // requests we're about to reject.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        RateLimitProperties.Rule rule = matchingRule(req.getRequestURI());
        if (rule == null) {
            chain.doFilter(req, res);
            return;
        }

        String key = rule.getPathPrefix() + "|" + clientIp(req);
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(rule));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            res.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            chain.doFilter(req, res);
            return;
        }

        long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
        log.debug("Rate limit hit: rule={} ip={} waitSeconds={}",
                rule.getPathPrefix(), clientIp(req), waitSeconds);

        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader("Retry-After", Long.toString(waitSeconds));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = new ApiError(
                "rate_limited",
                "too many requests; retry in " + waitSeconds + "s",
                Instant.now());
        res.getWriter().write(toJson(body));
    }

    private RateLimitProperties.Rule matchingRule(String path) {
        for (RateLimitProperties.Rule r : rules) {
            if (r.getPathPrefix() != null && MATCHER.match(r.getPathPrefix(), path)) {
                return r;
            }
        }
        return null;
    }

    private static Bucket newBucket(RateLimitProperties.Rule rule) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rule.getCapacity())
                .refillIntervally(rule.getCapacity(), rule.getRefillPeriod())
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /** Honours X-Forwarded-For first hop; falls back to socket remote addr. */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }

    private static String toJson(ApiError e) {
        return "{\"code\":\"" + e.code()
                + "\",\"message\":\"" + escape(e.message())
                + "\",\"timestamp\":\"" + e.timestamp() + "\"}";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
