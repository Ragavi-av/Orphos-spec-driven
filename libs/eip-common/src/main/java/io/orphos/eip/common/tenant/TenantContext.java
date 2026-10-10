package io.orphos.eip.common.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable per-request tenant context. Populated by {@link TenantResolutionFilter}
 * at request entry and surfaced to application code via {@link TenantContextHolder}.
 *
 * <p>{@code tenantId} is the canonical UUID identifier used for all persisted
 * references ({@code tenantId} columns, OpenBao paths, Keycloak realm names,
 * dedicated ArcadeDB database names). {@code domain} is the mutable, customer-facing
 * label used only for display and inbound request routing — never persisted as a
 * foreign key.</p>
 *
 * <p>{@code platformAdmin} is {@code true} when the calling principal authenticates
 * via the {@code platform} realm and carries {@code ROLE_platform_admin}. Such
 * callers may operate cross-tenant and supply an explicit {@code X-Tenant-Id}
 * header to act on a specific tenant; tenant-scoped users may not.</p>
 *
 * @see TenantContextHolder
 * @see TenantResolutionFilter
 */
public record TenantContext(UUID tenantId, String domain, boolean platformAdmin) {

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
    }

    public static TenantContext of(UUID tenantId, String domain) {
        return new TenantContext(tenantId, domain, false);
    }

    public static TenantContext platformAdmin(UUID actingOnTenantId, String domain) {
        return new TenantContext(actingOnTenantId, domain, true);
    }
}
