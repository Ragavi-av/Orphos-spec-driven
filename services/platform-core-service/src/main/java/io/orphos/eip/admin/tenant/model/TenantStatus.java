package io.orphos.eip.admin.tenant.model;

/**
 * Lifecycle states for a tenant.
 *
 * <p>Transitions: {@link #ACTIVE} ⇄ {@link #SUSPENDED} (reversible by platform admin),
 * either → {@link #DELETED} (soft-delete, irreversible from the UI). DELETED rows are
 * retained for audit + reactivation via support but are filtered out of all
 * tenant-scoped queries.</p>
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED,
    DELETED
}
