package io.orphos.eip.common.access;

import io.orphos.eip.common.security.EipRoles;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Shared RBAC decision logic — the server-side mirror of the SPA screen
 * registry ({@code frontend/shell/src/components/AdminPage/screenRegistry.js},
 * consumed by {@code accessCatalog.js}). Resolves the effective access of a role
 * on a resource, honouring the group→tab hierarchy and the role defaults so an
 * empty access map reproduces the historical role-based behaviour exactly.
 *
 * <p>NOTE: this Java map is hand-maintained to mirror that registry — when a
 * grantable tab is added there, add it to {@link #buildGroupOf()} (and to
 * {@link #LOCKED_RESOURCES} if platform-scoped), or group-level grants and
 * defaults for the new resource key will be wrong.</p>
 *
 * <p>Pure and dependency-free so every service can share one source of truth for
 * "does this role have access to this resource" (see {@link AccessEnforcementFilter}).</p>
 */
public final class AccessPolicy {

    private AccessPolicy() {}

    /** Roles whose access is fixed — always full access, never edited. */
    public static final Set<String> IMMUTABLE_ROLES = Set.of(EipRoles.PLATFORM_ADMIN);

    /**
     * Platform-admin-only resource keys (groups + tabs) that are never grantable
     * to any other role. Kept in sync with the SPA {@code LOCKED_RESOURCES}.
     */
    public static final Set<String> LOCKED_RESOURCES = Set.of(
            "security-ops", "platform",
            "tenants", "services", "db-connections", "bases", "logs",
            "plugins", "agents", "agent-approvals", "capabilities", "platform-branding");

    /**
     * Resource key → group key for the whole catalog (admin tabs + user
     * dashboard sections). Used to resolve group-level inheritance and defaults.
     * A resource that is itself a group maps to itself.
     */
    private static final Map<String, String> GROUP_OF = buildGroupOf();

    public static String groupOf(String resourceKey) {
        return GROUP_OF.getOrDefault(resourceKey, resourceKey);
    }

    /**
     * The default effect for a role + group when nothing explicit is set.
     *
     * <p>{@code guest} and Keycloak's bookkeeping roles ({@code default-roles-*},
     * {@code offline_access}, {@code uma_authorization}) default to deny everywhere:
     * every token carries them, so allowing on them would allow everyone. A guest-only
     * user is an account awaiting a role, and the SPA shows it a holding page — an admin
     * can still grant {@code guest} a section explicitly.</p>
     */
    public static String roleDefault(String role, String groupKey) {
        if (IMMUTABLE_ROLES.contains(role) || EipRoles.ADMIN.equals(role)) return "allow";
        if (EipRoles.GUEST.equals(role) || EipRoles.isKeycloakDefault(role)) return "deny";
        if ("user-dashboard".equals(groupKey)) return "allow";
        if ("ingestion_admin".equals(role)) return "documents".equals(groupKey) ? "allow" : "deny";
        if ("data_ai_admin".equals(role))   return ("data".equals(groupKey) || "ai".equals(groupKey)) ? "allow" : "deny";
        if ("secops_admin".equals(role))    return "auth-authz".equals(groupKey) ? "allow" : "deny";
        return "deny";
    }

    /**
     * Effective access for ONE role on a resource: explicit-on-resource wins,
     * then explicit-on-group, then the role default. Immutable roles always
     * allow; the {@code admin} role can never be denied the Access Control tab or
     * its group (self-lockout guard); locked resources allow only immutable roles.
     *
     * @param grants nested map {role → {resourceKey → "allow"|"deny"}}
     */
    public static String effectiveAccess(Map<String, Map<String, String>> grants,
                                         String role, String resourceKey, String groupKey) {
        if (IMMUTABLE_ROLES.contains(role)) return "allow";
        if ("admin".equals(role) && ("access-control".equals(resourceKey) || "auth-authz".equals(resourceKey))) {
            return "allow";
        }
        if (LOCKED_RESOURCES.contains(resourceKey)
                || (groupKey != null && LOCKED_RESOURCES.contains(groupKey))) {
            return "deny";
        }
        Map<String, String> forRole = grants == null ? null : grants.get(role);
        if (forRole != null) {
            String onResource = forRole.get(resourceKey);
            if ("allow".equals(onResource) || "deny".equals(onResource)) return onResource;
            if (groupKey != null) {
                String onGroup = forRole.get(groupKey);
                if ("allow".equals(onGroup) || "deny".equals(onGroup)) return onGroup;
            }
        }
        return roleDefault(role, groupKey);
    }

    /** True if any of the caller's roles resolves to allow (allow-wins). */
    public static boolean anyRoleAllows(Map<String, Map<String, String>> grants,
                                        Collection<String> roles, String resourceKey, String groupKey) {
        if (roles == null) return false;
        for (String r : roles) {
            if ("allow".equals(effectiveAccess(grants, r, resourceKey, groupKey))) return true;
        }
        return false;
    }

    private static Map<String, String> buildGroupOf() {
        Map<String, String> m = new java.util.HashMap<>();
        // Admin tabs
        put(m, "auth-authz", "auth", "users", "access-control");
        put(m, "data", "onboarding", "data-sources", "data-mapping", "ingestion-jobs", "queries", "ontology");
        put(m, "ai", "ai", "prompts", "doc-ai", "email-ai", "comms-ai");
        put(m, "documents", "doc-ingest-defaults", "doc-sources", "doc-upload", "doc-list",
                "doc-search-settings", "doc-queries");
        put(m, "comms", "email", "event-alerts", "mail-monitor", "channels");
        put(m, "appearance", "branding", "dashboard");
        put(m, "security-ops", "tenants", "services", "db-connections", "logs", "bases");
        // User dashboard sections
        put(m, "user-dashboard", "tasks-for-action", "notifications", "upcoming-meetings", "doc-search",
                "decision-log", "ai-history", "ask-orphos", "widget-board");
        return m;
    }

    private static void put(Map<String, String> m, String group, String... tabs) {
        for (String t : tabs) m.put(t, group);
    }
}
