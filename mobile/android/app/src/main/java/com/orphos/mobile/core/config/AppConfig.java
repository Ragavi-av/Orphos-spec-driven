package com.orphos.mobile.core.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.orphos.mobile.BuildConfig;

/**
 * Non-secret runtime configuration. For on-prem installs the API endpoint and tenant
 * are set per client (here, or later via a signed onboarding config / QR). Demo mode
 * lets the app run fully on mock data when no backend is reachable.
 */
public class AppConfig {

    private static final String FILE = "orphos_config";
    private static final String K_BASE_URL = "api_base_url";
    private static final String K_TENANT_SLUG = "tenant_slug";
    private static final String K_KEYCLOAK_URL = "keycloak_url";
    private static final String K_DEMO = "demo_mode";
    private static final String K_BUSY_THRESHOLD = "busy_task_threshold";
    private static final String K_ALERTS_INCLUDE_HIGH = "alerts_include_high";
    private static final String K_APP_LOCK = "app_lock_enabled";
    private static final String K_LOCK_SETUP_DONE = "app_lock_setup_done";
    private static final String K_ROLE = "user_role";
    private static final String K_PERSONA = "user_persona";
    private static final String K_NAME = "user_name";
    private static final String K_LANG = "app_language";
    private static final String K_SIGNED_OUT = "signed_out";
    private static final String K_MOBILE_ACCESS = "mobile_access_enabled";
    private static final String K_MFA = "mfa_enabled";
    private static final String K_STATUS_RED = "status_red";
    private static final String K_STATUS_BLUE = "status_blue";
    private static final String K_STATUS_GREEN = "status_green";
    private static final String K_REFRESH_MIN = "refresh_minutes";

    private final SharedPreferences prefs;

