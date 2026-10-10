package com.orphos.mobile.data.api;

import com.orphos.mobile.core.config.AppConfig;
import com.orphos.mobile.core.session.SessionStore;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Builds the Retrofit client against the per-client Orphos endpoint (from AppConfig).
 * Call {@link #rebuild()} after the endpoint changes.
 */
public class ApiClient {

    private final SessionStore session;
    private final AppConfig config;
    private OrphosApi api;
    private String baseUrl;

    public ApiClient(SessionStore session, AppConfig config) {
        this.session = session;
        this.config = config;
        rebuild();
    }

    public synchronized void rebuild() {
        this.baseUrl = normalise(config.apiBaseUrl());

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(com.orphos.mobile.BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.BASIC
                : HttpLoggingInterceptor.Level.NONE);

        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(new AuthInterceptor(session))
                .addInterceptor(logging)
                // Speech-to-text runs on CPU and takes appreciably longer than the
                // clip itself, so the 30 s read timeout below would abort any
                // dictation beyond a short sentence. Widened for that one route
                // rather than globally: every other call SHOULD fail fast.
                .addInterceptor(chain -> {
                    okhttp3.Request request = chain.request();
                    // Capture extraction waits on OCR plus an AI summary — same reasoning.
                    String path = request.url().encodedPath();
                    // A voice note is minutes, not a sentence: voice-service waits up to 120 s
                    // on Whisper, so the client must outlast that to hear its answer or error.
                    if (path.endsWith("/api/voice/transcribe")) {
                        return chain.withReadTimeout(150, TimeUnit.SECONDS)
                                    .withWriteTimeout(60, TimeUnit.SECONDS)
                                    .proceed(request);
                    }
                    if (path.endsWith("/api/mobile/captures/extract")
                            || path.endsWith("/api/mobile/captures/upload")) {
                        return chain.withReadTimeout(90, TimeUnit.SECONDS)
                                    .withWriteTimeout(60, TimeUnit.SECONDS)
                                    .proceed(request);
                    }
                    return chain.proceed(request);
                })
                .authenticator(new TokenAuthenticator(session, config))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        this.api = retrofit.create(OrphosApi.class);
    }

    public OrphosApi orphos() {
        return api;
    }

    public String baseUrl() {
        return baseUrl;
    }

    private static String normalise(String url) {
        if (url == null || url.isEmpty()) {
            return "https://orphos.local/";
        }
        return url.endsWith("/") ? url : url + "/";
    }
}
