package io.orphos.eip.common.access;

import io.orphos.eip.common.security.EipRoles;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which business domains ({@link DataDomains}) each persona sees in shared, tenant-wide
 * items — tasks, notifications, meetings, the daily digest, the priority queue,
 * decisions, approvals and travel.
 *
 * <p>Default scopes, applied through the role hierarchy ({@link EipRoles#expand}) so a
 * role also gets what the roles it implies get:</p>
 * <pre>
 *   viewer     enterprise                     (general, company-wide items only)
 *   executive  + strategy
 *   ceo        + finance, commercial, risk    + critical items from every domain
 *   cfo        + finance, commercial, risk
 *   cto        + it
 *   coo        + operations, commercial
 *   chro       + hr
 *   admin      everything the C-suite sees (it implies every C-suite role)
 * </pre>
 * <p>Any other role that makes a user a member (a scoped admin, a custom role with no
 * base) sees {@code enterprise}; a custom role based on a built-in role gets that role's
 * scope. {@code guest} sees nothing.</p>
 *
 * <p><b>Per-tenant grants.</b> The tenant's access map ({@link AccessGrantSource}) can widen
 * or narrow a role's scope with grants on the resource key {@code data-domain:<domain>}
 * ({@code allow} adds the domain, {@code deny} removes it). Allow wins across the caller's
 * roles, like every other access-map decision.</p>
 *
 * <p><b>Always visible:</b> the caller's own items (their personal mailbox or calendar).
 * <b>Never filtered:</b> platform admins, internal-service callers, and background work
 * with no user in the security context.</p>
 */
public final class PersonaDomainPolicy {

    private PersonaDomainPolicy() {}

    /** Access-map resource-key prefix for per-tenant domain grants. */
    public static final String GRANT_PREFIX = "data-domain:";

    private static final Map<String, List<String>> DEFAULT_SCOPE = Map.of(
            EipRoles.VIEWER,    List.of(DataDomains.ENTERPRISE),
            EipRoles.EXECUTIVE, List.of(DataDomains.STRATEGY),
            EipRoles.CEO,       List.of(DataDomains.FINANCE, DataDomains.COMMERCIAL, DataDomains.RISK),
            EipRoles.CFO,       List.of(DataDomains.FINANCE, DataDomains.COMMERCIAL, DataDomains.RISK),
            EipRoles.CTO,       List.of(DataDomains.IT),
            EipRoles.COO,       List.of(DataDomains.OPERATIONS, DataDomains.COMMERCIAL),
            EipRoles.CHRO,      List.of(DataDomains.HR));

    /** Roles that also receive critical items from outside their scope. */
    private static final Set<String> CRITICAL_ESCALATION = Set.of(EipRoles.CEO);

    /**
     * What one caller may see.
     *
     * @param unrestricted      platform admin / internal / system — every item passes
     * @param domains           the shared-item domains visible to the caller
     * @param criticalAnyDomain critical items pass regardless of domain (CEO)
     */
    public record Scope(boolean unrestricted, Set<String> domains, boolean criticalAnyDomain) {

        public static final Scope UNRESTRICTED = new Scope(true, Set.copyOf(DataDomains.ALL), true);

        /**
         * True if an item is visible.
         *
         * @param domain   the item's domain ({@link DataDomains}); null counts as enterprise
         * @param critical the item is critical (CRITICAL priority / ALERT criticality)
         * @param own      the item belongs to the caller personally (their mailbox/calendar)
         */
        public boolean allows(String domain, boolean critical, boolean own) {
            if (unrestricted || own) return true;
            String d = domain == null || domain.isBlank() ? DataDomains.ENTERPRISE : domain;
            return domains.contains(d) || (critical && criticalAnyDomain);
        }

        /** Stable cache-key fragment: responses computed for one scope can be reused for an equal one. */
        public String cacheKey() {
            if (unrestricted) return "*";
            return String.join(",", new java.util.TreeSet<>(domains)) + (criticalAnyDomain ? "+crit" : "");
        }
    }

    /** The scope of the current security-context principal, with the tenant's grants applied. */
    public static Scope current(AccessGrantSource grants) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Scope.UNRESTRICTED;
        Map<String, Map<String, String>> map = null;
        if (grants != null) {
            try { map = grants.grantsForCurrentTenant(); } catch (Exception e) { map = null; }
        }
        return forRoles(EipRoles.assignedRoles(auth), EipRoles.roleNames(auth), map);
    }

    /** The current principal's scope with the built-in defaults only (no tenant grants). */
    public static Scope current() {
        return current(null);
    }

    /**
     * The scope for a caller.
     *
     * @param assigned roles Keycloak assigned (custom roles included)
     * @param allNames assigned plus derived roles — derived ones are custom-role bases
     * @param grants   the tenant's access map, or null when unavailable (defaults then apply)
     */
    public static Scope forRoles(Collection<String> assigned, Collection<String> allNames,
                                 Map<String, Map<String, String>> grants) {
        Set<String> names = allNames == null ? Set.of() : Set.copyOf(allNames);
        if (names.contains(EipRoles.PLATFORM_ADMIN) || names.contains(EipRoles.SERVICE_INTERNAL)) {
            return Scope.UNRESTRICTED;
        }
        Set<String> derived = new LinkedHashSet<>(names);
        if (assigned != null) derived.removeAll(assigned);
        derived.remove(EipRoles.MEMBER);

        Set<String> domains = new LinkedHashSet<>();
        boolean critical = false;
        for (String role : assigned == null ? List.<String>of() : assigned) {
            if (EipRoles.isKeycloakDefault(role)) continue;
            // A custom role takes its scope from its built-in base (the derived roles).
            Collection<String> base = EipRoles.isBuiltIn(role) ? List.of(role) : derived;
            Set<String> roleDomains = new LinkedHashSet<>(defaultsFor(base));
            if (EipRoles.confersMembership(role)) roleDomains.add(DataDomains.ENTERPRISE);
            applyGrants(roleDomains, grants == null ? null : grants.get(role));
            domains.addAll(roleDomains);
            if (EipRoles.expand(base).stream().anyMatch(CRITICAL_ESCALATION::contains)) critical = true;
        }
        return new Scope(false, Collections.unmodifiableSet(domains), critical);
    }

    /** Default domains for a set of roles, hierarchy applied. */
    public static Set<String> defaultsFor(Collection<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        for (String r : EipRoles.expand(roles)) {
            out.addAll(DEFAULT_SCOPE.getOrDefault(r, List.of()));
        }
        return out;
    }

    private static void applyGrants(Set<String> domains, Map<String, String> roleGrants) {
        if (roleGrants == null) return;
        for (Map.Entry<String, String> g : roleGrants.entrySet()) {
            String key = g.getKey();
            if (key == null || !key.startsWith(GRANT_PREFIX)) continue;
            String domain = key.substring(GRANT_PREFIX.length());
            if (!DataDomains.isKnown(domain)) continue;
            if ("allow".equals(g.getValue())) domains.add(domain);
            else if ("deny".equals(g.getValue())) domains.remove(domain);
        }
    }

    /** True for a critical item: CRITICAL priority or ALERT criticality. */
    public static boolean isCritical(String priority, String criticality) {
        return "CRITICAL".equalsIgnoreCase(priority) || "ALERT".equalsIgnoreCase(criticality);
    }

    /** The default scope table, for display (e.g. an admin "who sees what" view). */
    public static Map<String, List<String>> defaultScopeTable() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String role : EipRoles.TENANT_BUILT_IN) {
            out.put(role, List.copyOf(defaultsFor(List.of(role))));
        }
        return out;
    }
}