    public AppConfig(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String apiBaseUrl() {
        return prefs.getString(K_BASE_URL, BuildConfig.API_BASE_URL);
    }

    public String tenantSlug() {
        return prefs.getString(K_TENANT_SLUG, BuildConfig.DEFAULT_TENANT_SLUG);
    }

    public String keycloakUrl() {
        return prefs.getString(K_KEYCLOAK_URL, BuildConfig.KEYCLOAK_URL);
    }

    public void setKeycloakUrl(String url) {
        prefs.edit().putString(K_KEYCLOAK_URL, url).apply();
    }

    /**
     * Demo (MockData) mode. Defaults to the build type's DEMO_DEFAULT — OFF for debug and
     * release, so a fresh install shows backend data (or an honest empty state), never
     * sample values; only the dedicated demo build starts in demo mode.
     */
    public boolean isDemoMode() {
        return prefs.getBoolean(K_DEMO, BuildConfig.DEMO_DEFAULT);
    }

    /**
     * Open-task count above which the executive is shown as "Busy" on Home.
     * Defaults to the build-environment value (gradle.properties →
     * BuildConfig.BUSY_TASK_THRESHOLD); can be overridden per install.
     */
    public int busyTaskThreshold() {
        return prefs.getInt(K_BUSY_THRESHOLD, BuildConfig.BUSY_TASK_THRESHOLD);
    }

    public void setBusyTaskThreshold(int threshold) {
        prefs.edit().putInt(K_BUSY_THRESHOLD, threshold).apply();
    }

    /**
     * Whether HIGH-priority items count as mobile alerts, or only CRITICAL ones.
     * Defaults to the build-environment value (gradle.properties →
     * BuildConfig.ALERTS_INCLUDE_HIGH); overridable per install.
     */
    public boolean alertsIncludeHigh() {
        return prefs.getBoolean(K_ALERTS_INCLUDE_HIGH, BuildConfig.ALERTS_INCLUDE_HIGH);
    }

    public void setAlertsIncludeHigh(boolean include) {
        prefs.edit().putBoolean(K_ALERTS_INCLUDE_HIGH, include).apply();
    }

    // --- Mobile Access config (managed from Platform Admin → Mobile Access, /api/mobile/config) ---

    /** Whether this tenant permits mobile access. Default true. */
    public boolean mobileAccessEnabled() { return prefs.getBoolean(K_MOBILE_ACCESS, true); }
    public void setMobileAccessEnabled(boolean v) { prefs.edit().putBoolean(K_MOBILE_ACCESS, v).apply(); }

    /** Whether MFA is required (enforced by Keycloak; stored for display/awareness). Default true. */
    public boolean mfaEnabled() { return prefs.getBoolean(K_MFA, true); }
    public void setMfaEnabled(boolean v) { prefs.edit().putBoolean(K_MFA, v).apply(); }

    /** Busy status-dot thresholds (open task count): >=red → red, >=blue → blue, else green. */
    public int statusRed()   { return prefs.getInt(K_STATUS_RED, 30); }
    public int statusBlue()  { return prefs.getInt(K_STATUS_BLUE, 20); }
    public int statusGreen() { return prefs.getInt(K_STATUS_GREEN, 10); }
    public void setStatusThresholds(int red, int blue, int green) {
        prefs.edit().putInt(K_STATUS_RED, red).putInt(K_STATUS_BLUE, blue).putInt(K_STATUS_GREEN, green).apply();
    }

    /** Data refresh interval in minutes. Default 5. */
    public int refreshMinutes() { return prefs.getInt(K_REFRESH_MIN, 5); }
    public void setRefreshMinutes(int m) { prefs.edit().putInt(K_REFRESH_MIN, Math.max(1, m)).apply(); }

    // --- App lock (biometric / device credential) ---

    /** Whether the app requires a biometric or device-credential unlock to open. */
    public boolean appLockEnabled() {
        return prefs.getBoolean(K_APP_LOCK, true);
    }

    public void setAppLockEnabled(boolean enabled) {
        prefs.edit().putBoolean(K_APP_LOCK, enabled).apply();
    }

    /** First-run biometric setup prompt has been shown (and accepted or skipped). */
    public boolean lockSetupDone() {
        return prefs.getBoolean(K_LOCK_SETUP_DONE, false);
    }

    public void setLockSetupDone(boolean done) {
        prefs.edit().putBoolean(K_LOCK_SETUP_DONE, done).apply();
    }

    // --- User role (drives role-based UI) ---

    /** Executive role: CEO | CFO | COO | CHRO. Drives tab naming and widget content. */
    public String userRole() {
        return prefs.getString(K_ROLE, "CEO");
    }

    public void setUserRole(String role) {
        prefs.edit().putString(K_ROLE, role).apply();
    }

    /** Canonical persona from Keycloak (admin|ceo|cto|cfo|executive|viewer|guest). "" = unknown. */
    public String persona() {
        return prefs.getString(K_PERSONA, "");
    }

    public void setPersona(String persona) {
        prefs.edit().putString(K_PERSONA, persona == null ? "" : persona).apply();
    }

    /** The signed-in user's display name for the Home greeting. "" = unknown (show a generic greeting). */
    public String userName() {
        return prefs.getString(K_NAME, "");
    }

    public void setUserName(String name) {
        prefs.edit().putString(K_NAME, name == null ? "" : name.trim()).apply();
    }

    /**
     * Forgets who was signed in — role, persona and display name — so the next sign-in
     * starts from defaults and cannot inherit the previous persona's UI or RBAC gating.
     */
    public void clearIdentity() {
        prefs.edit().remove(K_ROLE).remove(K_PERSONA).remove(K_NAME).apply();
    }

    /**
     * True after an explicit sign-out, until the next successful sign-in. While set, the
     * next SSO sign-in forces the Keycloak login form (never silently reusing the previous
     * browser session) and the demo build does not auto-skip the login screen.
     */
    public boolean signedOut() {
        return prefs.getBoolean(K_SIGNED_OUT, false);
    }

    public void setSignedOut(boolean v) {
        prefs.edit().putBoolean(K_SIGNED_OUT, v).apply();
    }

    /**
     * Selected UI language: "en" | "ja", or "" to follow the system default. Applied via
     * AppCompatDelegate.setApplicationLocales at startup and when changed in Settings.
     */
    public String language() {
        return prefs.getString(K_LANG, "");
    }

    public void setLanguage(String lang) {
        prefs.edit().putString(K_LANG, lang == null ? "" : lang).apply();
    }

    /** Analytics consent — defaults OFF (opt-in), per GDPR/APPI/CCPA expectations. */
    public boolean analyticsConsent() {
        return prefs.getBoolean("analytics_consent", false);
    }

    public void setAnalyticsConsent(boolean v) {
        prefs.edit().putBoolean("analytics_consent", v).apply();
    }

    public boolean personalisationConsent() {
        return prefs.getBoolean("personalisation_consent", false);
    }

    public void setPersonalisationConsent(boolean v) {
        prefs.edit().putBoolean("personalisation_consent", v).apply();
    }

    public void setApiBaseUrl(String url) {
        prefs.edit().putString(K_BASE_URL, url).apply();
    }

    public void setTenantSlug(String slug) {
        prefs.edit().putString(K_TENANT_SLUG, slug).apply();
    }

    public void setDemoMode(boolean demo) {
        prefs.edit().putBoolean(K_DEMO, demo).apply();
    }
}
