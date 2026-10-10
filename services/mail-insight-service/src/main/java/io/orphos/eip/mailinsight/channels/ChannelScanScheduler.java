package io.orphos.eip.mailinsight.channels;

import io.orphos.eip.common.tenant.TenantTaskExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans every active tenant's enabled communication-channel
 * connections, honouring each connection's {@link io.orphos.eip.common.schedule.ScheduleSpec}
 * (a connection without one scans on every sweep). No-ops cheaply for tenants
 * with no channel connection configured. Mirrors {@code MailScanScheduler}.
 *
 * <p>Disabled when no tenant-discovery is wired (no {@link TenantTaskExecutor})
 * or when {@code mail-insight.channels.scan.enabled=false}.</p>
 */
@Component
@ConditionalOnProperty(prefix = "mail-insight.channels.scan", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ChannelScanScheduler {

    private static final Logger log = LogManager.getLogger(ChannelScanScheduler.class);

    private final ObjectProvider<TenantTaskExecutor> tenants;
    private final ChannelScanService scanService;

    public ChannelScanScheduler(ObjectProvider<TenantTaskExecutor> tenants, ChannelScanService scanService) {
        this.tenants = tenants;
        this.scanService = scanService;
    }

    @Scheduled(fixedDelayString = "${mail-insight.channels.scan.interval-ms:900000}",
               initialDelayString = "${mail-insight.channels.scan.initial-delay-ms:90000}")
    public void scanAllTenants() {
        TenantTaskExecutor executor = tenants.getIfAvailable();
        if (executor == null) {
            log.debug("No TenantTaskExecutor available — scheduled channel scan skipped this tick.");
            return;
        }
        executor.forEachTenant(tenantId -> {
            try {
                scanService.scanDueConnections();
            } catch (Exception e) {
                log.warn("Scheduled channel scan failed for tenant {}: {}", tenantId, e.getMessage());
            }
        });
    }
}
