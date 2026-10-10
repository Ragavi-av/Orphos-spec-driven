package io.orphos.eip.common.http;

/**
 * The platform's service-to-service header names, in one place.
 *
 * <p>These values were previously hardcoded string literals in every outbound client
 * (see the former {@code HttpAccessGrantSource} and {@code EipEventPublisher} bodies).
 * They intentionally match {@code io.orphos.eip.mcp.spec.McpHeaders}, which cannot be
 * referenced from here: {@code eip-mcp-spec} depends on {@code eip-common}, so the
 * dependency only runs the other way.</p>
 */
public final class EipHttpHeaders {

    /** Tenant identifier header. Matches {@code TenantProperties.headerName} default. */
    public static final String TENANT_ID = "X-Tenant-Id";

    /** Internal service token honoured by the in-controller checks. */
    public static final String INTERNAL_TOKEN = "X-Internal-Token";

    /** Internal service token honoured by the {@code /api/internal/**} filter. */
    public static final String SERVICE_TOKEN = "X-Service-Token";

    private EipHttpHeaders() {}
}
