package io.orphos.eip.dataingestion.component;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * Requirement 7.4 — resource-aware throttling. Before the scheduler starts
 * queued work it asks this guard whether CPU or heap usage already exceeds
 * {@code ingestion.jobs.throttle-percent}; if so, due jobs stay queued and are
 * re-evaluated on the next tick. Manually triggered runs are NOT throttled —
 * an admin clicking "Run now" has decided the load is acceptable.
 */
@Component
public class SystemLoadGuard {

    private static final Logger log = LogManager.getLogger(SystemLoadGuard.class);

    private final double thresholdPercent;

    public SystemLoadGuard(@Value("${ingestion.jobs.throttle-percent:90}") double thresholdPercent) {
        this.thresholdPercent = thresholdPercent;
    }

    /** {@code true} when scheduled work should be deferred to the next tick. */
    public boolean overloaded() {
        double cpu = cpuPercent();
        double heap = heapPercent();
        boolean over = cpu > thresholdPercent || heap > thresholdPercent;
        if (over) {
            log.info("System load above {}% (cpu={}%, heap={}%) — leaving due jobs queued for the next tick",
                    thresholdPercent, Math.round(cpu), Math.round(heap));
        }
        return over;
    }

    private static double cpuPercent() {
        // com.sun.management extension — present on every JDK we ship (Temurin);
        // getCpuLoad() returns a negative value while the first sample warms up.
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean sun) {
            return sun.getCpuLoad() * 100.0;
        }
        return -1;
    }

    private static double heapPercent() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return used * 100.0 / rt.maxMemory();
    }
}
