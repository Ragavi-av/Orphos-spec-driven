package io.orphos.eip.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

import java.util.function.Supplier;

/**
 * Composes Bulkhead / CircuitBreaker / Retry around a single call, in the order Resilience4j
 * recommends: bulkhead outermost, then circuit breaker, then retry innermost.
 *
 * <p>Bulkhead outermost so a call rejected for lack of concurrency permits never reaches the
 * breaker and so never counts against its failure rate. Retry innermost so every retry attempt
 * still passes through the breaker check (a retry loop that bypassed the breaker could keep
 * hammering a known-down dependency instead of failing fast once the breaker opens).</p>
 *
 * <p>Each of the three is optional (pass {@code null} to skip it) — most call sites in this repo
 * need only one or two of these, not all three; see the per-site reasoning in the resilience
 * rollout plan for which mechanisms apply where. This mirrors the existing
 * {@code breaker.executeSupplier(...)} idiom used throughout the codebase rather than
 * introducing the separate {@code Decorators.ofSupplier(...)} builder style.</p>
 */
public final class EipResilienceDecorators {

    private EipResilienceDecorators() {
    }

    public static <T> T call(Supplier<T> supplier, Bulkhead bulkhead, CircuitBreaker breaker, Retry retry) {
        Supplier<T> decorated = supplier;
        if (retry != null) {
            decorated = Retry.decorateSupplier(retry, decorated);
        }
        if (breaker != null) {
            decorated = CircuitBreaker.decorateSupplier(breaker, decorated);
        }
        if (bulkhead != null) {
            decorated = Bulkhead.decorateSupplier(bulkhead, decorated);
        }
        return decorated.get();
    }
}
