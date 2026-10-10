package com.orphos.mobile.data.api;

import com.orphos.mobile.data.model.CopilotSession;
import com.orphos.mobile.data.model.DailyDigest;
import com.orphos.mobile.data.model.DecisionAnnotation;
import com.orphos.mobile.data.model.DecisionItem;
import com.orphos.mobile.data.model.MeetingsResponse;
import com.orphos.mobile.data.model.NlQuery;
import com.orphos.mobile.data.model.PriorityQueue;
import com.orphos.mobile.data.model.TenantView;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Retrofit view of the Orphos /api contract (see the Blueprint §02 and the extracted
 * field reference). Paths match the existing gateway route table.
 */
public interface OrphosApi {

    // --- Tenant bootstrap (unauthenticated) ---
    @GET("api/public/tenants/by-slug/{slug}")
    Call<TenantView> tenantBySlug(@Path("slug") String slug);

    @GET("api/public/tenants/by-domain/{domain}")
    Call<TenantView> tenantByDomain(@Path("domain") String domain);

    // --- Home ---
    @GET("api/briefing/daily-digest")
    Call<DailyDigest> dailyDigest();

    @GET("api/mail-insight/meetings")
    Call<MeetingsResponse> meetings();

    /** The same task list as the web dashboard's "Tasks & Approvals" panel. */
    /**
     * Notifications — the SAME endpoint the web dashboard's Notifications panel reads
     * (NotificationsPanel.jsx), so both surfaces show one list. Items carry a
     * {@code criticality} of ALERT | WARNING | INFORMATION, which is what they are
     * grouped by on both platforms.
     */
    @GET("api/mail-insight/notifications")
    Call<MeetingsResponse> mailNotifications();

    @GET("api/mail-insight/tasks")
    Call<MeetingsResponse> tasks();

    /**
     * Tasks completed (DONE) at or after an ISO-8601 instant — the default /tasks hides
     * completed ones. The caller passes the start of the user's local day.
     */
    @GET("api/mail-insight/tasks")
    Call<MeetingsResponse> tasksCompletedSince(@retrofit2.http.Query("completedSince") String sinceIso);

    /** Task status (OPEN | IN_PROGRESS | DONE | DISMISSED) — the web panel's Ignore/Complete. */
    @PATCH("api/mail-insight/items/{id}/status")
    Call<okhttp3.ResponseBody> setTaskStatus(@Path("id") String id, @Body Map<String, Object> body);

    /** Snooze: {"reminderAt": ISO-8601}; /tasks hides the item until then. */
    @PATCH("api/mail-insight/items/{id}/reminder")
    Call<okhttp3.ResponseBody> setTaskReminder(@Path("id") String id, @Body Map<String, Object> body);

    @GET("api/priority/queue")
    Call<PriorityQueue> priorityQueue();

    // --- Decisions (Home + AI Advisors) ---
    @GET("api/decisions")
    Call<List<DecisionItem>> decisions();

    @GET("api/decisions/{id}")
    Call<DecisionItem> decision(@Path("id") String id);

    @GET("api/decisions/{id}/annotation")
    Call<DecisionAnnotation> annotation(@Path("id") String id);

    /** Approve / reject / transition. Body e.g. { "status": "APPROVED", "source": "UI" }. */
    @PATCH("api/decisions/{id}/status")
    Call<DecisionItem> updateDecisionStatus(@Path("id") String id, @Body Map<String, Object> body);

    /** Record an approval decision. Body e.g. { "decision": "APPROVE" }. */
    @POST("api/mobile/approvals/{id}/decision")
    Call<okhttp3.ResponseBody> decideApproval(@Path("id") String id, @Body Map<String, Object> body);

    // --- Voice CEO / search ---
    @POST("api/nl-query/ask")
    Call<NlQuery.Response> ask(@Body NlQuery.Request request);

    // --- AI Co-pilot history — the SAME store as the web dashboard's History ▸ AI tab ---
    /** The current user's sessions, newest first (scoped server-side to token sub + tenant). */
    @GET("api/nl-query/me/copilot/sessions")
    Call<CopilotSession.ListResponse> copilotSessions(@Query("limit") int limit);

    /** Create or update one session; the server upserts on (tenant, user, sessionId). */
    @PUT("api/nl-query/me/copilot/sessions/{sessionId}")
    Call<okhttp3.ResponseBody> saveCopilotSession(@Path("sessionId") String sessionId,
                                                  @Body CopilotSession.Payload body);

    @DELETE("api/nl-query/me/copilot/sessions/{sessionId}")
    Call<Void> deleteCopilotSession(@Path("sessionId") String sessionId);

    @DELETE("api/nl-query/me/copilot/sessions")
    Call<Void> clearCopilotSessions();

    // --- Mobile BFF aggregations (/api/mobile/*) — used in connected mode ---
    /** {@code lang} + the day window, as the web greeting band and meetings rail send them. */
    @GET("api/mobile/home")
    Call<com.google.gson.JsonObject> mobileHome(@Query("lang") String lang,
                                                @Query("from") String from,
                                                @Query("to") String to);

    @GET("api/mobile/team-intelligence")
    Call<com.google.gson.JsonObject> mobileTeamIntelligence();

