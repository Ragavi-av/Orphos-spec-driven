package com.orphos.mobile.data.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.orphos.mobile.BuildConfig;
import com.orphos.mobile.core.config.AppConfig;
import com.orphos.mobile.core.session.SessionStore;

import java.io.IOException;

import okhttp3.Authenticator;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

/**
 * On a 401, refreshes the Keycloak access token using the stored refresh token
 * (Auth Code + PKCE public client, so no client secret) and retries once.
 * If refresh fails, the session is cleared so the app returns to sign-in.
 */
public class TokenAuthenticator implements Authenticator {

    private final SessionStore session;
    private final AppConfig config;
    private final Gson gson = new Gson();

    public TokenAuthenticator(SessionStore session, AppConfig config) {
        this.session = session;
        this.config = config;
    }

    @Nullable
    @Override
    public synchronized Request authenticate(@Nullable Route route, @NonNull Response response) {
        if (responseCount(response) >= 2) return null;               // already retried once
        // Pin the session this refresh belongs to: if the user signs out while it runs, the
        // result must neither restore the old tokens nor wipe the next user's session.
        int epoch = session.epoch();
        String refresh = session.refreshToken();
        String realm = session.realm();
        if (refresh == null || realm == null) return null;

        // Another thread may have refreshed while we waited on the lock.
        String current = authHeader(response.request());
        String latest = session.accessToken();
        if (latest != null && !latest.equals(current)) {
            return response.request().newBuilder()
                    .header("Authorization", "Bearer " + latest).build();
        }

        String base = config.keycloakUrl();
        if (!base.endsWith("/")) base += "/";
        String tokenUrl = base + "realms/" + realm + "/protocol/openid-connect/token";

        RequestBody form = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", BuildConfig.OIDC_CLIENT_ID)
                .add("refresh_token", refresh)
                .build();
        Request req = new Request.Builder().url(tokenUrl).post(form).build();

        try (Response r = new OkHttpClient().newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) { session.clearIfCurrent(epoch); return null; }
            JsonObject j = gson.fromJson(r.body().string(), JsonObject.class);
            if (j == null || !j.has("access_token")) { session.clearIfCurrent(epoch); return null; }
            String newAccess = j.get("access_token").getAsString();
            String newRefresh = j.has("refresh_token") ? j.get("refresh_token").getAsString() : refresh;
            if (!session.saveTokensIfCurrent(epoch, newAccess, newRefresh)) return null; // signed out meanwhile
            return response.request().newBuilder()
                    .header("Authorization", "Bearer " + newAccess).build();
        } catch (IOException e) {
            return null; // network hiccup — don't wipe the session
        }
    }

    private static String authHeader(Request req) {
        String h = req.header("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
    }

    private static int responseCount(Response response) {
        int count = 1;
        Response prior = response.priorResponse();
        while (prior != null) { count++; prior = prior.priorResponse(); }
        return count;
    }
}
