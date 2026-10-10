package io.orphos.eip.common.access;

import io.orphos.eip.common.security.EipRoles;
import io.orphos.eip.common.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fine-grained, tenant-scoped authorisation based on the RBAC access map. Layers
 * on top of a service's coarse role rules (its {@code SecurityConfig}): those
 * decide whether a role may reach an endpoint at all; this decides whether the
 * tenant's access map grants that role the specific tab/section the endpoint
 * belongs to.
 *
 * <p>Reusable across services: construct it with an {@link AccessGrantSource} and
 * an ordered {@code pathPrefix → resourceKey} route table. It only inspects
 * requests whose path matches a route (most-specific first); everything else is
 * ignored. Runs after {@code TenantResolutionFilter} (order
 * {@code LOWEST_PRECEDENCE - 1000}) so the tenant is bound and the JWT is in the
 * SecurityContext.</p>
 *
 * <p><b>Whose roles.</b> The map is evaluated against the roles the user was
 * <em>assigned</em> ({@link EipRoles#assignedRoles}); derived authorities such as a
 * custom role's built-in base are skipped, because the custom role's own grants
 * (copied from that base, then narrowed) are the ones that apply.</p>
 *
 * <p><b>Fail mode.</b> A {@code platform_admin} caller (the JWT converter only lets
 * that role through on platform-realm tokens) and unmapped paths always pass
 * through. When the access map can't be resolved (policy service down, no tenant
 * in scope, fetch error) the filter <b>fails closed by default</b> — 503, because
 * an unverifiable policy must not widen access. Grant sources serve their last good
 * map through short outages, so this only bites on a prolonged one. Set
 * {@code eip.access.fail-mode=open} (env {@code EIP_ACCESS_FAIL_MODE}) to let requests
 * through instead, relying on each service's coarse role rules; resources named in
 * {@code failClosedResources} stay closed even then.</p>
 */
public class AccessEnforcementFilter extends OncePerRequestFilter implements Ordered, EnvironmentAware {

    private static final Logger log = LogManager.getLogger(AccessEnforcementFilter.class);

    /** Property selecting the behaviour when the map is unavailable: {@code closed} (default) or {@code open}. */
    public static final String FAIL_MODE_PROPERTY = "eip.access.fail-mode";

    private final AccessGrantSource grantSource;
    /** Ordered: most-specific prefix first. */
    private final LinkedHashMap<String, String> routeResource;
    /** Resource keys (or group keys) that fail CLOSED when the map is unavailable, whatever the mode. */
    private final Set<String> failClosedResources;
    private volatile boolean failOpen = false;

    public AccessEnforcementFilter(AccessGrantSource grantSource,
                                   LinkedHashMap<String, String> routeResource) {
        this(grantSource, routeResource, Set.of());
    }

    public AccessEnforcementFilter(AccessGrantSource grantSource,
                                   LinkedHashMap<String, String> routeResource,
                                   Set<String> failClosedResources) {
        this.grantSource = grantSource;
        this.routeResource = routeResource;
        this.failClosedResources = failClosedResources == null ? Set.of() : failClosedResources;
    }

    /** Reads {@link #FAIL_MODE_PROPERTY}; called by Spring because the filter is a bean. */
    @Override
    public void setEnvironment(Environment environment) {
        setFailOpen("open".equalsIgnoreCase(environment.getProperty(FAIL_MODE_PROPERTY, "closed").trim()));
    }

    /** Selects the fail mode directly (tests, or filters built outside Spring). */
    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
        log.info("Access map fail mode: {}", failOpen ? "open" : "closed");
    }

    /** Parses a comma-separated resource-key list (from config) into a set. */
    public static Set<String> parseResourceSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new java.util.HashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 500;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return resourceFor(req.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String resourceKey = resourceFor(req.getRequestURI());
        if (resourceKey == null) { chain.doFilter(req, res); return; }

        Set<String> roles = EipRoles.assignedRoles(SecurityContextHolder.getContext().getAuthentication());
        // Bypass machine principals and the platform superadmin: the access map
        // governs USER roles → tabs/sections, not trusted service-to-service calls
        // (ROLE_service_internal, authenticated by the shared internal token) or
        // platform_admin (full access by definition). Neither can arrive forged in a
        // tenant token: EipJwtAuthoritiesConverter drops both.
        if (roles.contains(EipRoles.PLATFORM_ADMIN) || roles.contains(EipRoles.SERVICE_INTERNAL)) {
            chain.doFilter(req, res);
            return;
        }

        String groupKey = AccessPolicy.groupOf(resourceKey);
        boolean failClosed = !failOpen
                || failClosedResources.contains(resourceKey)
                || (groupKey != null && failClosedResources.contains(groupKey));

        // Resolve the map. null == unavailable (no tenant, policy service down, error).
        Map<String, Map<String, String>> grants = null;
        if (TenantContextHolder.current().isPresent()) {
            try {
                grants = grantSource.grantsForCurrentTenant();
            } catch (Exception e) {
                log.warn("Access map fetch threw for {}: {}", req.getRequestURI(), e.getMessage());
                grants = null;
            }
        }

        if (grants == null) {
            // Policy can't be verified right now.
            if (failClosed) {
                log.warn("Access map unavailable for fail-closed resource '{}' (path={}) — returning 503",
                        resourceKey, req.getRequestURI());
                res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Access to '" + resourceKey
                        + "' can't be verified right now (policy service unavailable). Try again shortly.\"}");
                return;
            }
            // Fail open (opted in): coarse role rules in the service's SecurityConfig still apply.
            chain.doFilter(req, res);
            return;
        }

        if (AccessPolicy.anyRoleAllows(grants, roles, resourceKey, groupKey)) {
            chain.doFilter(req, res);
            return;
        }

        log.info("Access denied by access map: roles={} resource={} path={}",
                roles, resourceKey, req.getRequestURI());
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"Your role does not have access to '" + resourceKey
                + "'. Ask an administrator to grant it in Access Control.\"}");
    }

    private String resourceFor(String uri) {
        for (Map.Entry<String, String> e : routeResource.entrySet()) {
            String p = e.getKey();
            if (uri.equals(p) || uri.startsWith(p + "/")) return e.getValue();
        }
        return null;
    }

    /** Helper for building nested grant maps from flat rows. */
    public static Map<String, Map<String, String>> toGrantMap(Iterable<Map<String, Object>> rows) {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (rows == null) return out;
        for (Map<String, Object> row : rows) {
            Object role = row.get("role");
            Object rk = row.get("resourceKey");
            Object eff = row.get("effect");
            if (role == null || rk == null) continue;
            out.computeIfAbsent(role.toString(), k -> new HashMap<>())
               .put(rk.toString(), eff == null ? "" : eff.toString());
        }
        return out;
    }
}
