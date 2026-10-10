package io.orphos.eip.mailinsight.scheduler;

import io.orphos.eip.common.tenant.TenantTaskExecutor;
import io.orphos.eip.mailinsight.pipeline.MailScanService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans every active tenant's configured mailboxes. Tenants with no
 * enabled Graph connection are skipped cheaply inside the scan service, so the
 * sweep is a no-op until a CXO configures a connection.
 *
 * <p>Disabled when no tenant-discovery is wired (no {@link TenantTaskExecutor}
 * bean) or when {@code mail-insight.scan.enabled=false}.</p>
 */
@Component
@ConditionalOnProperty(prefix = "mail-insight.scan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MailScanScheduler {

    private static final Logger log = LogManager.getLogger(MailScanScheduler.class);

    private final ObjectProvider<TenantTaskExecutor> tenants;
    private final MailScanService scanService;

    public MailScanScheduler(ObjectProvider<TenantTaskExecutor> tenants, MailScanService scanService) {
        this.tenants = tenants;
        this.scanService = scanService;
    }

    @Scheduled(fixedDelayString = "${mail-insight.scan.interval-ms:900000}",
               initialDelayString = "${mail-insight.scan.initial-delay-ms:60000}")
    public void scanAllTenants() {
        TenantTaskExecutor executor = tenants.getIfAvailable();
        if (executor == null) {
            log.debug("No TenantTaskExecutor available — scheduled scan skipped this tick.");
            return;
        }
        executor.forEachTenant(tenantId -> {
            try {
                // Honors each connection's ScheduleSpec (custom cadence); a
                // connection without one scans on every sweep as before.
                scanService.scanDueConnections();
            } catch (Exception e) {
                log.warn("Scheduled scan failed for tenant {}: {}", tenantId, e.getMessage());
            }
        });
    }
}
