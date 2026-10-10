package io.orphos.eip.admin.tenant.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.model.TenantStatus;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.common.tenant.TenantLookupService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical implementation of {@link TenantLookupService} (defined in
 * {@code eip-common}). Backed by {@link TenantRepository} with a Caffeine
 * cache in front of it because {@link io.orphos.eip.common.tenant.TenantResolutionFilter}
 * calls this on every inbound request.
 *
 * <p>Cache TTL is short (60s) so a tenant flip from ACTIVE to SUSPENDED or a
 * domain change propagates quickly. The cache is invalidated proactively when
 * {@link TenantService} mutates a tenant — see {@link #evict(UUID, String)}.</p>
 */
@Service
public class TenantLookupServiceImpl implements TenantLookupService {

    private final TenantRepository tenants;
    private final Cache<String, TenantSummary> byDomain;
    private final Cache<UUID, TenantSummary> byId;

    public TenantLookupServiceImpl(TenantRepository tenants) {
        this.tenants = tenants;
        this.byDomain = Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
        this.byId = Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
    }

    @Override
    public Optional<TenantSummary> findByDomain(String domain) {
        if (domain == null || domain.isBlank()) return Optional.empty();
        String key = domain.toLowerCase();
        TenantSummary hit = byDomain.get(key, k -> tenants.findByDomain(k)
                .map(TenantLookupServiceImpl::toSummary)
                .orElse(null));
        return Optional.ofNullable(hit);
    }

    @Override
    public Optional<TenantSummary> findById(UUID tenantId) {
        if (tenantId == null) return Optional.empty();
        TenantSummary hit = byId.get(tenantId, k -> tenants.findById(k)
                .map(TenantLookupServiceImpl::toSummary)
                .orElse(null));
        return Optional.ofNullable(hit);
    }

    /** Called by {@link TenantService} on every mutation to keep the cache honest. */
    public void evict(UUID tenantId, String domain) {
        if (tenantId != null) byId.invalidate(tenantId);
        if (domain != null) byDomain.invalidate(domain.toLowerCase());
    }

    public void evictAll() {
        byId.invalidateAll();
        byDomain.invalidateAll();
    }

    private static TenantSummary toSummary(Tenant t) {
        return new TenantSummary(t.id(), t.domain(), t.status() == TenantStatus.ACTIVE);
    }
}
