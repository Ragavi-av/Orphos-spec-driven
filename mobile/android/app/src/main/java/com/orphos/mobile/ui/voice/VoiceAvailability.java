package com.orphos.mobile.ui.voice;

import com.google.gson.JsonObject;
import android.util.Log;
import com.orphos.mobile.data.api.ApiClient;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Whether dictation is live for the signed-in tenant.
 *
 * <p>Voice is on for a user only when the voice module is RUNNING (a platform admin
 * started it) and the tenant has been ENABLED for it. {@code GET /api/voice/status}
 * collapses both into one answer, so the app never reasons about containers.</p>
 *
 * <p>Fails closed, and quietly. The module being stopped is the normal case on most
 * deployments, so the request failing IS the answer — it must never surface as an
 * error. The only consequence of "not available" is that a double-tap on the orb
 * does nothing.</p>
 *
 * <p>The answer is cached for the process: it is a per-deployment, per-tenant
 * setting that changes when an admin flips a switch, not something worth asking
 * about on every screen open. {@link #invalidate()} drops it (sign-out, or a
 * tenant switch).</p>
 */
public final class VoiceAvailability {

    public interface Callbackk {
        void onResult(boolean voiceEnabled);
    }

    private static Boolean cached;
    private static boolean inFlight;

    private VoiceAvailability() { }

    /** Forget the cached answer — call on sign-out or tenant change. */
    public static synchronized void invalidate() {
        cached = null;
    }

    /**
     * Resolve availability, from cache when known. The callback runs on the OkHttp
     * callback thread's main-thread dispatch (Retrofit delivers on the main thread
     * by default on Android), so it is safe to touch views from it.
     */
    public static void check(ApiClient apiClient, Callbackk cb) {
        Log.i("OrphosVoice", "check() entered; cached=" + cached + " inFlight=" + inFlight);
        Boolean hit;
        synchronized (VoiceAvailability.class) {
            hit = cached;
        }
        if (hit != null) { cb.onResult(hit); return; }
        // A second caller while the first is in flight simply gets the current
        // (false) answer rather than queueing — the orb re-checks on next open, and
        // an extra request per screen is not worth a callback list here.
        if (inFlight) { cb.onResult(false); return; }
        inFlight = true;
        Log.i("OrphosVoice", "enqueuing GET /api/voice/status");

        apiClient.orphos().voiceStatus().enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, Response<JsonObject> res) {
                boolean on = false;
                if (res.isSuccessful() && res.body() != null) {
                    JsonObject b = res.body();
                    on = b.has("available") && b.get("available").getAsBoolean()
                      && b.has("enabled")   && b.get("enabled").getAsBoolean();
                }
                // Only a definitive answer is cached: 2xx (the gate spoke) or 403/404
                // (voice disabled / route absent). A 401 or 5xx is transient — an
                // expired token or a service still booting — and caching it would
                // switch voice off until the process is killed.
                int code = res.code();
                boolean definitive = res.isSuccessful() || code == 403 || code == 404;
                Log.i("OrphosVoice", "status HTTP " + code + " -> " + on);
                settle(on, definitive, cb);
            }

            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                // Voice module down / route absent / offline — all mean "no dictation"
                // FOR NOW. Not cached: a connection that was refused a moment ago (a
                // dropped adb reverse, a flaky network) must not disable voice for the
                // rest of the session. The next screen open asks again.
                Log.i("OrphosVoice", "status failed: " + t);
                settle(false, false, cb);
            }
        });
    }

    private static void settle(boolean on, boolean cache, Callbackk cb) {
        synchronized (VoiceAvailability.class) {
            cached = cache ? on : null;
            inFlight = false;
        }
        cb.onResult(on);
    }
}
