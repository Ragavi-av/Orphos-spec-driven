package io.orphos.eip.common.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Factory for named {@link CircuitBreaker}s sharing the {@code eip.resilience.circuit-breaker.*}
 * defaults, the way {@code EipRestTemplates} centralizes outbound-HTTP timeouts.
 *
 * <p>Breakers are registry-backed and keyed by name: two calls to {@link #breaker(String)} with
 * the same name return the same instance, so its OPEN/CLOSED/HALF_OPEN state — and therefore its
 * protection — is shared by every caller that names that dependency, even across classes.
 * Wrap the outbound call itself in {@code breaker.executeSupplier(...)} (or
 * {@code executeCallable}/{@code executeRunnable} for other shapes): while CLOSED it just runs
 * the call and records the outcome; once enough recent calls fail it flips OPEN and every further
 * call fails immediately with {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}
 * instead of blocking on a dead or slow peer, until {@code waitDurationInOpenState} elapses and it
 * lets a trial call through (HALF_OPEN) to decide whether to close again.</p>
 *
 * <p>{@code CallNotPermittedException} is a {@code RuntimeException}, so it flows into whatever
 * generic {@code catch (Exception e)} the call site already had for network failures — no new
 * error-handling branch needed, the existing fallback (empty list, cached value, {@code null})
 * now also covers "breaker is open".</p>
 */
public class EipCircuitBreakers {

    private final CircuitBreakerRegistry registry;

    public EipCircuitBreakers(EipCircuitBreakerProperties props) {
        this.registry = CircuitBreakerRegistry.of(toConfig(props));
    }

    /** The named breaker, created with the shared defaults on first use and reused after. */
    public CircuitBreaker breaker(String name) {
        return registry.circuitBreaker(name);
    }

    public CircuitBreakerRegistry registry() {
        return registry;
    }

    /**
     * A breaker outside the shared registry, for classes built by hand outside Spring's
     * container (e.g. {@link io.orphos.eip.common.access.HttpAccessGrantSource}, constructed
     * directly with {@code new} in each service's own config) that can't take this factory as a
     * collaborator. Uses {@link EipCircuitBreakerProperties}'s field defaults, so the policy
     * still matches every registry-backed breaker even without Spring property binding.
     */
    public static CircuitBreaker standalone(String name) {
        return CircuitBreaker.of(name, toConfig(new EipCircuitBreakerProperties()));
    }

    private static CircuitBreakerConfig toConfig(EipCircuitBreakerProperties props) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(props.getFailureRateThreshold())
                .slidingWindowSize(props.getSlidingWindowSize())
                .minimumNumberOfCalls(props.getMinimumNumberOfCalls())
                .waitDurationInOpenState(props.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(props.getPermittedNumberOfCallsInHalfOpenState())
                // Do not let "the request failed" masquerade as "the dependency is down":
                //   - UpstreamClientException: a 4xx client error (bad key, malformed request,
                //     forbidden) — wrong request, never succeeds on retry (see that class).
                //   - a TenantDataUnavailableException flagged tripsBreaker=false: the store
                //     RESPONDED with a per-command error (e.g. ArcadeDB HTTP 500 for an
                //     idempotent/rejected statement) — it is up, so this must not open the breaker.
                // Genuine dependency failures (I/O errors, timeouts, 429/502/503/504) are NOT
                // ignored and still count toward the failure rate.
                .ignoreException(EipCircuitBreakers::isNotDependencyOutage)
                .build();
    }

    /** True for failures that mean "the request failed" rather than "the dependency is down". */
    private static boolean isNotDependencyOutage(Throwable t) {
        if (t instanceof UpstreamClientException) {
            return true;
        }
        return t instanceof io.orphos.eip.common.tenant.TenantDataUnavailableException tdue
                && !tdue.tripsBreaker();
    }
}
