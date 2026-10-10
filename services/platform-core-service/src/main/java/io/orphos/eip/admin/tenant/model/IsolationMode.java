package io.orphos.eip.admin.tenant.model;

/**
 * Tenant data-isolation strategy. Decided per-tenant at creation time and
 * mutable only by data-migration (not a regular API operation).
 *
 * <ul>
 *   <li>{@link #SHARED} — tenant data lives in the shared {@code orphos}
 *       ArcadeDB, isolated row-by-row via a {@code tenantId} column +
 *       {@code WHERE tenantId = ?} clause on every query.</li>
 *   <li>{@link #DEDICATED} — tenant gets its own ArcadeDB database
 *       ({@code eip_tenant_{uuid}}), provisioned at tenant creation. Used for
 *       security-conscious domains (banking, healthcare) where row-level
 *       isolation is insufficient.</li>
 * </ul>
 *
 * <p>See ADR-001 in {@code .kiro/specs/multi-tenant-platform/}.</p>
 */
public enum IsolationMode {
    SHARED,
    DEDICATED
}
