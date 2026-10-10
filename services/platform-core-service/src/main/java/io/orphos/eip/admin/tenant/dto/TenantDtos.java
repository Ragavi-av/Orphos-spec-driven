package io.orphos.eip.admin.tenant.dto;

import io.orphos.eip.admin.tenant.model.IsolationMode;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.model.TenantStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request + response DTOs for the tenant-management API. */
public final class TenantDtos {

    private TenantDtos() {}

    /**
     * Inbound payload for {@code POST /api/admin/tenants}. UUID is generated
     * server-side; isolation mode + database name are picked from defaults
     * when omitted (SHARED → {@code orphos}).
     */
    public record CreateTenantRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$",
                     message = "domain must be a valid lowercase host (RFC 1035)")
            @Size(max = 253)
            String domain,

            @NotBlank
            @Size(max = 200)
            String displayName,

            IsolationMode isolationMode,

            /**
             * For a DEDICATED tenant, the id of the ArcadeDB connection (from the
             * DB Connections registry) whose server hosts this tenant's database.
             * Optional — null/blank/"arcadedb-bundled" uses the bundled/platform
             * ArcadeDB. Ignored for SHARED.
             */
            @Size(max = 128)
            String arcadeConnectionId
    ) {}

    /** Inbound payload for {@code PATCH /api/admin/tenants/{id}}. Only mutable fields are accepted. */
    public record UpdateTenantRequest(
            @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$",
                     message = "domain must be a valid lowercase host (RFC 1035)")
            @Size(max = 253)
            String domain,

            @Size(max = 200)
            String displayName,

            TenantStatus status
    ) {}

    /** Response shape for admin views. Never returned to non-platform-admin users. */
    public record TenantView(
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
    ) {
        public static TenantView from(Tenant t) {
            return new TenantView(
                    t.id(), t.domain(), t.displayName(), t.status(), t.isolationMode(),
                    t.arcadeDatabase(), t.arcadeConnectionId(), t.keycloakRealm(),
                    t.createdAt(), t.updatedAt());
        }
    }

    /**
     * Minimal public projection returned by the unauthenticated bootstrap
     * endpoint ({@code GET /api/public/tenants/by-domain/{domain}}). The SPA
     * uses this to pick the right Keycloak realm before initiating login.
     * Status leaks just enough info for the SPA to render a "tenant suspended"
     * banner without exposing internals.
     */
    public record PublicTenantView(
            UUID id,
            String domain,
            String keycloakRealm,
            TenantStatus status
    ) {
        public static PublicTenantView from(Tenant t) {
            return new PublicTenantView(t.id(), t.domain(), t.keycloakRealm(), t.status());
        }
    }

    /**
     * Tenant operational metrics returned by
     * {@code GET /api/admin/tenants/{id}/metrics}. Drives the tenant-detail
     * drawer in the admin dashboard.
     *
     * <p>{@code userCount} is {@code -1} when the Keycloak realm is unreachable —
     * this is intentionally not conflated with "zero users", which a healthy
     * but unused tenant might legitimately have.</p>
     */
    public record TenantMetricsView(
            UUID tenantId,
            String domain,
            IsolationMode isolationMode,
            String arcadeDatabase,
            String keycloakRealm,
            int userCount,
            java.util.Map<String, Long> docCounts,
            long totalDocs,
            boolean databaseReachable,
            boolean keycloakRealmReachable,
            Instant lastActivityAt,
            Instant computedAt
    ) {}

    /** Bulk status-update request. {@code ids} can hold up to 100 tenant UUIDs. */
    public record BulkStatusRequest(
            @jakarta.validation.constraints.NotEmpty
            @jakarta.validation.constraints.Size(max = 100)
            java.util.List<UUID> ids,

            @jakarta.validation.constraints.NotNull
            TenantStatus status
    ) {}

    /** Per-id outcome for a bulk operation. {@code error} is null on success. */
    public record BulkResultItem(UUID id, boolean ok, String error) {}

    /** Roll-up response for a bulk operation. */
    public record BulkResultView(int total, int succeeded, int failed,
                                 java.util.List<BulkResultItem> results) {}
}
