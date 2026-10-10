package io.orphos.eip.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The platform's role catalog — the single server-side source of truth for role
 * names, the role hierarchy, and which roles are reserved.
 *
 * <p>Roles are flat Keycloak realm roles delivered in {@code realm_access.roles}.
 * Everything that used to hard-code its own copy of the role list (the Spring
 * {@code RoleHierarchy}, Keycloak provisioning, role administration, the SCIM
 * endpoint, persona selection) reads it from here, so the layers cannot drift.</p>
 *
 * <p>Two roles are synthetic and never come from a JWT:</p>
 * <ul>
 *   <li>{@link #SERVICE_INTERNAL} — granted only by the shared internal-token filter.</li>
 *   <li>{@link #MEMBER} — derived for every user who holds at least one real role beyond
 *       {@link #GUEST} (built-in or custom). A guest-only user is a new, not-yet-approved
 *       account: the SPA shows it a holding page, and data APIs gate on {@code member}
 *       so the server agrees.</li>
 * </ul>
 */
public final class EipRoles {

    private EipRoles() {}

    public static final String PLATFORM_ADMIN  = "platform_admin";
    public static final String ADMIN           = "admin";
    public static final String INGESTION_ADMIN = "ingestion_admin";
    public static final String DATA_AI_ADMIN   = "data_ai_admin";
    public static final String SECOPS_ADMIN    = "secops_admin";
    public static final String CEO             = "ceo";
    public static final String CTO             = "cto";
    public static final String CFO             = "cfo";
    public static final String COO             = "coo";
    public static final String CHRO            = "chro";
    public static final String EXECUTIVE       = "executive";
    public static final String VIEWER          = "viewer";
    public static final String GUEST           = "guest";

    /** Synthetic: a trusted service-to-service caller (shared internal token). */
    public static final String SERVICE_INTERNAL = "service_internal";
    /** Synthetic: any user holding a real role beyond {@link #GUEST}. */
    public static final String MEMBER = "member";

    /** Spring authority prefix used throughout the platform. */
    public static final String ROLE_PREFIX = "ROLE_";

    /** Built-in roles seeded into every tenant realm, in canonical display order. */
    public static final List<String> TENANT_BUILT_IN = List.of(
            ADMIN, CEO, CTO, CFO, COO, CHRO, EXECUTIVE, VIEWER, GUEST,
            INGESTION_ADMIN, DATA_AI_ADMIN, SECOPS_ADMIN);

    /** Roles that confer tenant administration rights (full or scoped). */
    public static final Set<String> ADMIN_TIER = Set.of(ADMIN, INGESTION_ADMIN, DATA_AI_ADMIN, SECOPS_ADMIN);

    /**
     * Dashboard persona precedence, strongest first. A user's persona is the first
     * entry they hold (after hierarchy expansion is NOT applied — a persona is what
     * the user was assigned, not everything their role implies).
     */
    public static final List<String> PERSONA_PRIORITY = List.of(
            PLATFORM_ADMIN, ADMIN, CEO, CTO, CFO, COO, CHRO, EXECUTIVE, VIEWER, GUEST);

    /** Roles that exist only as platform concepts and can never be created or assigned in a tenant realm. */
    public static final Set<String> RESERVED = Set.of(PLATFORM_ADMIN, SERVICE_INTERNAL, MEMBER);

    /**
     * The role hierarchy as parent → directly implied children. Stronger roles imply
     * weaker ones: {@code platform_admin} implies {@code admin}; {@code admin} implies
     * every scoped admin role and every C-suite role; the C-suite implies
     * {@code executive}, which implies {@code viewer}, which implies {@code guest}.
     */
    private static final Map<String, List<String>> IMPLIES = buildImplies();

    private static Map<String, List<String>> buildImplies() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(PLATFORM_ADMIN, List.of(ADMIN));
        m.put(ADMIN, List.of(INGESTION_ADMIN, DATA_AI_ADMIN, SECOPS_ADMIN, CEO, CTO, CFO, COO, CHRO));
        m.put(CEO, List.of(EXECUTIVE));
        m.put(CTO, List.of(EXECUTIVE));
        m.put(CFO, List.of(EXECUTIVE));
        m.put(COO, List.of(EXECUTIVE));
        m.put(CHRO, List.of(EXECUTIVE));
        m.put(EXECUTIVE, List.of(VIEWER));
        m.put(VIEWER, List.of(GUEST));
        return Collections.unmodifiableMap(m);
    }

    /** True for one of the built-in tenant roles ({@link #TENANT_BUILT_IN}). */
    public static boolean isBuiltIn(String role) {
        return role != null && TENANT_BUILT_IN.contains(role);
    }

    /** Keycloak's own bookkeeping roles, which carry no application meaning. */
    public static boolean isKeycloakDefault(String role) {
        return role == null
                || role.startsWith("default-roles-")
                || "offline_access".equals(role)
                || "uma_authorization".equals(role);
    }

    /**
     * True if holding {@code role} makes a user a {@link #MEMBER}: any role other
     * than {@link #GUEST}, Keycloak bookkeeping roles, and the synthetic roles.
     * Custom roles count — an admin who created one intends its holders to use the app.
     */
    public static boolean confersMembership(String role) {
        return role != null && !role.isBlank()
                && !GUEST.equals(role)
                && !SERVICE_INTERNAL.equals(role)
                && !MEMBER.equals(role)
                && !isKeycloakDefault(role);
    }

    /** Spring Security hierarchy lines ({@code ROLE_a > ROLE_b}) for {@code RoleHierarchyImpl}. */
    public static String springHierarchy() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : IMPLIES.entrySet()) {
            for (String child : e.getValue()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(ROLE_PREFIX).append(e.getKey()).append(" > ").append(ROLE_PREFIX).append(child);
            }
        }
        return sb.toString();
    }

    /**
     * The given roles plus everything they imply, transitively. Unknown (custom)
     * roles pass through unchanged — they imply nothing on their own.
     */
    public static Set<String> expand(Collection<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (roles == null) return out;
        Deque<String> todo = new ArrayDeque<>(roles);
        while (!todo.isEmpty()) {
            String r = todo.pop();
            if (r == null || !out.add(r)) continue;
            todo.addAll(IMPLIES.getOrDefault(r, List.of()));
        }
        return out;
    }

    /** Role names on a principal, {@code ROLE_} prefix stripped — including derived ones. */
    public static Set<String> roleNames(Authentication auth) {
        Set<String> out = new LinkedHashSet<>();
        if (auth == null) return out;
        for (GrantedAuthority a : auth.getAuthorities()) {
            out.add(stripPrefix(a.getAuthority()));
        }
        return out;
    }

    /**
     * Roles actually assigned to the principal, excluding {@link EipDerivedAuthority}
     * ones ({@code member}, custom-role bases). Use this where a decision is about the
     * user's own role assignments (the access map, role administration), not about
     * what those roles imply.
     */
    public static Set<String> assignedRoles(Authentication auth) {
        Set<String> out = new LinkedHashSet<>();
        if (auth == null) return out;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a instanceof EipDerivedAuthority) continue;
            out.add(stripPrefix(a.getAuthority()));
        }
        return out;
    }

    /**
     * Hierarchy-aware role check for code paths that run outside {@code @PreAuthorize}
     * (e.g. a branch inside a controller). Equivalent to {@code hasRole(role)} with the
     * platform hierarchy applied.
     */
    public static boolean hasRole(Authentication auth, String role) {
        return auth != null && expand(roleNames(auth)).contains(role);
    }

    /** True if the principal holds any of {@code roles}, hierarchy applied. */
    public static boolean hasAnyRole(Authentication auth, String... roles) {
        if (auth == null) return false;
        Set<String> held = expand(roleNames(auth));
        for (String r : roles) {
            if (held.contains(r)) return true;
        }
        return false;
    }

    /** The persona for a set of assigned roles: first match in {@link #PERSONA_PRIORITY}, else guest. */
    public static String persona(Collection<String> assigned) {
        if (assigned == null) return GUEST;
        Set<String> held = new LinkedHashSet<>();
        for (String r : assigned) if (r != null) held.add(r.toLowerCase());
        for (String p : PERSONA_PRIORITY) if (held.contains(p)) return p;
        return GUEST;
    }

    static String stripPrefix(String authority) {
        if (authority == null) return "";
        return authority.startsWith(ROLE_PREFIX) ? authority.substring(ROLE_PREFIX.length()) : authority;
    }
}
