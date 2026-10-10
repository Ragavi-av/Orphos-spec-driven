package io.orphos.eip.common.resilience;

/**
 * A non-transient, client-side upstream failure — an HTTP 4xx other than 429
 * (a bad request, a missing/invalid credential, or a forbidden operation). The
 * request itself is wrong, so it will never succeed on retry and it does not mean
 * the dependency is unhealthy.
 *
 * <p>{@link EipCircuitBreakers} configures every breaker to {@code ignoreExceptions}
 * this type, so a tenant's bad API key or a malformed request never counts toward the
 * failure rate that trips the breaker — only genuine dependency failures (5xx, timeouts,
 * connection errors, and 429 rate limiting) do. Retry policies should likewise
 * {@code ignoreExceptions(UpstreamClientException.class)} so these are not re-sent.</p>
 *
 * <p>It is a {@link RuntimeException}, so it still propagates through the existing
 * {@code catch}/fallback at the call site exactly as before — the only behavioural change
 * is that it no longer poisons the breaker or triggers a pointless retry.</p>
 */
public class UpstreamClientException extends RuntimeException {

    private final int statusCode;

    public UpstreamClientException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public UpstreamClientException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** The HTTP status that classified this as a client error (a 4xx, never 429). */
    public int statusCode() {
        return statusCode;
    }
}
