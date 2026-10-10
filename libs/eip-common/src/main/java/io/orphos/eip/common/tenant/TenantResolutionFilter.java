package io.orphos.eip.common.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the current tenant on every inbound request and binds it to
 * {@link TenantContextHolder}. Resolution order:
 *
 * <ol>
 *   <li><b>JWT claim</b> — if a {@link JwtAuthenticationToken} is present and
 *       carries the configured tenant claim, use it. This is the dominant path
 *       for authenticated SPA traffic.</li>
 *   <li><b>Platform-admin override</b> — if the principal is a platform-realm
 *       admin and supplies an explicit {@code X-Tenant-Id} header, honour it.
 *       Tenant-scoped principals may NOT spoof the header.</li>
 *   <li><b>Trusted internal header</b> — if no JWT is present (Kafka-relay HTTP
 *       endpoints, scheduled-job triggers) and the request originates from a
 *       trusted source, accept the header.</li>
 *   <li><b>Host header lookup</b> — for unauthenticated requests (login page,
 *       SPA bootstrap), resolve the tenant by domain via
 *       {@link TenantLookupService}. If no implementation is registered, this
 *       step is skipped.</li>
 * </ol>
 *
 * <p>The filter runs <b>after</b> Spring Security has populated
 * {@link SecurityContextHolder} (so the JWT is available) and <b>before</b>
 * application controllers. Routes listed in
 * {@link TenantProperties#getExcludePathPrefixes()} are bypassed entirely.</p>
 *
 * <p>The filter never short-circuits the request. If no tenant resolves, the
 * holder stays {@code null} and downstream tenant-scoped code throws
 * {@link MissingTenantContextException}. This means public probes and the
 * tenant-resolution endpoint itself can run pre-resolution.</p>
 */
public class TenantResolutionFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LogManager.getLogger(TenantResolutionFilter.class);

    private final TenantProperties props;
    private final TenantLookupService lookup;

    public TenantResolutionFilter(TenantProperties props, TenantLookupService lookup) {
        this.props = props;
        this.lookup = lookup;
    }

    @Override
    public int getOrder() {
        // Run AFTER Spring Security's BearerTokenAuthenticationFilter (which
        // sits at SecurityProperties.BASIC_AUTH_ORDER) so the JWT is in the
        // SecurityContext, and BEFORE controllers / @PreAuthorize evaluation.
        return Ordered.LOWEST_PRECEDENCE - 1000;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        if (!props.isEnabled()) return true;
        String path = req.getRequestURI();
        for (String prefix : props.getExcludePathPrefixes()) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        TenantContext ctx = resolve(req);
        if (ctx != null) {
            TenantContextHolder.set(ctx);
            if (log.isTraceEnabled()) {
                log.trace("Tenant bound: tenantId={} domain={} platformAdmin={}",
                        ctx.tenantId(), ctx.domain(), ctx.platformAdmin());
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private TenantContext resolve(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // (1) JWT claim — dominant path.
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            boolean isPlatformAdmin = isPlatformAdmin(jwt, jwtAuth.getAuthorities());

            // (2) Platform admin acting on a specific tenant via explicit header.
            if (isPlatformAdmin) {
                UUID headerTenant = parseUuid(req.getHeader(props.getHeaderName()));
                if (headerTenant != null) {
                    return new TenantContext(headerTenant, lookupDomain(headerTenant), true);
                }
                // Platform admin without an explicit target: leave context null.
                // Controllers that operate cross-tenant should accept the
                // header explicitly via @RequestHeader.
                return null;
            }

            String claimValue = jwt.getClaimAsString(props.getJwtClaim());
            UUID jwtTenant = parseUuid(claimValue);
            if (jwtTenant != null) {
                return new TenantContext(jwtTenant, lookupDomain(jwtTenant), false);
            }
            log.debug("JWT for sub={} carries no usable {} claim — falling through to header/host resolution",
                    jwt.getSubject(), props.getJwtClaim());
        }

        // (2b) Trusted internal-service principal — authenticated via the shared X-Internal-Token
        // (ROLE_service_internal, granted by InternalServiceTokenAuthenticationFilter). The shared
        // secret IS the trust, so an internal caller (agent runtime / MCP server / orchestrator) may
        // act on the tenant it names in X-Tenant-Id — mirroring McpTenant on the caller side. A
        // tenant-scoped user JWT still cannot spoof the header: path (1) already returned for it.
        if (auth != null && auth.isAuthenticated() && hasInternalServiceRole(auth)) {
            UUID headerTenant = parseUuid(req.getHeader(props.getHeaderName()));
            if (headerTenant != null) {
                return new TenantContext(headerTenant, lookupDomain(headerTenant), false);
            }
        }

        // (3) Trusted internal X-Tenant-Id header (no JWT path — Kafka relays, jobs).
        if (auth == null || !auth.isAuthenticated()) {
            if (isTrustedSource(req)) {
                UUID headerTenant = parseUuid(req.getHeader(props.getHeaderName()));
                if (headerTenant != null) {
                    return new TenantContext(headerTenant, lookupDomain(headerTenant), false);
                }
            }
        }

        // (4) Host-header lookup via SPI. Skipped when no implementation is registered.
        if (lookup != null) {
            String host = stripPort(req.getHeader("Host"));
            if (host != null && !host.isBlank()) {
                Optional<TenantLookupService.TenantSummary> hit = lookup.findByDomain(host);
                if (hit.isPresent() && hit.get().active()) {
                    return new TenantContext(hit.get().tenantId(), hit.get().domain(), false);
                }
            }
        }

        return null;
    }

    /**
     * A platform admin must hold {@code ROLE_platform_admin} <b>and</b> have been issued
     * by the platform realm. Either alone is not enough: a tenant realm can define a role
     * named {@code platform_admin} (the JWT converter already drops it, this is the second
     * line), and a platform-realm token without the role is not an administrator.
     * Spring Security has validated the token signature and issuer before this runs.
     */
    private boolean isPlatformAdmin(Jwt jwt, java.util.Collection<? extends GrantedAuthority> authorities) {
        boolean hasRole = false;
        if (authorities != null) {
            for (GrantedAuthority a : authorities) {
                if ("ROLE_platform_admin".equals(a.getAuthority())) { hasRole = true; break; }
            }
        }
        if (!hasRole) return false;
        String issuer = jwt.getClaimAsString("iss");
        return issuer != null
                && issuer.replaceAll("/+$", "").endsWith("/realms/" + props.getPlatformAdminRealm());
    }

    /**
     * True when the authenticated principal carries {@code ROLE_service_internal} — i.e. it was
     * authenticated by the shared internal-service token filter. The role name is a well-known
     * platform constant (defined in eip-core-security, which this foundational module does not
     * depend on), matched by string.
     */
    private static boolean hasInternalServiceRole(Authentication auth) {
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_service_internal".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private boolean isTrustedSource(HttpServletRequest req) {
        List<String> sources = props.getTrustedHeaderSources();
        if (sources == null || sources.isEmpty()) return false;
        String remote = req.getRemoteAddr();
        if (remote == null) return false;
        for (String prefix : sources) {
            if (remote.equals(prefix) || remote.startsWith(prefix)) return true;
        }
        return false;
    }

    private String lookupDomain(UUID tenantId) {
        if (lookup == null) return null;
        return lookup.findById(tenantId).map(TenantLookupService.TenantSummary::domain).orElse(null);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripPort(String host) {
        if (host == null) return null;
        int colon = host.indexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }
}
