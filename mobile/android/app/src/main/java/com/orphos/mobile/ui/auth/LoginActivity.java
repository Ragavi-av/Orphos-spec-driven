package com.orphos.mobile.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.orphos.mobile.MainActivity;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.auth.AuthManager;
import com.orphos.mobile.core.session.SessionStore;
import com.orphos.mobile.data.model.TenantView;
import com.orphos.mobile.ui.settings.SettingsActivity;

import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;

/**
 * Entry screen. Resolves the tenant, then runs Keycloak OIDC (Auth Code + PKCE) in the
 * system browser. A demo option seeds a local session so the app runs without a backend.
 */
public class LoginActivity extends AppCompatActivity {

    private AuthorizationService authService;
    private ActivityResultLauncher<Intent> authLauncher;
    private View progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SessionStore session = OrphosApp.get().session();
        if (session.isAuthenticated()) {
            goToMain();
            return;
        }

        // Shareable demo build: skip the login screen entirely and open the dashboards
        // pre-populated with mock data, so it "just works" on any phone with no backend.
        // Not after an explicit sign-out, though: that must land on the login screen.
        if (com.orphos.mobile.BuildConfig.DEMO_DEFAULT && !OrphosApp.get().config().signedOut()) {
            continueInDemo("");
            return;
        }

        setContentView(R.layout.activity_login);
        // Dev: allow http (localhost) token requests. Production uses https.
        net.openid.appauth.AppAuthConfiguration cfg = new net.openid.appauth.AppAuthConfiguration.Builder()
                .setConnectionBuilder(uri -> {
                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setInstanceFollowRedirects(false);
                    return conn;
                })
                .build();
        authService = new AuthorizationService(this, cfg);
        progress = findViewById(R.id.login_progress);

        TextInputEditText tenant = findViewById(R.id.login_tenant);
        tenant.setText(OrphosApp.get().config().tenantSlug());

        authLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleAuthResult(result.getData()));

        findViewById(R.id.login_signin).setOnClickListener(v ->
                startOidc(String.valueOf(tenant.getText()).trim()));

        findViewById(R.id.login_demo).setOnClickListener(v -> continueInDemo(
                String.valueOf(tenant.getText()).trim()));

        findViewById(R.id.login_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.login_language).setOnClickListener(v -> toggleLanguage());
    }

    /** Toggle app language English⇄Japanese (works before sign-in too). */
    private void toggleLanguage() {
        androidx.core.os.LocaleListCompat current =
                androidx.appcompat.app.AppCompatDelegate.getApplicationLocales();
        String lang = current.isEmpty()
                ? java.util.Locale.getDefault().getLanguage()
                : current.get(0).getLanguage();
        String next = "ja".equals(lang) ? "en" : "ja";
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(next));
    }

    private void startOidc(String slug) {
        if (slug.isEmpty()) {
            Toast.makeText(this, R.string.login_need_tenant, Toast.LENGTH_SHORT).show();
            return;
        }
        // Tapping "Sign in" is an explicit request for REAL SSO — leave demo mode first,
        // otherwise resolveTenant() would hand back the synthetic "tenant-demo" realm and
        // Keycloak returns "page not found" for a realm that doesn't exist.
        OrphosApp.get().config().setDemoMode(false);
        OrphosApp.get().config().setTenantSlug(slug);
        setBusy(true);
        AuthManager.resolveTenant(slug, new AuthManager.TenantCallback() {
            @Override public void onTenant(TenantView t) {
                OrphosApp.get().session().saveTenant(t.id, slug);
                OrphosApp.get().session().saveRealm(t.keycloakRealm);
                Intent authIntent = authService.getAuthorizationRequestIntent(
                        AuthManager.authRequest(AuthManager.serviceConfig(t.keycloakRealm)));
                authLauncher.launch(authIntent);
            }
            @Override public void onError(String message) {
                setBusy(false);
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleAuthResult(Intent data) {
        if (data == null) { setBusy(false); return; }
        AuthorizationResponse resp = AuthorizationResponse.fromIntent(data);
        AuthorizationException ex = AuthorizationException.fromIntent(data);
        if (resp == null) {
            setBusy(false);
            Toast.makeText(this, ex != null ? ex.getMessage() : getString(R.string.login_cancelled),
                    Toast.LENGTH_LONG).show();
            return;
        }
        authService.performTokenRequest(resp.createTokenExchangeRequest(), (tokenResp, tokenEx) -> {
            setBusy(false);
            if (tokenResp != null && tokenResp.accessToken != null) {
                OrphosApp.get().session().saveTokens(tokenResp.accessToken, tokenResp.refreshToken);
                OrphosApp.get().config().setDemoMode(false);
                OrphosApp.get().config().setSignedOut(false);   // fresh SSO sign-in completed
                applyRoleFromToken(tokenResp.accessToken);
                // Auto-map persona / roles / access grants from Keycloak. On a fresh login the
                // mapped persona becomes the default role; the manual Settings picker still overrides.
                OrphosApp.get().repo().loadProfile(true, (ok, fromMock) -> { });
                goToMain();
            } else {
                Toast.makeText(this, tokenEx != null ? tokenEx.getMessage()
                        : getString(R.string.login_failed), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Sets the app's role from the signed-in identity so the role-based UI (work tab
     * name/content, Decision Log vs Team Intelligence) reflects the real user. Reads the
     * Keycloak roles from the access token (realm_access + resource_access) and maps the
     * first recognised executive role (CEO/CFO/COO/CHRO). Unrecognised tokens leave the
     * current setting untouched, so this never clobbers a deliberate manual selection.
     */
    private void applyRoleFromToken(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return;
            byte[] json = android.util.Base64.decode(parts[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            org.json.JSONObject claims = new org.json.JSONObject(new String(json, java.nio.charset.StandardCharsets.UTF_8));

            // Display name for the Home greeting — standard OIDC claims, never the raw email.
            String name = firstNonBlank(claims.optString("name", null),
                    joinName(claims.optString("given_name", null), claims.optString("family_name", null)),
                    usernameNotEmail(claims.optString("preferred_username", null)),
                    emailLocalPart(claims.optString("email", null)));
            if (name != null && !name.isBlank()) OrphosApp.get().config().setUserName(name);

            java.util.Set<String> roles = new java.util.HashSet<>();
            org.json.JSONObject realm = claims.optJSONObject("realm_access");
            collectRoles(realm, roles);
            org.json.JSONObject resource = claims.optJSONObject("resource_access");
            if (resource != null) {
                for (java.util.Iterator<String> it = resource.keys(); it.hasNext(); ) {
                    collectRoles(resource.optJSONObject(it.next()), roles);
                }
            }
            // A dedicated single-value claim, if the realm maps roles that way.
            String single = claims.optString("role", null);
            if (single != null) roles.add(single);

            // Keycloak roles are lower-case ("ceo", "viewer"); match case-insensitively in
            // persona order so a viewer becomes VIEWER rather than falling back to CEO.
            java.util.Set<String> held = new java.util.HashSet<>();
            for (String r : roles) if (r != null) held.add(r.toLowerCase(java.util.Locale.ROOT));
            for (String persona : com.orphos.mobile.core.role.Role.PERSONA_PRIORITY) {
                if (held.contains(persona)) {
                    OrphosApp.get().config().setUserRole(
                            com.orphos.mobile.core.role.Role.fromPersona(persona).name());
                    return;
                }
            }
            OrphosApp.get().config().setUserRole(com.orphos.mobile.core.role.Role.VIEWER.name());
        } catch (Exception ignored) {
            // Malformed/absent roles — keep the existing role setting.
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    private static String joinName(String given, String family) {
        if (given == null || given.isBlank()) return null;
        return (family == null || family.isBlank()) ? given : given + " " + family;
    }

    private static String usernameNotEmail(String preferred) {
        return (preferred != null && !preferred.contains("@")) ? preferred : null;
    }

    private static String emailLocalPart(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : null;
    }

    private static void collectRoles(org.json.JSONObject holder, java.util.Set<String> out) {
        if (holder == null) return;
        org.json.JSONArray arr = holder.optJSONArray("roles");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String r = arr.optString(i, null);
            if (r != null) out.add(r.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    private void continueInDemo(String slug) {
        OrphosApp.get().config().setDemoMode(true);
        OrphosApp.get().config().setTenantSlug(slug.isEmpty() ? "demo" : slug);
        OrphosApp.get().session().saveTenant("demo-tenant", slug.isEmpty() ? "demo" : slug);
        OrphosApp.get().session().saveTokens("demo-access-token", null);
        goToMain();
    }

    private void setBusy(boolean busy) {
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (authService != null) authService.dispose();
    }
}
