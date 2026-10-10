package io.orphos.eip.common.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Circuit-breaker defaults for every service that depends on {@code eip-common}.
 *
 * <p>One policy configured in one place, the same way {@code EipHttpProperties} centralizes
 * timeouts. {@link EipCircuitBreakers} builds every registry-backed breaker from these values;
 * {@link EipCircuitBreakers#standalone(String)} uses the same field defaults for classes that
 * are plain objects and can't take the Spring-bound properties bean as a collaborator.</p>
 */
@ConfigurationProperties("eip.resilience.circuit-breaker")
public class EipCircuitBreakerProperties {

    /** Percentage of calls in the sliding window that must fail before the breaker opens. */
    private float failureRateThreshold = 50f;

    /** Number of calls tracked in the sliding window used to compute the failure rate. */
    private int slidingWindowSize = 20;

    /** Calls that must be recorded before the failure rate is evaluated — avoids tripping on a cold start. */
    private int minimumNumberOfCalls = 10;

    /** How long the breaker stays OPEN (rejecting calls immediately) before trying a call again. */
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);

    /** Trial calls let through while HALF_OPEN before deciding to close (recovered) or re-open (still down). */
    private int permittedNumberOfCallsInHalfOpenState = 5;

    public float getFailureRateThreshold() { return failureRateThreshold; }
    public void setFailureRateThreshold(float failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }

    public int getSlidingWindowSize() { return slidingWindowSize; }
    public void setSlidingWindowSize(int slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }

    public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) { this.minimumNumberOfCalls = minimumNumberOfCalls; }

    public Duration getWaitDurationInOpenState() { return waitDurationInOpenState; }
    public void setWaitDurationInOpenState(Duration waitDurationInOpenState) { this.waitDurationInOpenState = waitDurationInOpenState; }

    public int getPermittedNumberOfCallsInHalfOpenState() { return permittedNumberOfCallsInHalfOpenState; }
    public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) { this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState; }
}
