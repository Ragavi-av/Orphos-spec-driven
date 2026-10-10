package io.orphos.eip.common.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bulkhead defaults for every service that depends on {@code eip-common}.
 *
 * <p>Semaphore-style (see {@link EipBulkheads}): {@code maxWaitDuration} defaults to zero, i.e.
 * fail fast when the concurrency cap is already full rather than queue a caller behind it. This
 * repo is a plain Spring MVC/servlet stack — every caller already owns a request thread, so
 * queuing here would just add latency in front of a cap that exists to shed load, not smooth it.</p>
 */
@ConfigurationProperties("eip.resilience.bulkhead")
public class EipBulkheadProperties {

    /** Permits available: calls beyond this many concurrent in-flight are rejected (or wait, see below). */
    private int maxConcurrentCalls = 10;

    /** How long a caller waits for a permit before being rejected. Zero = fail fast, never queue. */
    private Duration maxWaitDuration = Duration.ZERO;

    public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
    public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }

    public Duration getMaxWaitDuration() { return maxWaitDuration; }
    public void setMaxWaitDuration(Duration maxWaitDuration) { this.maxWaitDuration = maxWaitDuration; }
}
