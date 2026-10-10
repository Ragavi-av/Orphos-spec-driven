package io.orphos.eip.mcp.spec;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.UUID;

/**
 * Secure tenant resolution for MCP tool handlers (TO-BE design §5.1, §9). The tenant a tool executes
 * for must be bound to the <em>authenticated principal</em>, never trusted from a bare request
 * header — otherwise a tenant-A caller could set {@code X-Tenant-Id: tenant-B} and read another
 * tenant's data.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li><b>JWT-resolved tenant</b> — if the platform's {@code TenantResolutionFilter} already bound a
 *       tenant (from the validated JWT {@code tenant_id} claim, or a platform-admin's explicit
 *       header override), that wins. A user JWT can only ever act as its own tenant; the raw header
 *       is ignored on this path.</li>
 *   <li><b>Internal-service call</b> — only when the request authenticated as
 *       {@code ROLE_service_internal} (a valid {@code X-Internal-Token}, i.e. the agent runtime /
 *       orchestrator) is the {@code X-Tenant-Id} header trusted as caller-asserted. This is the
 *       service-mesh trust boundary: the shared token is the secret, the tenant is the routing.</li>
 *   <li>otherwise the call is refused — no trusted tenant.</li>
 * </ol>
 *
 * <p>Because the MCP endpoint requires authentication (Spring Security {@code anyRequest()
 * .authenticated()}), an unauthenticated caller never reaches a tool at all.</p>
 */
public final class McpTenant {

    /** Authority granted to a validated internal-service call — matches {@code InternalServiceTokenAuthenticationFilter.ROLE}. */
    public static final String ROLE_SERVICE_INTERNAL = "ROLE_service_internal";

    /**
     * Session→tenant fallback cache (TO-BE design's {@code eip-mcp-tenant-bridge}, Jul-10 §4). Populated
     * whenever a call is authenticated the primary way (JWT / internal token) and carries an
     * {@code Mcp-Session-Id}; used ONLY as a fallback for MCP clients that authenticate at
     * {@code initialize} but do not reattach auth on every {@code tools/call}. Per-call auth stays the
     * default trust path — this never overrides it. In-memory + short TTL, so a lost entry just forces
     * the client to re-authenticate.
     */
    private static final Cache<String, String> SESSION_TENANT = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    private McpTenant() {}

    /**
     * The tenant id this MCP tool call may act on, as a string for upstream propagation.
     *
     * @throws SecurityException if no tenant can be trusted for the current call
     */
    public static String requireTenantId() {
        String sessionId = header(McpHeaders.MCP_SESSION_ID);

        // (1) Tenant bound from the validated JWT claim (or platform-admin override) by TenantResolutionFilter.
        TenantContext ctx = TenantContextHolder.currentOrNull();
        if (ctx != null) {
            String tenant = ctx.tenantId().toString();
            rememberSession(sessionId, tenant);
            return tenant;
        }
        // (2) Internal-service call: the shared token authenticated the caller; trust the asserted header.
        if (isInternalServiceCall()) {
            UUID headerTenant = parseUuid(header(McpHeaders.TENANT_ID));
            if (headerTenant != null) {
                String tenant = headerTenant.toString();
                rememberSession(sessionId, tenant);
                return tenant;
            }
            throw new SecurityException(
                    "Internal-service MCP call is missing a valid " + McpHeaders.TENANT_ID + " header.");
        }
        // (3) Fallback: a known MCP session that was authenticated on an earlier call (design §4).
        if (sessionId != null) {
            String cached = SESSION_TENANT.getIfPresent(sessionId);
            if (cached != null) {
                return cached;
            }
        }
        throw new SecurityException(
                "No trusted tenant for this MCP call: the request carries neither a JWT-resolved tenant, "
              + "internal-service authentication, nor a previously-authenticated MCP session. "
              + "Refusing to act on an unbound tenant.");
    }

    private static void rememberSession(String sessionId, String tenantId) {
        if (sessionId != null && !sessionId.isBlank()) {
            SESSION_TENANT.put(sessionId, tenantId);
        }
    }

    /** Runs {@code work} with the resolved tenant bound to {@link TenantContextHolder}. */
    public static <T> T withResolvedTenant(java.util.function.Supplier<T> work) {
        UUID tenantId = UUID.fromString(requireTenantId());
        return TenantContextHolder.callWith(TenantContext.of(tenantId, null), work);
    }

    private static boolean isInternalServiceCall() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (ROLE_SERVICE_INTERNAL.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static String header(String name) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getHeader(name);
        }
        return null;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
