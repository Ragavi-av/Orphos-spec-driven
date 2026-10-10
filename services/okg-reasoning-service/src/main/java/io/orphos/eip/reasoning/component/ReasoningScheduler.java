package io.orphos.eip.reasoning.component;

import io.orphos.eip.common.tenant.TenantTaskExecutor;
import io.orphos.eip.reasoning.service.ReasoningService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs a reasoning cycle for every active tenant on a fixed interval —
 * exactly the {@code TenantTaskExecutor.forEachTenant} pattern documented in
 * that class's own Javadoc example (which literally names this scheduler).
 * Tenants with no ingested facts yet are cheaply skipped inside
 * {@link ReasoningService#reasonForCurrentTenant()}.
 */
@Component
public class ReasoningScheduler {

    private static final Logger log = LogManager.getLogger(ReasoningScheduler.class);

    private final ObjectProvider<TenantTaskExecutor> tenants;
    private final ReasoningService reasoningService;

    public ReasoningScheduler(ObjectProvider<TenantTaskExecutor> tenants, ReasoningService reasoningService) {
        this.tenants = tenants;
        this.reasoningService = reasoningService;
    }

    @Scheduled(fixedDelayString = "${reasoning.cycle-interval-ms:900000}",
               initialDelayString = "${reasoning.cycle-initial-delay-ms:90000}")
    public void reasonAllTenants() {
        TenantTaskExecutor executor = tenants.getIfAvailable();
        if (executor == null) {
            log.debug("No TenantTaskExecutor available — scheduled reasoning cycle skipped this tick.");
            return;
        }
        executor.forEachTenant(tenantId -> reasoningService.reasonForCurrentTenant());
    }
}