    @GET("api/mobile/financials")
    Call<com.google.gson.JsonObject> mobileFinancials();

    /** The caller's own web widget board: persona-resolved widget list + each value. */
    @GET("api/mobile/metrics")
    Call<com.google.gson.JsonObject> mobileMetrics();

    /**
     * Meetings for a window the CALLER defines, as ISO-8601 instants. The window is
     * required to match the web dashboard: mail-insight falls back to a rolling
     * now→now+24h when it is omitted, which silently differs from a calendar day.
     */
    @GET("api/mobile/meetings")
    Call<com.google.gson.JsonObject> mobileMeetings(@Query("from") String from,
                                                    @Query("to") String to);

    @GET("api/mobile/travel")
    Call<com.google.gson.JsonElement> mobileTravel();

    /** Auto-inferred, persistent approvals (the authoritative Approval store). */
    @GET("api/mobile/approvals")
    Call<com.google.gson.JsonElement> mobileApprovals();

    /** Per-tenant mobile config (access, security, alerts, status thresholds, refresh). */
    @GET("api/mobile/config")
    Call<com.google.gson.JsonObject> mobileConfig();

    @GET("api/mobile/captures")
    Call<com.google.gson.JsonArray> mobileCaptures();

    @POST("api/mobile/captures")
    Call<com.google.gson.JsonObject> mobileCreateCapture(@Body Map<String, Object> body);

    /** Re-sends the caller's saved captures that are not yet in Web Document Search. */
    @POST("api/mobile/captures/reingest")
    Call<com.google.gson.JsonObject> reingestCaptures();

    /**
     * Upload of a REVIEWED capture: the bytes plus the text as the user corrected it, the
     * AI summary and key points (JSON array). The BFF ingests that text, so Web Document
     * Search finds the note by what it says. {@code transcript} is a voice note's
     * speech-to-text exactly as returned, kept apart from the edited text; null omits it.
     */
    @Multipart
    @POST("api/mobile/captures/upload")
    Call<com.google.gson.JsonObject> uploadReviewedCapture(@Part okhttp3.MultipartBody.Part file,
                                                           @Part("title") okhttp3.RequestBody title,
                                                           @Part("type") okhttp3.RequestBody type,
                                                           @Part("mimeType") okhttp3.RequestBody mimeType,
                                                           @Part("content") okhttp3.RequestBody content,
                                                           @Part("summary") okhttp3.RequestBody summary,
                                                           @Part("keyPoints") okhttp3.RequestBody keyPoints,
                                                           @Part("actions") okhttp3.RequestBody actions,
                                                           @Part("decisions") okhttp3.RequestBody decisions,
                                                           @Part("transcript") okhttp3.RequestBody transcript);

    /**
     * Review step: recognise + AI-summarise a capture WITHOUT saving it. {@code text} is
     * what the device already recognised; without it the {@code file} is OCR'd server-side.
     * Either part may be null (Retrofit then omits it).
     */
    @Multipart
    @POST("api/mobile/captures/extract")
    Call<com.google.gson.JsonObject> extractCapture(@Part okhttp3.MultipartBody.Part file,
                                                    @Part("text") okhttp3.RequestBody text);

    @GET("api/mobile/captures/{id}")
    Call<com.google.gson.JsonObject> mobileCapture(@Path("id") String id);

    /** The capture's original bytes (e.g. the handwriting image). */
    @GET("api/mobile/captures/{id}/file")
    Call<okhttp3.ResponseBody> mobileCaptureFile(@Path("id") String id);

    // --- Mobile profile (persona + roles + access grants) ---
    @GET("api/mobile/profile")
    Call<com.google.gson.JsonObject> mobileProfile();

    // --- Voice / speech-to-text ("VoiceEnabled") ---
    // Served by voice-service, which is a SEPARATELY managed platform functionality:
    // a platform admin starts it and enables it per tenant (Admin -> Voice). Both
    // calls therefore fail normally when voice is off, and callers must treat that
    // as "no dictation here" rather than as an error worth showing.

    /** {available, enabled} for the calling tenant. Dictation needs BOTH true. */
    @GET("api/voice/status")
    Call<com.google.gson.JsonObject> voiceStatus();

    /**
     * Upload a recorded clip and get {text} back. The part name must be "audio" and
     * the FILENAME must carry a real audio extension - Whisper picks its ffmpeg
     * demuxer from it, and an extensionless name is decoded as raw PCM and returns
     * plausible-looking nonsense instead of an error.
     *
     * <p>{@code language} is an ISO-639-1 hint. Without it Whisper auto-detects from the
     * first seconds of audio, which on a 3-4 s dictation routinely picks the wrong
     * language (Tamil and German have both been seen for English speech) and returns
     * a transliterated transcript. Null omits the parameter.</p>
     */
    @Multipart
    @POST("api/voice/transcribe")
    Call<com.google.gson.JsonObject> voiceTranscribe(@Part okhttp3.MultipartBody.Part audio,
                                                     @Query("language") String language);

    @POST("api/mobile/devices")
    Call<Object> registerDevice(@Body Map<String, Object> device);
}
