package io.orphos.eip.common.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI for resolving a tenant by its public-facing domain or by UUID.
 *
 * <p>{@code eip-common} ships no implementation — the canonical one lives in
 * {@code platform-core-service} (queries the {@code Tenant} ArcadeDB doc). Services
 * that don't talk to platform-core-service directly can either depend on it via an
 * HTTP client adapter or supply a cached read-through implementation. When no
 * bean is present, {@link TenantResolutionFilter} skips the
 * {@code Host}-header lookup path and only resolves tenants from the JWT
 * claim or the {@code X-Tenant-Id} header.</p>
 */
public interface TenantLookupService {

    Optional<TenantSummary> findByDomain(String domain);

    Optional<TenantSummary> findById(UUID tenantId);

    /**
     * Minimal projection sufficient for filter-time resolution. Implementations
     * SHOULD cache this aggressively — the filter runs on every request.
     */
    record TenantSummary(UUID tenantId, String domain, boolean active) {}
}
