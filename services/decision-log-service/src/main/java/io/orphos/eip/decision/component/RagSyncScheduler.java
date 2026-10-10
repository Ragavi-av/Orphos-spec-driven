package io.orphos.eip.decision.component;

import io.orphos.eip.common.tenant.TenantTaskExecutor;
import io.orphos.eip.decision.service.RagSyncService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the RAG integration sweep for every active tenant every 5 minutes
 * (Req 2.5 sync bound). Tenants with no new source items no-op cheaply via
 * the per-source watermarks. Disabled when tenant discovery isn't wired (no
 * {@link TenantTaskExecutor} bean) or {@code decision-log.sync.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "decision-log.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagSyncScheduler {

    private static final Logger log = LogManager.getLogger(RagSyncScheduler.class);

    private final ObjectProvider<TenantTaskExecutor> tenants;
    private final RagSyncService sync;

    public RagSyncScheduler(ObjectProvider<TenantTaskExecutor> tenants, RagSyncService sync) {
        this.tenants = tenants;
        this.sync = sync;
    }

    @Scheduled(fixedDelayString = "${decision-log.sync.interval-ms:300000}",
               initialDelayString = "${decision-log.sync.initial-delay-ms:90000}")
    public void syncAllTenants() {
        TenantTaskExecutor executor = tenants.getIfAvailable();
        if (executor == null) {
            log.debug("No TenantTaskExecutor available — RAG sync skipped this tick.");
            return;
        }
        executor.forEachTenant(tenantId -> sync.syncTenant(tenantId.toString()));
    }
}
