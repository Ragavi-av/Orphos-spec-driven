package io.orphos.eip.ingest.component;

import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.ingest.repository.OkgConnectionRepository;
import io.orphos.eip.ingest.service.JiraIngestionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Periodically syncs every tenant with an enabled Jira connection.
 *
 * <p>Deliberately does NOT use {@code TenantTaskExecutor.forEachTenant} (which
 * iterates ALL active tenants platform-wide) — most tenants won't have Jira
 * configured, so we ask {@link OkgConnectionRepository} which tenants
 * actually opted in first, then bind each one in turn. Mirrors the pattern in
 * mail-insight-service's {@code MailScanScheduler} otherwise: fixed-delay tick,
 * per-tenant failure isolation, disabled via a property for tests.</p>
 */
@Component
@ConditionalOnProperty(prefix = "okg.jira.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OkgIngestScheduler {

    private static final Logger log = LogManager.getLogger(OkgIngestScheduler.class);

    private final OkgConnectionRepository connections;
    private final ObjectProvider<JiraIngestionService> ingestion;

    public OkgIngestScheduler(OkgConnectionRepository connections, ObjectProvider<JiraIngestionService> ingestion) {
        this.connections = connections;
        this.ingestion = ingestion;
    }

    @Scheduled(fixedDelayString = "${okg.jira.sync.interval-ms:1800000}",
               initialDelayString = "${okg.jira.sync.initial-delay-ms:60000}")
    public void syncAllTenants() {
        JiraIngestionService service = ingestion.getIfAvailable();
        if (service == null) return;

        List<UUID> tenantIds;
        try {
            tenantIds = connections.tenantIdsWithEnabledConnection(JiraIngestionService.SOURCE);
        } catch (Exception e) {
            log.warn("Could not list tenants with Jira connections — skipping this tick: {}", e.getMessage());
            return;
        }
        if (tenantIds.isEmpty()) {
            log.debug("No tenants have an enabled Jira connection — nothing to do this tick.");
            return;
        }

        for (UUID tenantId : tenantIds) {
            try {
                TenantContextHolder.runWith(TenantContext.of(tenantId, null), service::syncCurrentTenant);
            } catch (Exception e) {
                log.warn("Scheduled Jira sync failed for tenant {}: {}", tenantId, e.getMessage(), e);
                try {
                    TenantContextHolder.runWith(TenantContext.of(tenantId, null),
                            () -> connections.recordSyncResult(JiraIngestionService.SOURCE, false, e.getMessage()));
                } catch (Exception ignored) {
                    // best-effort status write; the warning above already captured the failure
                }
            }
        }
    }
}
