package io.orphos.eip.ingest.vo;

import java.util.UUID;

/**
 * Subset of {@code Tenant} fields this service needs to route ArcadeDB writes
 * to the right database. Duplicated from graph-proxy-service's identical
 * record — both are small, self-contained lookups with no other dependency,
 * so each service keeps its own copy rather than adding a cross-service coupling.
 *
 * <p>{@code dataset} comes from platform-core-service's {@code arcadeDatabase}
 * field — the same string identifies both the ArcadeDB database and the Fuseki
 * dataset for a given tenant (DEDICATED: {@code eip_tenant_{uuid}}; SHARED:
 * {@code orphos}).</p>
 */
public record TenantRoutingInfo(
        UUID tenantId,
        IsolationMode isolationMode,
        String dataset,
        String arcadeConnectionId,
        boolean active
) {
    /** Mirrors platform-core-service's {@code IsolationMode}; values must stay in sync. */
    public enum IsolationMode { SHARED, DEDICATED }
}
