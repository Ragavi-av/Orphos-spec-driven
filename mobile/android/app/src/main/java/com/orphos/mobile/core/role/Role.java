package com.orphos.mobile.core.role;

import com.orphos.mobile.R;

/**
 * Persona role. Drives role-based UI: the work tab's content and heading (Metrics for the
 * CEO, Financials for the CFO, Technology for the CTO, People for the CHRO, Operations for the
 * COO, Overview for a viewer) and whether the Business section shows the Decision Log
 * or Team Intelligence.
 *
 * <p>The DATA each role sees is filtered server-side by persona (the backend's
 * PersonaDomainPolicy reads the token); this enum only shapes the screens around it.
 * Anything unrecognised is {@link #VIEWER} — the least-privileged view — never CEO.</p>
 */
public enum Role {
    CEO, CFO, CTO, COO, CHRO, VIEWER;

    public static Role of(String s) {
        if (s == null) return VIEWER;
        try { return Role.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return VIEWER; }
    }

    /**
     * Maps a canonical Keycloak persona (the backend's /profile {@code persona}) to the
     * app's Role. Admins get the CEO view (they see every domain); executive, viewer,
     * guest and anything unrecognised get the Viewer view.
     */
    public static Role fromPersona(String persona) {
        if (persona == null) return VIEWER;
        switch (persona.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "platform_admin":
            case "admin":
            case "ceo":  return CEO;
            case "cfo":  return CFO;
            case "cto":  return CTO;
            case "coo":  return COO;
            case "chro": return CHRO;
            default:     return VIEWER;
        }
    }

    /** Persona precedence, strongest first — mirrors the backend's EipRoles.PERSONA_PRIORITY. */
    public static final String[] PERSONA_PRIORITY = {
            "platform_admin", "admin", "ceo", "cto", "cfo", "coo", "chro", "executive", "viewer", "guest"};

    /**
     * Bottom-nav label for the fifth (work) tab — "Metrics" for every persona. What the tab
     * shows, and its in-screen heading ({@link #workTitleRes()}), still vary by role.
     */
    public int workTabTitleRes() {
        return R.string.nav_metrics;
    }

    /** True when this role's work tab is the finance dashboard (CFO). */
    public boolean isFinance() {
        return this == CFO;
    }

    /** True when this role's work tab is the company-wide Metrics screen (CEO). */
    public boolean showsCompanyMetrics() {
        return this == CEO;
    }

    /** True when this role's work tab is a data-driven RoleDashboard (CHRO People). */
    public boolean showsRoleDashboard() {
        return this == CHRO;
    }

    /** Bottom-nav icon for the work tab — shared by every persona, like its label. */
    public int workTabIconRes() {
        return R.drawable.ic_metrics;
    }

    /** True when the Business section's third tab is the Decision Log (vs Team Intelligence). */
    public boolean showsDecisionLog() {
        // People-focused role keeps Team Intelligence; execs get the Decision Log.
        return this != CHRO;
    }

    /** Persona-specific heading shown inside the work tab (People, Financials, ...). */
    public int workTitleRes() {
        switch (this) {
            case CHRO:   return R.string.role_tab_people;
            case COO:    return R.string.role_tab_operations;
            case CTO:    return R.string.role_tab_technology;
            case VIEWER: return R.string.role_tab_overview;
            case CEO:    return R.string.nav_metrics;
            default:     return R.string.nav_financials;
        }
    }
}
