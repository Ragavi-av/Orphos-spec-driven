package io.orphos.eip.admin.tenant.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical tenant aggregate stored in the {@code Tenant} ArcadeDB document type.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — immutable UUID, foreign-keyed by every tenant-scoped table.</li>
 *   <li>{@code domain} — mutable, customer-facing host (e.g. {@code acme.orphos.io}).
 *       Resolved at request entry by {@code TenantResolutionFilter} via the
 *       {@code Host} header. Must be globally unique.</li>
 *   <li>{@code displayName} — human-readable name for admin UI; not used for routing.</li>
 *   <li>{@code status} — {@link TenantStatus#ACTIVE}, SUSPENDED, or soft-deleted.</li>
 *   <li>{@code isolationMode} — {@link IsolationMode#SHARED} or DEDICATED. Drives
 *       {@code TenantDatabaseResolver}.</li>
 *   <li>{@code arcadeDatabase} — actual DB name to route to ({@code orphos} for
 *       SHARED, {@code eip_tenant_{uuid}} for DEDICATED).</li>
 *   <li>{@code arcadeConnectionId} — for a DEDICATED tenant, the id of the DB
 *       Connection (from the connections registry) whose ArcadeDB <em>server</em>
 *       hosts {@code arcadeDatabase}. {@code null}/blank == the bundled/platform
 *       ArcadeDB (today's behaviour). Ignored for SHARED tenants.</li>
 *   <li>{@code keycloakRealm} — realm name in Keycloak ({@code tenant-{uuid}}).
 *       The demo tenant is the exception: realm is {@code eip} during transition.</li>
 *   <li>{@code createdAt}/{@code updatedAt} — UTC instants.</li>
 * </ul>
 *
 * <p>This is a value object — repository round-trips return immutable instances.
 * Mutations go through {@code TenantService}, which validates business rules
 * before issuing the SQL UPDATE.</p>
 */
public record Tenant(
        UUID id,
        String domain,
        String displayName,
        TenantStatus status,
        IsolationMode isolationMode,
        String arcadeDatabase,
        String arcadeConnectionId,
        String keycloakRealm,
        Instant createdAt,
        Instant updatedAt
) {}
