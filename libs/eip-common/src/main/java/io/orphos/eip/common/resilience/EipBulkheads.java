package io.orphos.eip.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;

/**
 * Factory for named semaphore {@link Bulkhead}s, sharing the {@code eip.resilience.bulkhead.*}
 * defaults, the way {@link EipCircuitBreakers} centralizes circuit-breaker policy.
 *
 * <p>Deliberately semaphore-style, not thread-pool-style: this is a plain Spring MVC/servlet
 * stack, so every caller already runs on its own request thread. A thread-pool bulkhead would
 * force an extra async hop (submit + {@code CompletableFuture}) to isolate work that's already
 * isolated by the servlet container's own thread-per-request model; a semaphore just caps
 * concurrent permits in-line and composes with {@code executeSupplier} the same way a
 * {@link io.github.resilience4j.circuitbreaker.CircuitBreaker} does.</p>
 *
 * <p>Only add a bulkhead where there's a genuinely separate resource to protect that isn't
 * already bounded by an existing dedicated thread pool or connection pool (a HikariCP pool, a
 * {@code ThreadPoolTaskExecutor}) — wrapping calls that already run inside one of those in a
 * second bulkhead is redundant admission control on the same load.</p>
 */
public class EipBulkheads {

    private final BulkheadRegistry registry;

    public EipBulkheads(EipBulkheadProperties props) {
        this.registry = BulkheadRegistry.of(toConfig(props));
    }

    /** The named bulkhead, created with the shared defaults on first use and reused after. */
    public Bulkhead bulkhead(String name) {
        return registry.bulkhead(name);
    }

    /** The named bulkhead, built from a caller-supplied {@link BulkheadConfig}. */
    public Bulkhead bulkhead(String name, BulkheadConfig config) {
        return registry.bulkhead(name, config);
    }

    public BulkheadRegistry registry() {
        return registry;
    }

    private static BulkheadConfig toConfig(EipBulkheadProperties props) {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(props.getMaxConcurrentCalls())
                .maxWaitDuration(props.getMaxWaitDuration())
                .build();
    }
}
