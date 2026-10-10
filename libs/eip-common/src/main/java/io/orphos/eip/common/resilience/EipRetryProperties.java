package io.orphos.eip.common.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Retry defaults for every service that depends on {@code eip-common}.
 *
 * <p>Unlike {@link EipCircuitBreakerProperties}, this deliberately does NOT expose a global
 * retryable-exception list: whether a given failure is safe to retry is a call-site decision
 * (a read is usually safe, a write after an ambiguous timeout may not be — see
 * {@link EipRetries#retry(String, io.github.resilience4j.retry.RetryConfig)}). These properties
 * only fix the shared shape (how many attempts, how the backoff grows); every call site builds
 * its own {@code RetryConfig} on top via {@link EipRetries#retry(String, io.github.resilience4j.retry.RetryConfig)}
 * when the default "retry on any exception" predicate isn't safe for it.</p>
 */
@ConfigurationProperties("eip.resilience.retry")
public class EipRetryProperties {

    /** Total attempts (including the first), before the retry gives up and rethrows. */
    private int maxAttempts = 3;

    /** Wait before the first retry attempt. */
    private Duration waitDuration = Duration.ofMillis(200);

    /** Multiplier applied to the wait duration after each attempt (exponential backoff). */
    private double exponentialBackoffMultiplier = 2.0;

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public Duration getWaitDuration() { return waitDuration; }
    public void setWaitDuration(Duration waitDuration) { this.waitDuration = waitDuration; }

    public double getExponentialBackoffMultiplier() { return exponentialBackoffMultiplier; }
    public void setExponentialBackoffMultiplier(double v) { this.exponentialBackoffMultiplier = v; }
}
