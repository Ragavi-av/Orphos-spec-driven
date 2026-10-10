package io.orphos.eip.reasoning.vo;

import java.util.UUID;

/**
 * Subset of {@code Tenant} fields this service needs to route ArcadeDB reads
 * and Fuseki writes. Duplicated from graph-proxy-service's identical record —
 * see okg-ingest-service's copy of this class for the shared rationale.
 *
 * <p>{@code dataset} identifies both the ArcadeDB database and the Fuseki
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
    public enum IsolationMode { SHARED, DEDICATED }
}
