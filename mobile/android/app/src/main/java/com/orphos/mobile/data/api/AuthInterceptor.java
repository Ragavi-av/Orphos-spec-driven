package com.orphos.mobile.data.api;

import androidx.annotation.NonNull;

import com.orphos.mobile.core.session.SessionStore;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Attaches the Orphos request contract to every call:
 *   Authorization: Bearer &lt;token&gt;   and   X-Tenant-Id: &lt;tenantId&gt;
 * mirroring the web SPA's axios interceptor. Token refresh-on-401 is added in Phase 1.
 */
public class AuthInterceptor implements Interceptor {

    private final SessionStore session;

    public AuthInterceptor(SessionStore session) {
        this.session = session;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request original = chain.request();
        Request.Builder b = original.newBuilder();

        String token = session.accessToken();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        String tenantId = session.tenantId();
        if (tenantId != null) {
            b.header("X-Tenant-Id", tenantId);
        }
        return chain.proceed(b.build());
    }
}
