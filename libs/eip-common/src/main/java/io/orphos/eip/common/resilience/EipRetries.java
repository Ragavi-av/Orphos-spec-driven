package io.orphos.eip.common.resilience;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Factory for named {@link Retry}s, the retry counterpart to {@link EipCircuitBreakers}.
 *
 * <p>Retries are registry-backed and keyed by name, same as breakers. Unlike breakers, a retry's
 * "is this exception retryable" predicate is inherently call-site-specific — a read is usually
 * safe to retry after any transient failure, but a write after an ambiguous (post-request)
 * timeout may already have committed server-side, so retrying risks a duplicate. That's why this
 * class, unlike {@code EipCircuitBreakers}, exposes a two-arg {@link #retry(String, RetryConfig)}
 * that lets a caller supply its own retry predicate while still inheriting the shared
 * attempt-count/backoff shape from {@link EipRetryProperties} (via {@link #baseConfig()}).</p>
 */
public class EipRetries {

    private final RetryRegistry registry;
    private final EipRetryProperties props;

    public EipRetries(EipRetryProperties props) {
        this.props = props;
        this.registry = RetryRegistry.of(toConfig(props));
    }

    /** The named retry, created with the shared defaults (retry on any exception) on first use. */
    public Retry retry(String name) {
        return registry.retry(name);
    }

    /**
     * The named retry, built from a caller-supplied {@link RetryConfig} (typically
     * {@link #baseConfig()} narrowed with {@code .retryOnException(...)} or
     * {@code .retryExceptions(...)}) rather than the blanket "retry on any exception" default.
     * Registering under the same name twice with different configs is a caller error (the
     * registry keeps the first); use distinct names per distinct retry policy.
     */
    public Retry retry(String name, RetryConfig config) {
        return registry.retry(name, config);
    }

    /** The shared attempt-count/backoff shape, as a starting point for a narrowed {@link RetryConfig}. */
    public RetryConfig.Builder<Object> baseConfig() {
        return RetryConfig.custom()
                .maxAttempts(props.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        props.getWaitDuration(), props.getExponentialBackoffMultiplier()));
    }

    public RetryRegistry registry() {
        return registry;
    }

    /**
     * A retry outside the shared registry, for classes built by hand outside Spring's container
     * (mirrors {@link EipCircuitBreakers#standalone(String)}).
     */
    public static Retry standalone(String name, RetryConfig config) {
        return Retry.of(name, config);
    }

    private static RetryConfig toConfig(EipRetryProperties props) {
        return RetryConfig.custom()
                .maxAttempts(props.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        props.getWaitDuration(), props.getExponentialBackoffMultiplier()))
                .build();
    }
}
