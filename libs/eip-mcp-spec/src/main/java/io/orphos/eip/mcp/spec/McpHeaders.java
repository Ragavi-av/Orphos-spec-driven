package io.orphos.eip.mcp.spec;

import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Auth/tenant header propagation between the orchestrator/agents and MCP tool servers
 * (TO-BE design §5.1). These are the platform's existing headers — reused, not reinvented:
 *
 * <ul>
 *   <li>{@link #TENANT_ID} — the tenant the call executes for (resolved by the platform's
 *       {@code TenantResolutionFilter} on the server side).</li>
 *   <li>{@link #INTERNAL_TOKEN} — service-to-service auth honoured by the existing
 *       {@code InternalServiceTokenFilter} (grants {@code ROLE_service_internal}).</li>
 * </ul>
 *
 * <p>Outbound: {@link #propagationHeaders(String)} builds the header map from the current
 * {@link TenantContextHolder}. Inbound: {@link #withTenant(String, Runnable)} re-binds the tenant
 * context inside a tool handler that received the header out-of-band (e.g. an MCP transport that
 * does not pass through the servlet filter chain).</p>
 */
public final class McpHeaders {

    /** Tenant identifier header. Matches {@code TenantProperties.headerName} default. */
    public static final String TENANT_ID = "X-Tenant-Id";

    /** Internal service-token header honoured by {@code InternalServiceTokenFilter}. */
    public static final String INTERNAL_TOKEN = "X-Internal-Token";

    /** MCP Streamable-HTTP session id (assigned by the server at {@code initialize}, echoed by clients). */
    public static final String MCP_SESSION_ID = "Mcp-Session-Id";

    private McpHeaders() {}

    /**
     * Builds the outbound header map for an MCP call: the current tenant's id (if bound) plus the
     * supplied internal service token. Insertion-ordered for deterministic, log-friendly output.
     *
     * @param internalToken the internal service token, or {@code null} to omit it
     * @return a mutable map of header name → value
     */
    public static Map<String, String> propagationHeaders(String internalToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        TenantContextHolder.current()
                .ifPresent(ctx -> headers.put(TENANT_ID, ctx.tenantId().toString()));
        if (internalToken != null && !internalToken.isBlank()) {
            headers.put(INTERNAL_TOKEN, internalToken);
        }
        return headers;
    }

    /**
     * Re-binds the tenant context from a received {@link #TENANT_ID} header value for the duration
     * of {@code work}, restoring the previous binding afterwards. Use inside a tool handler when the
     * MCP transport delivered the tenant header outside the normal servlet path.
     *
     * @param tenantIdHeader the {@code X-Tenant-Id} value received with the call
     * @param work           the handler body to run with the tenant bound
     * @throws IllegalArgumentException if {@code tenantIdHeader} is missing or not a UUID
     */
    public static void withTenant(String tenantIdHeader, Runnable work) {
        TenantContextHolder.runWith(bind(tenantIdHeader), work);
    }

    /** {@link #withTenant(String, Runnable)} variant that returns a value. */
    public static <T> T withTenant(String tenantIdHeader, Supplier<T> work) {
        return TenantContextHolder.callWith(bind(tenantIdHeader), work);
    }

    private static TenantContext bind(String tenantIdHeader) {
        if (tenantIdHeader == null || tenantIdHeader.isBlank()) {
            throw new IllegalArgumentException("Missing " + TENANT_ID + " header on MCP tool call");
        }
        try {
            return TenantContext.of(UUID.fromString(tenantIdHeader.trim()), null);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + TENANT_ID + " header (not a UUID): " + tenantIdHeader);
        }
    }
}
