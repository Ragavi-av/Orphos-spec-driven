package io.orphos.eip.docingest.source;

import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Periodically crawls every tenant with an enabled remote source connection.
 *
 * <p>Mirrors okg-ingest-service's {@code OkgIngestScheduler}: ask the repository
 * which tenants opted in (cross-tenant scan), then bind each tenant context in
 * turn and crawl, with per-tenant failure isolation. Disable via
 * {@code doc-ingest.sources.sync.enabled=false}.</p>
 */
@Component
@ConditionalOnProperty(prefix = "doc-ingest.sources.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SourceCrawlScheduler {

    private static final Logger log = LogManager.getLogger(SourceCrawlScheduler.class);

    private final SourceConnectionRepository connections;
    private final SourceCrawlService crawlService;

    public SourceCrawlScheduler(SourceConnectionRepository connections, SourceCrawlService crawlService) {
        this.connections = connections;
        this.crawlService = crawlService;
    }

    @Scheduled(fixedDelayString = "${doc-ingest.sources.sync.interval-ms:1800000}",
               initialDelayString = "${doc-ingest.sources.sync.initial-delay-ms:120000}")
    public void crawlAllTenants() {
        List<UUID> tenantIds;
        try {
            tenantIds = connections.tenantIdsWithEnabledConnection();
        } catch (Exception e) {
            log.warn("Could not list tenants with source connections — skipping this tick: {}", e.getMessage());
            return;
        }
        if (tenantIds.isEmpty()) {
            log.debug("No tenants have an enabled source connection — nothing to crawl this tick.");
            return;
        }
        for (UUID tenantId : tenantIds) {
            try {
                TenantContextHolder.runWith(TenantContext.of(tenantId, null), crawlService::crawlCurrentTenant);
            } catch (Exception e) {
                log.warn("Scheduled source crawl failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
    }
}
