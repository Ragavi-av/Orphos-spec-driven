package com.orphos.mobile.auth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.orphos.mobile.BuildConfig;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.core.config.AppConfig;
import com.orphos.mobile.core.session.SessionStore;
import com.orphos.mobile.data.model.TenantView;
import com.orphos.mobile.ui.auth.LoginActivity;

import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;

import java.io.IOException;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * OIDC helper. Resolves the tenant (to learn its Keycloak realm) and builds the
 * Authorization Code + PKCE request against that realm's Keycloak endpoints.
 * The bearer token comes from Keycloak — NOT from /api/auth/login (which returns no token).
 */
public class AuthManager {

    public interface TenantCallback {
        void onTenant(TenantView tenant);
        void onError(String message);
    }

    /** Resolve tenant → realm. In demo mode returns a synthetic tenant. */
    public static void resolveTenant(String slug, TenantCallback cb) {
        if (OrphosApp.get().config().isDemoMode()) {
            TenantView t = new TenantView();
            t.id = "demo-tenant";
            t.domain = slug;
            t.keycloakRealm = "tenant-demo";
            t.status = "ACTIVE";
            cb.onTenant(t);
            return;
        }
        OrphosApp.get().api().orphos().tenantBySlug(slug).enqueue(new Callback<TenantView>() {
            @Override public void onResponse(@NonNull Call<TenantView> c, @NonNull Response<TenantView> r) {
                if (r.isSuccessful() && r.body() != null && r.body().keycloakRealm != null) {
                    cb.onTenant(r.body());
                } else {
                    resolveByDomain(slug, cb); // display-name miss → try domain (e.g. "localhost")
                }
            }
            @Override public void onFailure(@NonNull Call<TenantView> c, @NonNull Throwable t) {
                cb.onError("Can't reach the server. Check the endpoint in Settings, or use demo mode.");
            }
        });
    }

    private static void resolveByDomain(String domain, TenantCallback cb) {
        OrphosApp.get().api().orphos().tenantByDomain(domain).enqueue(new Callback<TenantView>() {
            @Override public void onResponse(@NonNull Call<TenantView> c, @NonNull Response<TenantView> r) {
                if (r.isSuccessful() && r.body() != null && r.body().keycloakRealm != null) {
                    cb.onTenant(r.body());
                } else {
                    cb.onError("Tenant not found for \"" + domain + "\".");
                }
            }
            @Override public void onFailure(@NonNull Call<TenantView> c, @NonNull Throwable t) {
                cb.onError("Can't reach the server. Check the endpoint in Settings, or use demo mode.");
            }
        });
    }

    public static AuthorizationServiceConfiguration serviceConfig(String realm) {
        String base = OrphosApp.get().config().keycloakUrl();
        if (!base.endsWith("/")) base += "/";
        String oidc = base + "realms/" + realm + "/protocol/openid-connect/";
        return new AuthorizationServiceConfiguration(
                Uri.parse(oidc + "auth"),
                Uri.parse(oidc + "token"),
                null,
                Uri.parse(oidc + "logout"));
    }

    public static AuthorizationRequest authRequest(AuthorizationServiceConfiguration cfg) {
        // AppAuth applies PKCE (S256) automatically for public clients.
        AuthorizationRequest.Builder b = new AuthorizationRequest.Builder(
                cfg,
                BuildConfig.OIDC_CLIENT_ID,
                ResponseTypeValues.CODE,
                Uri.parse(BuildConfig.OIDC_REDIRECT_URI))
                .setScope("openid profile email roles");
        // After an explicit sign-out, always show Keycloak's login form. The browser keeps
        // its Keycloak session cookie, and without this Keycloak silently re-issues a code
        // for the PREVIOUS persona, so the app went straight to Home as that user.
        if (OrphosApp.get().config().signedOut()) {
            b.setPrompt(AuthorizationRequest.Prompt.LOGIN);
        }
        return b.build();
    }

    /**
     * Explicit sign-out, shared by every sign-out entry point. Ends the Keycloak SSO session,
     * wipes tokens, cached data, persona / role / name and in-memory RBAC grants, re-arms the
     * app lock, and replaces the whole task with the Login screen (no back stack to Home).
     */
    public static void signOut(Activity from) {
        OrphosApp app = OrphosApp.get();
        SessionStore session = app.session();
        AppConfig config = app.config();

        // Capture before clear(): the server-side logout needs this user's refresh token.
        if (!config.isDemoMode()) endKeycloakSession(session.realm(), session.refreshToken());

        session.clear();                 // tokens, tenant, realm, offline cache; bumps epoch
        config.clearIdentity();          // persisted role / persona / display name
        config.setDemoMode(false);
        config.setSignedOut(true);       // next sign-in forces the Keycloak login form
        app.accessState().reset();       // in-memory persona + grants
        app.lockState().lock();          // a fresh sign-in unlocks again, as before

        Intent i = new Intent(from, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        from.startActivity(i);
        from.finish();
    }

    /**
     * Best-effort RP logout: POST the refresh token to the realm's logout endpoint (public
     * client, so client_id only). Keycloak ends the user session, which invalidates the SSO
     * cookie the login browser still holds. Fire-and-forget: sign-out never waits on the
     * network, and the forced login prompt above covers an offline logout.
     */
    private static void endKeycloakSession(String realm, String refreshToken) {
        if (realm == null || refreshToken == null) return;
        String base = OrphosApp.get().config().keycloakUrl();
        if (!base.endsWith("/")) base += "/";
        try {
            Request req = new Request.Builder()
                    .url(base + "realms/" + realm + "/protocol/openid-connect/logout")
                    .post(new FormBody.Builder()
                            .add("client_id", BuildConfig.OIDC_CLIENT_ID)
                            .add("refresh_token", refreshToken)
                            .build())
                    .build();
            new OkHttpClient().newCall(req).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(@NonNull okhttp3.Call c, @NonNull IOException e) { }
                @Override public void onResponse(@NonNull okhttp3.Call c, @NonNull okhttp3.Response r) {
                    r.close();
                }
            });
        } catch (IllegalArgumentException ignored) {
            // Malformed Keycloak URL in Settings — nothing to call; local sign-out still happens.
        }
    }
}
