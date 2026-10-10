package io.orphos.eip.proxy.vo;

import java.util.UUID;

/**
 * Subset of {@code Tenant} fields graph-proxy needs to make routing decisions:
 * which Fuseki dataset to talk to and whether to rewrite the SPARQL with a
 * tenant named-graph FROM clause.
 *
 * <p>{@code dataset} comes from admin-service's {@code arcadeDatabase} field —
 * the same string identifies both the ArcadeDB database and the Fuseki dataset
 * for a given tenant (DEDICATED: {@code eip_tenant_{uuid}}; SHARED:
 * {@code orphos}).</p>
 *
 * <p>{@code active} mirrors {@code status == ACTIVE}; non-active tenants get
 * blocked at the controller boundary.</p>
 */
public record TenantRoutingInfo(
        UUID tenantId,
        IsolationMode isolationMode,
        String dataset,
        boolean active
) {
    /** Enum mirrors admin-service's {@code IsolationMode}; values must stay in sync. */
    public enum IsolationMode { SHARED, DEDICATED }
}
