package io.orphos.eip.common.tenant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Runs a unit of work once per active tenant, with {@link TenantContextHolder}
 * bound for each iteration. Used by background services (scheduled jobs, Kafka
 * consumers) that have no per-request tenant context but need to do work
 * scoped to each tenant in turn.
 *
 * <p>Usage:
 * <pre>{@code
 * @Component
 * class PriorityRefreshJob {
 *     private final TenantTaskExecutor tenants;
 *
 *     @Scheduled(fixedDelayString = "${epq.cache.refresh-ms:30000}")
 *     void refreshAll() {
 *         tenants.forEachTenant(tenantId -> rebuildQueueForCurrentTenant());
 *     }
 * }
 * }</pre>
 *
 * <p>Failure isolation: an exception thrown by the work callback for one
 * tenant is logged and absorbed — the loop continues with the next tenant.
 * Otherwise a single corrupt tenant could halt the whole platform's
 * scheduled work.</p>
 */
public class TenantTaskExecutor {

    private static final Logger log = LogManager.getLogger(TenantTaskExecutor.class);

    private final ActiveTenantsProvider tenants;

    public TenantTaskExecutor(ActiveTenantsProvider tenants) {
        this.tenants = tenants;
    }

    /** Runs {@code work} once per active tenant. Exceptions for one tenant don't abort the others. */
    public void forEachTenant(Consumer<UUID> work) {
        Set<UUID> ids;
        try {
            ids = tenants.activeTenantIds();
        } catch (Exception e) {
            log.warn("Could not list active tenants — skipping this tick: {}", e.getMessage());
            return;
        }
        if (ids.isEmpty()) {
            log.debug("No active tenants — nothing to do this tick.");
            return;
        }
        for (UUID id : ids) {
            try {
                TenantContextHolder.runWith(
                        TenantContext.of(id, null),
                        () -> work.accept(id));
            } catch (Exception e) {
                log.warn("Tenant {} task failed: {} — continuing with other tenants", id, e.getMessage(), e);
            }
        }
    }
}
