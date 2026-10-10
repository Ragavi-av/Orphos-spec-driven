package io.orphos.eip.admin.roles.registry;

import io.orphos.eip.common.security.CustomRoleBaseSource;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * platform-core's {@link CustomRoleBaseSource}: reads {@code basedOn} straight from the
 * {@code TenantRoleMeta} rows this service owns (other services fetch the same map from
 * {@code GET /api/internal/role-bases}). Cached per tenant for a minute, since the JWT
 * converter consults it on every request that carries a custom role.
 */
@Component
public class RegistryCustomRoleBaseSource implements CustomRoleBaseSource {

    private static final Logger log = LogManager.getLogger(RegistryCustomRoleBaseSource.class);
    private static final long TTL_MILLIS = 60_000L;

    private final RoleRegistry registry;

    private record Entry(Map<String, String> bases, long fetchedAt) {}
    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    public RegistryCustomRoleBaseSource(RoleRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Map<String, String> customRoleBases(UUID tenantId) {
        if (tenantId == null) return null;
        long now = System.currentTimeMillis();
        Entry cached = cache.get(tenantId);
        if (cached != null && now - cached.fetchedAt() < TTL_MILLIS) return cached.bases();
        try {
            Map<String, String> bases = read(tenantId);
            cache.put(tenantId, new Entry(bases, now));
            return bases;
        } catch (Exception e) {
            log.debug("Could not read custom role bases for tenant {}: {}", tenantId, e.getMessage());
            return cached != null ? cached.bases() : null;
        }
    }

    /** {@code customRole → basedOn} for one tenant, uncached. */
    public Map<String, String> read(UUID tenantId) {
        List<Map<String, Object>> rows = TenantContextHolder.callWith(
                new TenantContext(tenantId, null, false), registry::list);
        Map<String, String> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("roleKey");
            Object base = row.get("basedOn");
            if (key != null && base != null && !base.toString().isBlank()) {
                out.put(key.toString(), base.toString().trim());
            }
        }
        return Map.copyOf(out);
    }
}
