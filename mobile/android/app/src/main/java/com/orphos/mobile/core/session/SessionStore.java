package com.orphos.mobile.core.session;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Encrypted, on-device store for the access/refresh tokens and the resolved tenant.
 * Backed by Android Keystore via EncryptedSharedPreferences. The refresh token here
 * is later gated behind a biometric unlock (see AuthManager, Phase 1).
 */
public class SessionStore {

    private static final String FILE = "orphos_session";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_TENANT_ID = "tenant_id";
    private static final String K_TENANT_SLUG = "tenant_slug";
    private static final String K_REALM = "keycloak_realm";

    private final SharedPreferences prefs;
    private final Context context;
    private int epoch = 0;

    public SessionStore(Context context) {
        this.context = context.getApplicationContext();
        SharedPreferences p;
        try {
            MasterKey key = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Fall back to plain prefs only if the Keystore is unavailable (should not
            // happen on API 26+). Tokens still never leave the device.
            p = context.getSharedPreferences(FILE + "_fallback", Context.MODE_PRIVATE);
        }
        this.prefs = p;
    }

    public String accessToken() {
        return prefs.getString(K_ACCESS, null);
    }

    public String refreshToken() {
        return prefs.getString(K_REFRESH, null);
    }

    public String realm() {
        return prefs.getString(K_REALM, null);
    }

    public void saveRealm(String realm) {
        prefs.edit().putString(K_REALM, realm).apply();
    }

    public String tenantId() {
        return prefs.getString(K_TENANT_ID, null);
    }

    public String tenantSlug() {
        return prefs.getString(K_TENANT_SLUG, null);
    }

    public void saveTokens(String accessToken, String refreshToken) {
        prefs.edit()
                .putString(K_ACCESS, accessToken)
                .putString(K_REFRESH, refreshToken)
                .apply();
    }

    public void saveTenant(String tenantId, String tenantSlug) {
        prefs.edit()
                .putString(K_TENANT_ID, tenantId)
                .putString(K_TENANT_SLUG, tenantSlug)
                .apply();
    }

    public boolean isAuthenticated() {
        return accessToken() != null;
    }

    /**
     * Session generation, bumped on every {@link #clear()}. Async work started for one
     * signed-in user (token refresh, profile load) captures it and drops its result if it
     * changed — otherwise a late response would restore the previous persona's tokens or
     * profile after sign-out.
     */
    public synchronized int epoch() {
        return epoch;
    }

    /** {@link #saveTokens} only if no sign-out happened since {@code expectedEpoch}. */
    public synchronized boolean saveTokensIfCurrent(int expectedEpoch, String accessToken,
                                                    String refreshToken) {
        if (epoch != expectedEpoch) return false;
        saveTokens(accessToken, refreshToken);
        return true;
    }

    /** {@link #clear()} only if no sign-out (and so no new sign-in) happened since {@code expectedEpoch}. */
    public synchronized void clearIfCurrent(int expectedEpoch) {
        if (epoch == expectedEpoch) clear();
    }

    /**
     * Signs out. Also drops the offline data cache (decisions, digest), so nothing cached
     * for this user or tenant can be shown to whoever signs in next.
     */
    public synchronized void clear() {
        epoch++;
        prefs.edit().clear().apply();
        context.getSharedPreferences("orphos_cache", Context.MODE_PRIVATE).edit().clear().apply();
    }
}
