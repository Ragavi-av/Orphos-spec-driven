package com.orphos.mobile.core.role;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides which mobile surfaces a user may see, mirroring the web's access resolution
 * (frontend/shell/src/components/AdminPage/accessCatalog.js):
 * effective access = explicit role→resource grant, else explicit role→group grant, else the
 * role default; immutable / admin roles allow-all; the 'user-dashboard' group defaults allow;
 * allow-wins across the roles a user holds.
 *
 * When no live grant map is available (offline / demo / unconfigured), it falls back to
 * sensible per-persona defaults.
 */
public final class MobileAccess {

    /** Pseudo-group every mobile user surface nests under (mirrors web USER_GROUP). */
    private static final String USER_GROUP = "user-dashboard";

    // Resource keys used by the mobile surfaces (consistent with web USER_SCREENS where possible).
    public static final String NAV_HOME = "nav-home";
    public static final String ASK_ORPHOS = "ask-orphos";
    public static final String DECISION_LOG = "decision-log";
    public static final String TEAM_INTEL = "team-intel";
    public static final String CAPTURES = "widget-board";
    public static final String FINANCIALS = "financials";
    public static final String TASKS = "tasks-for-action";
    public static final String NOTIFICATIONS = "notifications";
    public static final String MEETINGS = "meetings";
    public static final String TRAVEL = "travel";
    public static final String DECISION_QUEUE = "decision-queue";

    private final AccessState state;

    public MobileAccess(AccessState state) {
        this.state = state;
    }

    /** True when the current identity may see the given resource. Home is always visible. */
    public boolean canSee(String resourceKey) {
        if (resourceKey == null || NAV_HOME.equals(resourceKey)) return true;

        if (state != null && state.grantsAvailable()) {
            return canSeeFromGrants(resourceKey);
        }
        return canSeeFromPersona(resourceKey);
    }

    // --- grant-based resolution (mirrors accessCatalog.effectiveAccess / anyRoleAllows) ---

    private boolean canSeeFromGrants(String resourceKey) {
        List<String> roles = state.roles();
        // No roles at all → fall back to persona defaults so we never blank the app.
        if (roles.isEmpty()) return canSeeFromPersona(resourceKey);
        for (String role : roles) {
            if ("allow".equals(effectiveAccess(role, resourceKey))) return true;
        }
        return false;
    }

    private String effectiveAccess(String role, String resourceKey) {
        if (state.immutableRoles().contains(role)) return "allow";
        if (state.lockedResources().contains(resourceKey)) return "deny";

        Map<String, String> forRole = state.grantMap().get(role);
        if (forRole != null) {
            String onResource = forRole.get(resourceKey);
            if ("allow".equals(onResource) || "deny".equals(onResource)) return onResource;
            String onGroup = forRole.get(USER_GROUP);
            if ("allow".equals(onGroup) || "deny".equals(onGroup)) return onGroup;
        }
        return roleDefault(role);
    }

    /** Encodes the web roleDefault for the user-dashboard group. */
    private static String roleDefault(String role) {
        String r = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if ("admin".equals(r) || "platform_admin".equals(r)) return "allow";
        // Every mobile surface nests under user-dashboard, which defaults to allow.
        return "allow";
    }

    // --- persona fallback (no live grants) ---

    private static final Set<String> ALLOW_ALL_PERSONAS = new HashSet<>();
    private static final Set<String> GUEST_ALLOWED = new HashSet<>();
    private static final Set<String> VIEWER_DENIED = new HashSet<>();
    static {
        ALLOW_ALL_PERSONAS.add("admin");
        ALLOW_ALL_PERSONAS.add("ceo");
        ALLOW_ALL_PERSONAS.add("cfo");
        ALLOW_ALL_PERSONAS.add("cto");
        ALLOW_ALL_PERSONAS.add("executive");
        // Guests only get the home surface and notifications.
        GUEST_ALLOWED.add(NAV_HOME);
        GUEST_ALLOWED.add(NOTIFICATIONS);
        // Viewers see read-only dashboard sections but not capture creation (widget board).
        VIEWER_DENIED.add(CAPTURES);
    }

    private boolean canSeeFromPersona(String resourceKey) {
        String persona = state == null ? "" : state.persona();
        persona = persona == null ? "" : persona.trim().toLowerCase(Locale.ROOT);

        // Unknown / empty persona (demo, unconfigured) → allow everything so the app is usable.
        if (persona.isEmpty() || ALLOW_ALL_PERSONAS.contains(persona)) return true;
        if ("guest".equals(persona)) return GUEST_ALLOWED.contains(resourceKey);
        if ("viewer".equals(persona)) return !VIEWER_DENIED.contains(resourceKey);
        // Any other recognised business persona defaults to full access.
        return true;
    }
}
