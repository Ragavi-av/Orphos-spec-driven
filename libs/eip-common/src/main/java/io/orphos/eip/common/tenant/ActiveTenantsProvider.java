package io.orphos.eip.common.tenant;

import java.util.Set;
import java.util.UUID;

/**
 * SPI for the set of tenants a background job should iterate over (e.g. the
 * priority-engine 30-second refresh, the briefing 7am cron, the okg-reasoning
 * 15-minute cycle).
 *
 * <p>Different from {@link io.orphos.eip.common.security.AllowedIssuersProvider}:
 * that one lists trusted issuer URLs for JWT validation; this one lists the
 * actual tenant UUIDs whose data needs to be processed. platform-core-service is the
 * source of truth and exposes both via {@code /api/internal/...} endpoints.</p>
 *
 * <p>Implementations: {@code ConfigBackedActiveTenantsProvider} (static list),
 * {@code AdminPollingActiveTenantsProvider} (HTTP poll, cached). platform-core-service
 * registers a primary DB-backed implementation that queries the {@code Tenant}
 * doc type directly.</p>
 */
public interface ActiveTenantsProvider {

    /** @return UUIDs of all tenants currently in {@code ACTIVE} status. */
    Set<UUID> activeTenantIds();
}
