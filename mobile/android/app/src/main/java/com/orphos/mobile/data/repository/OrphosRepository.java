package com.orphos.mobile.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.core.config.AppConfig;
import com.orphos.mobile.core.demo.MockData;
import com.orphos.mobile.data.api.ApiClient;
import com.orphos.mobile.data.model.Capture;
import com.orphos.mobile.data.model.CopilotSession;
import com.orphos.mobile.data.model.DailyDigest;
import com.orphos.mobile.data.model.DecisionAnnotation;
import com.orphos.mobile.data.model.DecisionItem;
import com.orphos.mobile.data.model.Financials;
import com.orphos.mobile.data.model.HomeSummary;
import com.orphos.mobile.data.model.MailInsightDto;
import com.orphos.mobile.data.model.MeetingBrief;
import com.orphos.mobile.data.model.MeetingsResponse;
import com.orphos.mobile.data.model.NlQuery;
import com.orphos.mobile.data.model.TeamIntelligence;
import com.orphos.mobile.data.model.TravelItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Single data access point for the UI. Demo mode serves the built-in sample data. Connected
 * mode serves ONLY backend data: when a live call fails the caller gets an empty value
 * (flagged {@code fromMock=true}) and shows its unavailable state — never sample data
 * passed off as the tenant's own, which is what made the app disagree with the web.
 */
public class OrphosRepository {

    /**
     * fromMock = the data is NOT live backend data: demo samples, the offline cache, or the
     * empty value returned when the backend could not be reached.
     */
    public interface DataCallback<T> {
        void onResult(T data, boolean fromMock);
    }

    private final ApiClient api;
    private final AppConfig config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    public OrphosRepository(ApiClient api, AppConfig config) {
        this.api = api;
        this.config = config;
    }

    private boolean demo() {
        return config.isDemoMode();
    }

    private <T> void mock(T data, DataCallback<T> cb) {
        main.post(() -> cb.onResult(data, true));
    }

    // --- offline cache (connected mode) ---

    private SharedPreferences cache() {
        return OrphosApp.get().getSharedPreferences("orphos_cache", Context.MODE_PRIVATE);
    }

    /** Cache entries are per tenant, so switching tenant never serves the previous tenant's rows. */
    private static String scoped(String key) {
        String tenant = OrphosApp.get().session().tenantId();
        return key + ":" + (tenant == null ? "" : tenant);
    }

    private void cachePut(String key, Object value) {
        try { cache().edit().putString(scoped(key), gson.toJson(value)).apply(); } catch (Exception ignored) { }
    }

    private <T> T cacheGet(String key, java.lang.reflect.Type type) {
        try {
            String json = cache().getString(scoped(key), null);
            return json == null ? null : gson.fromJson(json, type);
        } catch (Exception e) { return null; }
    }

    // --- Decisions ---

    public void decisions(DataCallback<List<DecisionItem>> cb) {
        if (demo()) { mock(MockData.decisions(), cb); return; }
        java.lang.reflect.Type type = new TypeToken<List<DecisionItem>>(){}.getType();
        api.orphos().decisions().enqueue(new Callback<List<DecisionItem>>() {
            @Override public void onResponse(@NonNull Call<List<DecisionItem>> c, @NonNull Response<List<DecisionItem>> r) {
                if (r.isSuccessful() && r.body() != null) {
                    cachePut("decisions", r.body());
                    cb.onResult(r.body(), false);
                } else {
                    offline();
                }
            }
            @Override public void onFailure(@NonNull Call<List<DecisionItem>> c, @NonNull Throwable t) {
                offline();
            }
            private void offline() {
                List<DecisionItem> cached = cacheGet("decisions", type);
                cb.onResult(cached != null ? cached : new java.util.ArrayList<>(), true);
            }
        });
    }

    public void decision(String id, DataCallback<DecisionItem> cb) {
        if (demo()) { mock(MockData.decision(id), cb); return; }
        api.orphos().decision(id).enqueue(new Callback<DecisionItem>() {
            @Override public void onResponse(@NonNull Call<DecisionItem> c, @NonNull Response<DecisionItem> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(r.body(), false);
                else cb.onResult(null, true);
            }
            @Override public void onFailure(@NonNull Call<DecisionItem> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    public void annotation(String id, DataCallback<DecisionAnnotation> cb) {
        if (demo()) { mock(MockData.annotation(id), cb); return; }
        api.orphos().annotation(id).enqueue(new Callback<DecisionAnnotation>() {
            @Override public void onResponse(@NonNull Call<DecisionAnnotation> c, @NonNull Response<DecisionAnnotation> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(r.body(), false);
                else cb.onResult(null, true);
            }
            @Override public void onFailure(@NonNull Call<DecisionAnnotation> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    /**
     * PATCH /api/decisions/{id}/status. A failed write resolves with {@code null} — callers
     * must check it before telling the user the status changed.
     */
    public void updateDecisionStatus(String id, String status, DataCallback<DecisionItem> cb) {
        if (demo()) {
            DecisionItem d = MockData.decision(id);
            d.status = status;
            mock(d, cb);
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        body.put("source", "UI");
        api.orphos().updateDecisionStatus(id, body).enqueue(new Callback<DecisionItem>() {
            @Override public void onResponse(@NonNull Call<DecisionItem> c, @NonNull Response<DecisionItem> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(r.body(), false);
                else cb.onResult(null, true);
            }
            @Override public void onFailure(@NonNull Call<DecisionItem> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    /**
     * Records an approval decision through the mobile BFF (/api/mobile/approvals/{id}/decision).
     * {@code decision} is one of APPROVE | REJECT | YES | NO | GO | HOLD. Resolves {@code true}
     * only when the BFF accepted the decision; callers must not show it as decided otherwise.
     * Demo mode resolves locally without a network call.
     */
    public void decideApproval(String id, String decision, DataCallback<Boolean> cb) {
        if (demo()) { main.post(() -> cb.onResult(true, true)); return; }
        if (id == null) { main.post(() -> cb.onResult(false, false)); return; }
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        api.orphos().decideApproval(id, body).enqueue(new Callback<okhttp3.ResponseBody>() {
            @Override public void onResponse(@NonNull Call<okhttp3.ResponseBody> c, @NonNull Response<okhttp3.ResponseBody> r) {
                cb.onResult(r.isSuccessful(), false);
            }
            @Override public void onFailure(@NonNull Call<okhttp3.ResponseBody> c, @NonNull Throwable t) {
                cb.onResult(false, true);
            }
        });
    }

    /** Demo-mode tasks the user dismissed or snoozed — kept hidden until the app restarts. */
    private final java.util.Set<String> demoHiddenTasks = new java.util.HashSet<>();
    /** Demo-mode tasks the user marked in progress. */
    private final java.util.Set<String> demoInProgressTasks = new java.util.HashSet<>();
    /** Demo-mode tasks the user completed (a subset of demoHiddenTasks). */
    private final java.util.Set<String> demoDoneTasks = new java.util.HashSet<>();

    /**
     * How many tasks the user completed today (status DONE since local midnight) — the "done"
     * half of Home's progress ring. Dismissed tasks are not counted. A failed call resolves
     * {@code null}.
     */
    public void doneTaskCount(DataCallback<Integer> cb) {
        if (demo()) { mock(demoDoneTasks.size(), cb); return; }
        String startOfDay = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toString();
        api.orphos().tasksCompletedSince(startOfDay).enqueue(new Callback<MeetingsResponse>() {
            @Override public void onResponse(@NonNull Call<MeetingsResponse> c, @NonNull Response<MeetingsResponse> r) {
                if (r.isSuccessful() && r.body() != null && r.body().result != null) {
                    cb.onResult(r.body().result.size(), false);
                } else {
                    cb.onResult(null, true);
                }
            }
            @Override public void onFailure(@NonNull Call<MeetingsResponse> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    /**
     * Sets a task's status (DISMISSED, OPEN, …) via mail-insight — the same write the web
     * panel's Ignore makes. Resolves {@code true} only when the server accepted it.
     */
    public void setTaskStatus(String id, String status, DataCallback<Boolean> cb) {
        if (demo()) {
            demoInProgressTasks.remove(id);
            demoDoneTasks.remove(id);
            if ("OPEN".equalsIgnoreCase(status)) demoHiddenTasks.remove(id);
            else if ("IN_PROGRESS".equalsIgnoreCase(status)) demoInProgressTasks.add(id);
            else {
                demoHiddenTasks.add(id);
                if ("DONE".equalsIgnoreCase(status)) demoDoneTasks.add(id);
            }
            main.post(() -> cb.onResult(true, true));
            return;
        }
        if (id == null) { main.post(() -> cb.onResult(false, false)); return; }
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        api.orphos().setTaskStatus(id, body).enqueue(booleanCallback(cb));
    }

    /**
     * Snoozes a task until {@code reminderAtIso}; mail-insight hides it from /tasks until then
     * and re-surfaces it afterwards. {@code null} clears the snooze.
     */
    public void snoozeTask(String id, String reminderAtIso, DataCallback<Boolean> cb) {
        if (demo()) {
            if (reminderAtIso == null) demoHiddenTasks.remove(id);
            else demoHiddenTasks.add(id);
            main.post(() -> cb.onResult(true, true));
            return;
        }
        if (id == null) { main.post(() -> cb.onResult(false, false)); return; }
        Map<String, Object> body = new HashMap<>();
        body.put("reminderAt", reminderAtIso);
        api.orphos().setTaskReminder(id, body).enqueue(booleanCallback(cb));
    }

    private Callback<okhttp3.ResponseBody> booleanCallback(DataCallback<Boolean> cb) {
        return new Callback<okhttp3.ResponseBody>() {
            @Override public void onResponse(@NonNull Call<okhttp3.ResponseBody> c, @NonNull Response<okhttp3.ResponseBody> r) {
                cb.onResult(r.isSuccessful(), false);
            }
            @Override public void onFailure(@NonNull Call<okhttp3.ResponseBody> c, @NonNull Throwable t) {
                cb.onResult(false, true);
            }
        };
    }

    /**
     * Approvals from the mobile BFF (/api/mobile/approvals) — the authoritative, auto-inferred
     * Approval store. In demo mode it derives a list from the mock decisions that match
     * approval keywords; a failed live call resolves empty (flagged not-live).
     */
    public void approvals(DataCallback<List<com.orphos.mobile.data.model.ApprovalItem>> cb) {
        if (demo()) { mock(demoApprovals(), cb); return; }
        api.orphos().mobileApprovals().enqueue(new Callback<com.google.gson.JsonElement>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonElement> c,
                                             @NonNull Response<com.google.gson.JsonElement> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(parseApprovals(r.body()), false);
                else cb.onResult(new java.util.ArrayList<>(), true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonElement> c, @NonNull Throwable t) {
                cb.onResult(new java.util.ArrayList<>(), true);
            }
        });
    }

    private List<com.orphos.mobile.data.model.ApprovalItem> parseApprovals(com.google.gson.JsonElement el) {
        List<com.orphos.mobile.data.model.ApprovalItem> list = new java.util.ArrayList<>();
        com.google.gson.JsonArray arr = asArray(el);
        if (arr == null) return list;
        for (com.google.gson.JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            com.google.gson.JsonObject o = e.getAsJsonObject();
            com.orphos.mobile.data.model.ApprovalItem a = new com.orphos.mobile.data.model.ApprovalItem();
            a.id = str(o, "id");
            a.sourceId = str(o, "sourceId");
            a.sourceRef = str(o, "sourceRef");
            a.source = str(o, "source");
            a.subject = str(o, "subject");
            a.summary = str(o, "summary");
            a.approvalType = str(o, "approvalType");
            a.status = str(o, "status");
            a.decision = str(o, "decision");
            list.add(a);
        }
        return list;
    }

    /** Demo/offline approvals derived from the mock decisions that need an approve/reject response. */
    private List<com.orphos.mobile.data.model.ApprovalItem> demoApprovals() {
        List<com.orphos.mobile.data.model.ApprovalItem> list = new java.util.ArrayList<>();
        for (DecisionItem d : MockData.decisions()) {
            if (d.status != null && ("APPROVED".equalsIgnoreCase(d.status)
                    || "REJECTED".equalsIgnoreCase(d.status))) continue;
            String text = ((d.title == null ? "" : d.title) + " "
                    + (d.rationale == null ? "" : d.rationale) + " "
                    + (d.description == null ? "" : d.description));
            if (!APPROVAL_KW.matcher(text).find()) continue;
            com.orphos.mobile.data.model.ApprovalItem a = new com.orphos.mobile.data.model.ApprovalItem();
            a.id = d.id; a.sourceRef = d.id; a.source = "DECISION";
            a.subject = d.title; a.summary = d.rationale != null ? d.rationale : d.description;
            a.approvalType = inferApprovalType(text); a.status = "PENDING";
            list.add(a);
        }
        return list;
    }

    // --- Tasks & Approvals (same list as the web dashboard panel) ---

    /** Keywords that mark a task as needing an explicit approve / reject response. */
    private static final java.util.regex.Pattern APPROVAL_KW = java.util.regex.Pattern.compile(
            "\\b(approv\\w*|sign[- ]?off|authoris\\w*|authoriz\\w*|sanction\\w*|sign\\s+the|" +
            "requires? (?:your )?(?:approval|sign)|needs? approval|for approval|yes/no|ratif\\w*)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Tasks for action — the same mail-insight list the web "Tasks & Approvals" panel shows,
     * joined to the BFF Approval store by task id ({@code approval.sourceRef == task.id}), so
     * the Approval tab here, the Approvals screen and the web panel all show one set.
     * Demo mode serves the decision-derived sample tasks; a failed live call resolves empty.
     */
    public void actionTasks(DataCallback<List<com.orphos.mobile.data.model.ActionTask>> cb) {
        if (demo()) {
            List<com.orphos.mobile.data.model.ActionTask> tasks = mapActionTasks(MockData.decisions());
            tasks.removeIf(t -> t.id != null && demoHiddenTasks.contains(t.id));
            for (com.orphos.mobile.data.model.ActionTask t : tasks) {
                t.status = t.id != null && demoInProgressTasks.contains(t.id) ? "IN_PROGRESS" : "OPEN";
            }
            mock(tasks, cb);
            return;
        }
        approvals((approvals, approvalsFromMock) -> {
            // Only a live approval list can be joined to live task ids.
            Map<String, com.orphos.mobile.data.model.ApprovalItem> byTask = new HashMap<>();
            if (!approvalsFromMock && approvals != null) {
                for (com.orphos.mobile.data.model.ApprovalItem a : approvals) {
                    if (a.sourceRef != null && !a.sourceRef.isEmpty()) byTask.put(a.sourceRef, a);
                }
            }
            api.orphos().tasks().enqueue(new Callback<MeetingsResponse>() {
                @Override public void onResponse(@NonNull Call<MeetingsResponse> c, @NonNull Response<MeetingsResponse> r) {
                    if (r.isSuccessful() && r.body() != null && r.body().result != null) {
                        cb.onResult(mapInsightTasks(r.body().result, byTask), false);
                    } else {
                        cb.onResult(new java.util.ArrayList<>(), true);
                    }
                }
                @Override public void onFailure(@NonNull Call<MeetingsResponse> c, @NonNull Throwable t) {
                    cb.onResult(new java.util.ArrayList<>(), true);
                }
            });
        });
    }

    private List<com.orphos.mobile.data.model.ActionTask> mapInsightTasks(
            List<MailInsightDto> items, Map<String, com.orphos.mobile.data.model.ApprovalItem> byTask) {
        List<com.orphos.mobile.data.model.ActionTask> out = new java.util.ArrayList<>();
        for (MailInsightDto m : items) {
            if (m.status != null && ("DONE".equalsIgnoreCase(m.status)
                    || "DISMISSED".equalsIgnoreCase(m.status))) continue;

            com.orphos.mobile.data.model.ActionTask t = new com.orphos.mobile.data.model.ActionTask();
            t.id = m.id;
            t.title = m.title;
            t.severity = m.priority;
            t.status = m.status;
            t.meta = m.senderDisplay != null && !m.senderDisplay.isEmpty() ? m.senderDisplay
                    : (m.source != null ? m.source : "Task");
            t.reason = m.rationale != null && !m.rationale.isEmpty() ? m.rationale : m.summary;

            // The BFF approval record is the authority on "needs approval" — the same one the
            // web panel reads — so both apps flag exactly the same tasks.
            com.orphos.mobile.data.model.ApprovalItem a = byTask.get(m.id);
            t.requiresApproval = a != null;
            if (a != null) {
                t.approvalId = a.id;
                t.approvalType = a.approvalType != null ? a.approvalType : "APPROVE_REJECT";
                t.approvalStatus = a.status != null ? a.status : "PENDING";
                t.decision = a.decision;
            }
            applyTaskPresentation(t, null);
            out.add(t);
        }
        return out;
    }

    private List<com.orphos.mobile.data.model.ActionTask> mapActionTasks(List<DecisionItem> decisions) {
        List<com.orphos.mobile.data.model.ActionTask> out = new java.util.ArrayList<>();
        if (decisions == null) return out;
        for (DecisionItem d : decisions) {
            // Skip already-resolved decisions.
            if (d.status != null && ("APPROVED".equalsIgnoreCase(d.status)
                    || "REJECTED".equalsIgnoreCase(d.status) || "COMPLETED".equalsIgnoreCase(d.status))) continue;

            com.orphos.mobile.data.model.ActionTask t = new com.orphos.mobile.data.model.ActionTask();
            t.id = d.id;
            t.title = d.title;
            t.severity = d.priorityLevel;
            String area = d.decisionArea != null ? d.decisionArea : d.domain;
            // Sub-line under the title (e.g. "Contract #4928-B", "Gamma Ventures • Today 4:00 PM").
            t.meta = d.impactSummary != null ? d.impactSummary
                    : (area != null ? area : (d.priorityLevel != null ? d.priorityLevel : "Decision"));
            t.reason = d.rationale != null ? d.rationale
                    : (d.impactSummary != null ? d.impactSummary : d.description);

            String text = ((d.title == null ? "" : d.title) + " "
                    + (d.rationale == null ? "" : d.rationale) + " "
                    + (d.description == null ? "" : d.description));
            t.requiresApproval = APPROVAL_KW.matcher(text).find();
            t.approvalType = inferApprovalType(text);
            t.approvalStatus = "PENDING";
            applyTaskPresentation(t, area);
            out.add(t);
        }
        return out;
    }

    /** Infers the approval response style from the task text: yes/no, go-ahead, or approve/reject. */
    private static String inferApprovalType(String text) {
        String s = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("yes/no") || s.contains("yes / no")) return "YES_NO";
        if (s.contains("go-ahead") || s.contains("go ahead")) return "GO_AHEAD";
        return "APPROVE_REJECT";
    }

    /**
     * Derives the Figma "ORPHOS-1" Tasks-card presentation — icon, AI badge label and the
     * action controls — from the decision area. Critical items show a neutral outlined button
     * plus a blue primary; High items collapse to a single full-width orange action.
     */
    private void applyTaskPresentation(com.orphos.mobile.data.model.ActionTask t, String area) {
        String a = area == null ? "" : area.toUpperCase(java.util.Locale.ROOT);
        boolean critical = "CRITICAL".equalsIgnoreCase(t.severity);
        switch (a) {
            case "CUSTOMER":
                t.icon = "person-check"; t.kind = "AI REASONING";
                t.secondary = "View Contract"; t.primary = "Review & Approve";
                break;
            case "INVESTOR":
                t.icon = "handshake"; t.kind = "AI ADVICE";
                t.secondary = "View Doc"; t.primary = "Prepare Now";
                break;
            case "MARKETING":
                t.icon = "megaphone"; t.kind = "AI OBSERVATION";
                t.singleAction = true; t.actionLabel = "Optimize Budget";
                break;
            case "PEOPLE":
                t.icon = "people"; t.kind = "AI IMPACT ANALYSIS";
                t.singleAction = true; t.actionLabel = "Review HR Plan";
                break;
            default:
                t.icon = critical ? "person-check" : "megaphone";
                t.kind = t.requiresApproval ? "AI REASONING" : "AI ADVICE";
                if (critical) {
                    t.secondary = "View";
                    t.primary = t.requiresApproval ? "Review & Approve" : "Open";
                } else {
                    t.singleAction = true;
                    t.actionLabel = t.requiresApproval ? "Review & Approve" : "Review";
                }
        }
    }

    // --- Home ---

    /**
     * Notifications — the same mail-insight feed the web dashboard's Notifications panel
     * shows, so both surfaces list one set. Returned UNFILTERED and ungrouped: the
     * caller groups by {@code criticality} (ALERT / WARNING / INFORMATION) exactly as
     * the web panel does, rather than this layer deciding what is worth showing.
     */
    public void mailNotifications(DataCallback<List<MailInsightDto>> cb) {
        if (demo()) { mock(MockData.notifications(), cb); return; }
        api.orphos().mailNotifications().enqueue(new Callback<MeetingsResponse>() {
            @Override public void onResponse(@NonNull Call<MeetingsResponse> c, @NonNull Response<MeetingsResponse> r) {
                if (r.isSuccessful() && r.body() != null && r.body().result != null) {
                    cb.onResult(r.body().result, false);
                } else {
                    cb.onResult(new java.util.ArrayList<>(), true);
                }
            }
            @Override public void onFailure(@NonNull Call<MeetingsResponse> c, @NonNull Throwable t) {
                cb.onResult(new java.util.ArrayList<>(), true);
            }
        });
    }

    /**
     * Meetings. Served from the backend in every mode, demo included: the web dashboard
     * has no sample calendar, so a built-in one here could only disagree with it.
     */
    public void meetings(DataCallback<List<MailInsightDto>> cb) {
        api.orphos().meetings().enqueue(new Callback<MeetingsResponse>() {
            @Override public void onResponse(@NonNull Call<MeetingsResponse> c, @NonNull Response<MeetingsResponse> r) {
                if (r.isSuccessful() && r.body() != null && r.body().result != null) cb.onResult(r.body().result, false);
                else cb.onResult(new java.util.ArrayList<>(), true);
            }
            @Override public void onFailure(@NonNull Call<MeetingsResponse> c, @NonNull Throwable t) {
                cb.onResult(new java.util.ArrayList<>(), true);
            }
        });
    }

    // --- Travel (extracted from mail-insight emails) ---

    /**
     * Travel for the tenant, aggregated by the mobile BFF (/api/mobile/travel) — the same
     * list the web Travel rail reads. An empty list is a real answer ("no trips") and is
     * passed through as live. Like meetings, this is never served from built-in samples,
     * in demo mode either, so the app can only show the trips the web shows.
     */
    public void travel(DataCallback<List<TravelItem>> cb) {
        api.orphos().mobileTravel().enqueue(new Callback<com.google.gson.JsonElement>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonElement> c,
                                             @NonNull Response<com.google.gson.JsonElement> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(parseTravel(r.body()), false);
                else cb.onResult(new java.util.ArrayList<>(), true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonElement> c, @NonNull Throwable t) {
                cb.onResult(new java.util.ArrayList<>(), true);
            }
        });
    }

    /**
     * Trips still ahead, soonest first, with display-ready date and time labels.
     *
     * This mirrors {@code upcoming()} in the web TravelRail, so both surfaces list the
     * same trips in the same order. Trips that left before today are dropped. Trips with
     * no readable date are kept at the end. The BFF returns the rows newest-stored first,
     * and inferred rows carry a raw ISO instant in {@code travelDate}/{@code time}. Used
     * as is, the Home card showed whichever trip was inferred last, as an ISO string.
     */
    private List<TravelItem> parseTravel(com.google.gson.JsonElement el) {
        List<TravelItem> dated = new java.util.ArrayList<>();
        List<TravelItem> undated = new java.util.ArrayList<>();
        com.google.gson.JsonArray arr = asArray(el);
        if (arr == null) return dated;
        long today = dayStartMillis(0);
        for (com.google.gson.JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            com.google.gson.JsonObject o = e.getAsJsonObject();
            TravelItem t = new TravelItem();
            t.sourceId = str(o, "id");
            t.from = firstNonNull(str(o, "from"), str(o, "origin"));
            t.to = firstNonNull(str(o, "to"), str(o, "destination"));
            t.mode = firstNonNull(str(o, "mode"), str(o, "travelMode"));
            // The BFF Travel structure uses "travelDate" (+ "time"); keep the older keys as fallbacks.
            String rawDate = firstNonNull(str(o, "travelDate"), str(o, "date"),
                    str(o, "startAt"), str(o, "dueDate"));
            String rawTime = firstNonNull(str(o, "time"), str(o, "startAt"));
            t.purpose = firstNonNull(str(o, "purpose"), str(o, "title"), str(o, "summary"));
            t.confirmationStatus = firstNonNull(str(o, "confirmationStatus"), str(o, "status"));

            t.startAtMillis = tripStart(rawDate, rawTime);
            if (t.startAtMillis == null) {
                // Nothing parsable: keep whatever text the row has rather than
                // dropping the only "when" the user has.
                t.date = rawDate;
                t.time = rawTime;
                undated.add(t);
            } else if (t.startAtMillis >= today) {
                java.util.Date at = new java.util.Date(t.startAtMillis);
                t.date = new java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault()).format(at);
                t.time = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(at);
                dated.add(t);
            }
        }
        dated.sort((a, b) -> Long.compare(a.startAtMillis, b.startAtMillis));
        dated.addAll(undated);
        return dated;
    }

    /**
     * A trip's departure as epoch ms, or null when the row has no readable date.
     *
     * Rows arrive in two shapes, as in TravelRail.tripStart. Inferred trips copy the
     * source meeting's ISO instant into both fields. Older rows have text ("Mon, 22 Sep"
     * plus "6:30 AM") that omits the year, so that form is read against the current year.
     */
    private static Long tripStart(String date, String time) {
        for (String iso : new String[] { time, date }) {
            if (iso == null) continue;
            try { return java.time.Instant.parse(iso).toEpochMilli(); } catch (Exception ignored) { }
        }
        if (date == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})\\s+([A-Za-z]{3,})").matcher(date);
        if (!m.find()) return null;
        String text = m.group(1) + " " + m.group(2).substring(0, 3) + " "
                + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                + (time != null && !time.isBlank() ? " " + time.trim() : " 12:00 AM");
        try {
            java.util.Date d = new java.text.SimpleDateFormat("d MMM yyyy h:mm a", java.util.Locale.ENGLISH).parse(text);
            return d == null ? null : d.getTime();
        } catch (Exception e) {
            return null;
        }
    }

    public void dailyDigest(DataCallback<DailyDigest> cb) {
        if (demo()) { mock(MockData.dailyDigest(), cb); return; }
        api.orphos().dailyDigest().enqueue(new Callback<DailyDigest>() {
            @Override public void onResponse(@NonNull Call<DailyDigest> c, @NonNull Response<DailyDigest> r) {
                if (r.isSuccessful() && r.body() != null) {
                    cachePut("digest", r.body());
                    cb.onResult(r.body(), false);
                } else {
                    offline();
                }
            }
            @Override public void onFailure(@NonNull Call<DailyDigest> c, @NonNull Throwable t) {
                offline();
            }
            private void offline() {
                cb.onResult(cacheGet("digest", DailyDigest.class), true);
            }
        });
    }

    // --- Home dashboard ---

    public void homeSummary(DataCallback<HomeSummary> cb) {
        if (demo()) { mock(MockData.homeSummary(), cb); return; }
        // Same digest language and calendar day the web greeting band asks for.
        api.orphos().mobileHome(java.util.Locale.getDefault().getLanguage(), dayStartIso(0), dayStartIso(1))
                .enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(parseHome(r.body()), false);
                else cb.onResult(emptyHome(), true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(emptyHome(), true);
            }
        });
    }

    /**
     * A Home summary with no data. Task counts are NOT part of /api/mobile/home — the Home
     * screen takes them from the same task + approval lists the Tasks screen shows.
     */
    private static HomeSummary emptyHome() {
        HomeSummary h = new HomeSummary();
        h.summary = new java.util.ArrayList<>();
        h.meetings = new java.util.ArrayList<>();
        return h;
    }

    private HomeSummary parseHome(com.google.gson.JsonObject o) {
        HomeSummary h = emptyHome();

        // Alerts = CRITICAL items, plus HIGH items only when configured to include them.
        boolean includeHigh = config.alertsIncludeHigh();
        int alerts = 0;
        alerts += countBySeverity(o.get("decisions"), "priorityLevel", includeHigh);
        // Also fold in prioritised notifications/alerts if the BFF supplies them.
        alerts += countBySeverity(o.get("alerts"), "severity", includeHigh);
        alerts += countBySeverity(o.get("notifications"), "priority", includeHigh);
        h.criticalCount = alerts;

        com.google.gson.JsonElement mt = o.get("meetings");
        if (mt != null && mt.isJsonObject()) {
            com.google.gson.JsonElement res = mt.getAsJsonObject().get("result");
            if (res != null && res.isJsonArray()) {
                for (com.google.gson.JsonElement e : res.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    com.google.gson.JsonObject m = e.getAsJsonObject();
                    h.meetings.add(new HomeSummary.Meeting(str(m, "dueDate"), str(m, "title")));
                }
            }
        }

        com.google.gson.JsonElement dg = o.get("digest");
        if (dg != null && dg.isJsonObject()) {
            com.google.gson.JsonElement items = dg.getAsJsonObject().get("items");
            if (items != null && items.isJsonArray()) {
                for (com.google.gson.JsonElement e : items.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    com.google.gson.JsonObject it = e.getAsJsonObject();
                    String sev = str(it, "severity");
                    String severity = "critical".equalsIgnoreCase(sev) ? "CRITICAL"
                            : ("warning".equalsIgnoreCase(sev) ? "WARNING" : "INFO");
                    h.summary.add(new HomeSummary.Point(str(it, "title"), severity));
                }
            }
        }
        return h;
    }

    /**
     * Counts array items whose severity field is CRITICAL (always) or HIGH (only when
     * {@code includeHigh}). Used to map priority notifications to the app's alert count.
     */
    private static int countBySeverity(com.google.gson.JsonElement arr, String field, boolean includeHigh) {
        int n = 0;
        if (arr != null && arr.isJsonArray()) {
            for (com.google.gson.JsonElement e : arr.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                String sev = str(e.getAsJsonObject(), field);
                if (sev == null) continue;
                if ("CRITICAL".equalsIgnoreCase(sev) || "ALERT".equalsIgnoreCase(sev)) n++;
                else if (includeHigh && ("HIGH".equalsIgnoreCase(sev) || "WARNING".equalsIgnoreCase(sev))) n++;
            }
        }
        return n;
    }

    private static String str(com.google.gson.JsonObject o, String key) {
        try {
            com.google.gson.JsonElement e = o.get(key);
            return (e != null && !e.isJsonNull()) ? e.getAsString() : null;
        } catch (Exception ex) { return null; }
    }

    private static int intVal(com.google.gson.JsonObject o, String key) {
        try {
            com.google.gson.JsonElement e = o.get(key);
            return (e != null && !e.isJsonNull()) ? e.getAsInt() : 0;
        } catch (Exception ex) { return 0; }
    }

    /** Today's meetings, in this device's zone. */
    public void meetingBriefs(DataCallback<List<MeetingBrief>> cb) {
        meetingBriefs(dayStartIso(0), dayStartIso(1), cb);
    }

    /**
     * Meetings for an explicit window, as ISO-8601 instants — what the calendar's
     * day picker needs. The window is always supplied because mail-insight otherwise
     * falls back to a rolling now→now+24h, which is not a calendar day; the web
     * dashboard has always sent it (MeetingsRail.dayStart) and mobile now matches.
     */
    public void meetingBriefs(String fromIso, String toIso, DataCallback<List<MeetingBrief>> cb) {
        // Backend only, in demo mode too. See meetings().
        api.orphos().mobileMeetings(fromIso, toIso)
                .enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (r.isSuccessful() && r.body() != null) {
                    // An empty day is a REAL answer for a calendar and is passed through as
                    // live. A failed call resolves empty too, but flagged not-live.
                    cb.onResult(parseMeetings(r.body()), false);
                } else cb.onResult(new java.util.ArrayList<>(), true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(new java.util.ArrayList<>(), true);
            }
        });
    }

    /**
     * Midnight of today + {@code offset} days, in THIS DEVICE'S zone, as an ISO-8601
     * instant. Mirrors dayStart() in the web MeetingsRail so both surfaces ask for the
     * same calendar day: the server deliberately does not derive "today" itself, because
     * only the client knows where the tenant's day starts.
     */
    private static String dayStartIso(int offset) {
        return java.time.Instant.ofEpochMilli(dayStartMillis(offset)).toString();
    }

    /** {@link #dayStartIso} as epoch ms. */
    private static long dayStartMillis(int offset) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        c.add(java.util.Calendar.DAY_OF_MONTH, offset);
        return c.getTimeInMillis();
    }

    private List<MeetingBrief> parseMeetings(com.google.gson.JsonObject o) {
        List<MeetingBrief> list = new java.util.ArrayList<>();
        com.google.gson.JsonElement res = o.get("result");
        if (res != null && res.isJsonArray()) {
            for (com.google.gson.JsonElement e : res.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject m = e.getAsJsonObject();
                int attendeeCount = 0;
                String startAt = null;
                String location = null;
                String end = null;
                List<String> points = new java.util.ArrayList<>();
                List<String> attendees = new java.util.ArrayList<>();
                String organizer = null, joinUrl = null;

                // The curated meeting detail: attendees, end, room and brief points.
                com.google.gson.JsonElement mtgEl = m.get("meeting");
                if (mtgEl != null && mtgEl.isJsonObject()) {
                    com.google.gson.JsonObject mtg = mtgEl.getAsJsonObject();
                    attendeeCount = intVal(mtg, "attendeeCount");
                    startAt = str(mtg, "startAt");
                    end = str(mtg, "endAt");
                    location = str(mtg, "locationText");
                    points.addAll(parseJsonStringArray(str(mtg, "briefPointsJson")));
                    attendees.addAll(parseAttendeeNames(str(mtg, "attendeesJson")));
                    organizer = str(mtg, "organizerDisplay");
                    joinUrl = str(mtg, "onlineJoinUrlRef");
                }
                // Fall back to splitting the summary when no brief points were supplied.
                if (points.isEmpty()) {
                    String summary = str(m, "summary");
                    if (summary != null && !summary.isEmpty()) {
                        for (String s : summary.split("(?<=\\.)\\s+|;\\s*")) {
                            String p = s.trim();
                            if (!p.isEmpty()) points.add(p);
                        }
                    }
                }
                // The start, in the order the backend itself uses to place a meeting in the
                // requested day (MailInsightRepository.startOf: dueDate, else receivedAt) and
                // the web rail displays (meetings.js startOf). meeting.startAt is only the last
                // resort: an LLM pass can move dueDate, and reading a different field here put
                // the phone at odds with both the day filter and the web.
                String time = firstNonBlank(str(m, "dueDate"), str(m, "receivedAt"), startAt);
                MeetingBrief brief = new MeetingBrief(str(m, "id"), formatClock(time),
                        str(m, "title"), attendeeCount, points);
                brief.startAtIso = time;
                brief.endAtIso = end;
                brief.location = location;
                brief.attendees = attendees;
                brief.organizer = organizer;
                brief.joinUrl = joinUrl;
                brief.priority = firstNonBlank(str(m, "priority"), str(m, "criticality"));
                list.add(brief);
            }
        }
        // Earliest first: the feed is sorted server-side today, but every screen relies on
        // it, so it is not left to chance.
        list.sort(MeetingBrief.BY_START);
        return list;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** Display names from meeting.attendeesJson ({@code [{displayName,response}]}). */
    private static List<String> parseAttendeeNames(String json) {
        List<String> out = new java.util.ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(json);
            if (el.isJsonArray()) {
                for (com.google.gson.JsonElement e : el.getAsJsonArray()) {
                    String name = null;
                    if (e.isJsonObject()) name = str(e.getAsJsonObject(), "displayName");
                    else if (e.isJsonPrimitive()) name = e.getAsString();
                    if (name != null && !name.isBlank()) out.add(name.trim());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Parses a JSON string holding an array of strings (e.g. briefPointsJson) into a list. */
    private static List<String> parseJsonStringArray(String json) {
        List<String> out = new java.util.ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(json);
            if (el.isJsonArray()) {
                for (com.google.gson.JsonElement e : el.getAsJsonArray()) {
                    if (!e.isJsonNull()) out.add(e.getAsString());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Formats an ISO-8601 instant to a short local clock time; returns the input on failure. */
    private static String formatClock(String iso) {
        if (iso == null || iso.isBlank()) return iso;
        try {
            java.time.Instant inst = java.time.OffsetDateTime.parse(iso.trim()).toInstant();
            return java.time.format.DateTimeFormatter.ofPattern("h:mm a")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(inst);
        } catch (Exception e) {
            return iso;
        }
    }

    // --- Business / Team Intelligence ---
    // Live sources: briefing Zenitus HR metrics + okg-reasoning insights, aggregated by the
    // BFF at /api/mobile/team-intelligence.

    public void teamIntelligence(DataCallback<TeamIntelligence> cb) {
        if (demo()) { mock(MockData.teamIntelligence(), cb); return; }
        api.orphos().mobileTeamIntelligence().enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(parseTeam(r.body()), false);
                else cb.onResult(emptyTeam(), true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(emptyTeam(), true);
            }
        });
    }

    private static TeamIntelligence emptyTeam() {
        TeamIntelligence t = new TeamIntelligence();
        t.period = "This month";
        t.productivity = new java.util.ArrayList<>();
        t.availability = new java.util.ArrayList<>();
        t.burnout = new java.util.ArrayList<>();
        return t;
    }

    private TeamIntelligence parseTeam(com.google.gson.JsonObject o) {
        TeamIntelligence t = emptyTeam();

        // Productivity — one bar per org unit (consolidated snapshot).
        com.google.gson.JsonElement prod = o.get("productivity");
        if (prod != null && prod.isJsonArray()) {
            for (com.google.gson.JsonElement e : prod.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject d = e.getAsJsonObject();
                t.productivity.add(new TeamIntelligence.Productivity(
                        str(d, "orgUnit"), (int) Math.round(dbl(d, "productivityIndex"))));
            }
        }

        // Availability — consolidated counts summed into status buckets (no per-person data).
        com.google.gson.JsonElement avail = o.get("availability");
        if (avail != null && avail.isJsonArray()) {
            int available = 0, busy = 0, onLeave = 0;
            for (com.google.gson.JsonElement e : avail.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject d = e.getAsJsonObject();
                available += intVal(d, "availableCount");
                busy += intVal(d, "busyCount");
                onLeave += intVal(d, "onLeaveCount");
            }
            if (available + busy + onLeave > 0) {
                t.availability.add(new TeamIntelligence.Availability("Available", available));
                t.availability.add(new TeamIntelligence.Availability("Busy", busy));
                t.availability.add(new TeamIntelligence.Availability("On leave", onLeave));
            }
        }
        // Fall back to HR summary by department if no consolidated availability exists.
        if (t.availability.isEmpty()) {
            com.google.gson.JsonElement hr = o.get("hrSummary");
            if (hr != null && hr.isJsonObject()) {
                com.google.gson.JsonElement byDept = hr.getAsJsonObject().get("byDepartment");
                if (byDept != null && byDept.isJsonArray()) {
                    for (com.google.gson.JsonElement e : byDept.getAsJsonArray()) {
                        if (!e.isJsonObject()) continue;
                        com.google.gson.JsonObject d = e.getAsJsonObject();
                        t.availability.add(new TeamIntelligence.Availability(str(d, "label"), intVal(d, "count")));
                    }
                }
            }
        }

        // Wellbeing — a burnout signal per org unit whose overload rate is elevated.
        com.google.gson.JsonElement well = o.get("wellbeing");
        if (well != null && well.isJsonArray()) {
            for (com.google.gson.JsonElement e : well.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject d = e.getAsJsonObject();
                double overload = dbl(d, "overloadRatePct");
                if (overload >= 15d) {
                    t.burnout.add(new TeamIntelligence.Burnout(
                            str(d, "orgUnit"),
                            String.format(java.util.Locale.getDefault(), "%.0f%% over the overload threshold", overload),
                            "Elevated team overload",
                            "Rebalance workload and encourage time off."));
                }
            }
        }

        // Plus any per-person overload insights inferred by okg-reasoning (role, not name).
        com.google.gson.JsonElement ins = o.get("insights");
        if (ins != null && ins.isJsonObject()) {
            com.google.gson.JsonElement op = ins.getAsJsonObject().get("OverloadedPerson");
            if (op != null && op.isJsonArray()) {
                for (com.google.gson.JsonElement e : op.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    t.burnout.add(new TeamIntelligence.Burnout(
                            str(e.getAsJsonObject(), "displayLabel"),
                            "Flagged as overloaded", "Possible burnout",
                            "Appreciate their effort and encourage them to take time off."));
                }
            }
        }
        return t;
    }

    // --- Scribbles / captures ---
    // Live: doc-ingest (OCR + RAG) for content; capture index/threads on the BFF.
    // Demo: a durable on-device store (LocalCaptureStore) so notes persist + list across restarts.

    private com.orphos.mobile.core.capture.LocalCaptureStore captureStore;

    private com.orphos.mobile.core.capture.LocalCaptureStore captureStore() {
        if (captureStore == null) {
            captureStore = new com.orphos.mobile.core.capture.LocalCaptureStore(OrphosApp.get());
        }
        return captureStore;
    }

    /**
     * The captures saved on this device in demo mode — only what the user saved, never
     * samples. Builds before this change seeded three sample captures ("cap-seed-*") into
     * the store on first use; they are purged here so they stop showing as real notes.
     */
    private List<Capture> localCaptures() {
        List<Capture> l = captureStore().list();
        if (l == null) return new java.util.ArrayList<>();
        boolean hadSeeds = l.removeIf(c -> c.id != null && c.id.startsWith("cap-seed-"));
        if (hadSeeds) captureStore().save(l);
        return l;
    }

    public void captures(DataCallback<List<Capture>> cb) {
        if (demo()) { mock(localCaptures(), cb); return; }
        api.orphos().mobileCaptures().enqueue(new Callback<com.google.gson.JsonArray>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonArray> c,
                                             @NonNull Response<com.google.gson.JsonArray> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(parseCaptures(r.body()), false);
                else cb.onResult(new java.util.ArrayList<>(), true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonArray> c, @NonNull Throwable t) {
                cb.onResult(new java.util.ArrayList<>(), true);
            }
        });
    }

    /**
     * Asks the BFF to re-send the user's saved captures that never reached Web Document
     * Search (doc-ingest was down, or refused the forward). Saving never waits on this, so a
     * capture can be listed before it is searchable; this closes that gap. Resolves with the
     * number newly made searchable, or {@code null} when the call failed. No-op in demo mode,
     * which has no web search.
     */
    public void reingestCaptures(DataCallback<Integer> cb) {
        if (demo()) { mock(0, cb); return; }
        api.orphos().reingestCaptures().enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (!r.isSuccessful() || r.body() == null) { cb.onResult(null, true); return; }
                com.google.gson.JsonElement n = r.body().get("ingested");
                cb.onResult(n != null && n.isJsonPrimitive() ? n.getAsInt() : 0, false);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    /**
     * Review step: recognise and AI-summarise a capture WITHOUT saving it. Resolves with a
     * draft (content = recognised text; title/summary/keyPoints from the AI when available),
     * or {@code null} when the server could not be reached. {@code recognisedText} is what
     * the device already read (on-device handwriting recognition) — when present it is sent
     * instead of the image, so the server only summarises. Demo mode has no AI, so the draft
     * carries just the device's text for the user to complete — except the sample voice
     * transcript, which gets its sample summary so the voice flow can be shown end to end.
     */
    public void extractCapture(java.io.File file, String recognisedText, String mimeType,
                               DataCallback<Capture> cb) {
        boolean hasText = recognisedText != null && !recognisedText.trim().isEmpty();
        if (demo() && MockData.VOICE_NOTE_TRANSCRIPT.equals(recognisedText)) {
            main.postDelayed(() -> cb.onResult(MockData.voiceNoteDraft(), false), 900);
            return;
        }
        if (demo()) {
            Capture draft = new Capture();
            draft.content = hasText ? recognisedText.trim() : "";
            mock(draft, cb);
            return;
        }
        okhttp3.MultipartBody.Part filePart = null;
        if (!hasText && file != null && file.exists()) {
            okhttp3.MediaType mt = okhttp3.MediaType.parse(mimeType != null ? mimeType : "image/png");
            filePart = okhttp3.MultipartBody.Part.createFormData("file", file.getName(),
                    okhttp3.RequestBody.create(file, mt));
        }
        okhttp3.RequestBody textPart = hasText
                ? okhttp3.RequestBody.create(recognisedText.trim(), okhttp3.MediaType.parse("text/plain"))
                : null;
        if (filePart == null && textPart == null) { cb.onResult(null, true); return; }

        api.orphos().extractCapture(filePart, textPart).enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (!r.isSuccessful() || r.body() == null) { cb.onResult(null, true); return; }
                com.google.gson.JsonObject o = r.body();
                Capture draft = new Capture();
                draft.title = str(o, "title");
                draft.content = str(o, "text");
                draft.summary = str(o, "summary");
                draft.keyPoints = stringList(o.get("keyPoints"));
                draft.actions = stringList(o.get("actions"));
                draft.decisions = stringList(o.get("decisions"));
                draft.lowConfidence = o.has("lowConfidence") && o.get("lowConfidence").getAsBoolean();
                cb.onResult(draft, false);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    /**
     * Saves a REVIEWED capture: its original bytes (none for a typed note, {@code file} null)
     * plus the text, summary and key points as the user confirmed them, under the file name
     * they chose. {@code actions}/{@code decisions} are the AI's, passed through untouched
     * (null when the AI never classified the note). Connected, the BFF persists
     * it (ArcadeDB) and ingests the text into doc-ingest for Web Document Search; resolves
     * with the stored record, or {@code null} when it was not saved. {@code transcript} is a
     * voice note's unedited speech-to-text, stored beside {@code content}; null for others.
     */
    public void saveReviewedCapture(java.io.File file, String title, String type, String mimeType,
                                    String content, String transcript, String summary, List<String> keyPoints,
                                    List<String> actions, List<String> decisions,
                                    DataCallback<Capture> cb) {
        String mime = (mimeType != null && !mimeType.isEmpty()) ? mimeType : mimeFor(type);
        if (demo()) {
            Capture c = new Capture(newCaptureId(), title, type, "Just now");
            c.content = content;
            c.transcript = transcript;
            c.summary = summary;
            c.keyPoints = keyPoints;
            c.actions = actions;
            c.decisions = decisions;
            c.mimeType = mime;
            c.ingestChannel = ingestChannelFor(type);
            c.hasFile = file != null && file.exists();
            c.localFilePath = c.hasFile ? file.getAbsolutePath() : null;
            captureStore().add(c);
            mock(c, cb);
            return;
        }
        if (file == null) {
            // Typed note: no bytes to upload. The BFF stores the text, summary and key points,
            // then sends them to doc-ingest.
            Map<String, Object> body = new HashMap<>();
            body.put("title", title);
            body.put("type", type);
            body.put("content", content);
            body.put("summary", summary);
            body.put("keyPoints", keyPoints != null ? keyPoints : new java.util.ArrayList<String>());
            if (actions != null) body.put("actions", actions);
            if (decisions != null) body.put("decisions", decisions);
            body.put("mimeType", mime);
            body.put("ingestChannel", ingestChannelFor(type));
            api.orphos().mobileCreateCapture(body).enqueue(new Callback<com.google.gson.JsonObject>() {
                @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                                 @NonNull Response<com.google.gson.JsonObject> r) {
                    if (r.isSuccessful() && r.body() != null) cb.onResult(parseCapture(r.body()), false);
                    else cb.onResult(null, true);
                }
                @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                    cb.onResult(null, true);
                }
            });
            return;
        }
        if (!file.exists()) { cb.onResult(null, true); return; }

        okhttp3.MediaType text = okhttp3.MediaType.parse("text/plain");
        okhttp3.MediaType mt = okhttp3.MediaType.parse(mime);
        okhttp3.MultipartBody.Part filePart = okhttp3.MultipartBody.Part.createFormData("file", file.getName(),
                okhttp3.RequestBody.create(file, mt != null ? mt : okhttp3.MediaType.parse("application/octet-stream")));
        com.google.gson.JsonArray points = new com.google.gson.JsonArray();
        if (keyPoints != null) for (String k : keyPoints) points.add(k);

        api.orphos().uploadReviewedCapture(filePart,
                okhttp3.RequestBody.create(nz(title), text),
                okhttp3.RequestBody.create(nz(type), text),
                okhttp3.RequestBody.create(nz(mime), text),
                okhttp3.RequestBody.create(nz(content), text),
                okhttp3.RequestBody.create(nz(summary), text),
                okhttp3.RequestBody.create(points.toString(), text),
                jsonPart(actions, text),
                jsonPart(decisions, text),
                transcript == null || transcript.trim().isEmpty() ? null
                        : okhttp3.RequestBody.create(transcript.trim(), text))
                .enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(parseCapture(r.body()), false);
                else cb.onResult(null, true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    /** Why a voice note produced no transcript. */
    public enum TranscribeError { NOT_ENABLED, NO_SPEECH, TIMEOUT, UNAVAILABLE, OFFLINE }

    public interface TranscriptCallback {
        /** Exactly one of {@code text} (never blank) and {@code error} is non-null. */
        void onTranscript(String text, TranscribeError error);
    }

    /**
     * Speech-to-text for a recorded voice note, by the platform's own Whisper through
     * voice-service — the same route Ask Orphos dictates through. The clip is NOT deleted:
     * the caller keeps it for playback and saves it with the note. {@code language} is an
     * ISO-639-1 hint, or null for Whisper to detect. Demo mode has no Whisper to ask, so it
     * resolves with {@link MockData#VOICE_NOTE_TRANSCRIPT}, which the review labels as a sample.
     */
    public void transcribeVoiceNote(java.io.File audio, String language, TranscriptCallback cb) {
        if (audio == null || !audio.exists() || audio.length() == 0L) {
            main.post(() -> cb.onTranscript(null, TranscribeError.NO_SPEECH));
            return;
        }
        if (demo()) {
            // A short pause so the processing timeline reads as transcription, not a skip.
            main.postDelayed(() -> cb.onTranscript(MockData.VOICE_NOTE_TRANSCRIPT, null), 1200);
            return;
        }
        // The filename's extension is what tells Whisper how to decode the clip.
        okhttp3.MultipartBody.Part part = okhttp3.MultipartBody.Part.createFormData("audio", audio.getName(),
                okhttp3.RequestBody.create(audio, okhttp3.MediaType.parse("audio/mp4")));
        api.orphos().voiceTranscribe(part, language).enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (!r.isSuccessful() || r.body() == null) {
                    cb.onTranscript(null, r.code() == 403 || r.code() == 404
                            ? TranscribeError.NOT_ENABLED : TranscribeError.UNAVAILABLE);
                    return;
                }
                String text = str(r.body(), "text");
                if (text == null || text.trim().isEmpty()) cb.onTranscript(null, TranscribeError.NO_SPEECH);
                else cb.onTranscript(text.trim(), null);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onTranscript(null, t instanceof java.net.SocketTimeoutException
                        ? TranscribeError.TIMEOUT : TranscribeError.UNAVAILABLE);
            }
        });
    }

    /**
     * A capture's stored original written to a cache file (e.g. a voice note's recording, for
     * playback), or null when it has none or it could not be fetched.
     */
    public void captureFile(Capture capture, java.io.File cacheDir, DataCallback<java.io.File> cb) {
        if (capture == null || !capture.hasFile) { cb.onResult(null, demo()); return; }
        if (demo()) {
            java.io.File local = capture.localFilePath != null ? new java.io.File(capture.localFilePath) : null;
            mock(local != null && local.exists() ? local : null, cb);
            return;
        }
        api.orphos().mobileCaptureFile(capture.id).enqueue(new Callback<okhttp3.ResponseBody>() {
            @Override public void onResponse(@NonNull Call<okhttp3.ResponseBody> c,
                                             @NonNull Response<okhttp3.ResponseBody> r) {
                java.io.File out = null;
                if (r.isSuccessful() && r.body() != null) {
                    java.io.File dir = new java.io.File(cacheDir, "captures");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    java.io.File f = new java.io.File(dir, "file-" + capture.id.replaceAll("[^A-Za-z0-9_-]", "_")
                            + extensionFor(capture.mimeType));
                    try (java.io.InputStream in = r.body().byteStream();
                         java.io.OutputStream os = new java.io.FileOutputStream(f)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                        out = f;
                    } catch (Exception e) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                cb.onResult(out, out == null);
            }
            @Override public void onFailure(@NonNull Call<okhttp3.ResponseBody> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) return ".bin";
        // audio/mp4 has no single registered extension on every device; the recorder writes it as .m4a.
        if ("audio/mp4".equalsIgnoreCase(mimeType)) return ".m4a";
        String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        return ext != null && !ext.isEmpty() ? "." + ext : ".bin";
    }

    private static final int MAX_IMAGE_DISPLAY_PX = 2048;

    /** A capture's stored original as a bitmap (e.g. the handwriting), or null when none. */
    public void captureImage(Capture capture, DataCallback<android.graphics.Bitmap> cb) {
        if (capture == null || !capture.hasFile) { cb.onResult(null, demo()); return; }
        if (demo()) {
            android.graphics.Bitmap bmp = com.orphos.mobile.core.capture.CaptureFiles
                    .decodeForDisplay(capture.localFilePath, MAX_IMAGE_DISPLAY_PX);
            mock(bmp, cb);
            return;
        }
        api.orphos().mobileCaptureFile(capture.id).enqueue(new Callback<okhttp3.ResponseBody>() {
            @Override public void onResponse(@NonNull Call<okhttp3.ResponseBody> c,
                                             @NonNull Response<okhttp3.ResponseBody> r) {
                android.graphics.Bitmap bmp = null;
                if (r.isSuccessful() && r.body() != null) {
                    // Downsampled for display: a full-resolution camera original can exceed app memory.
                    try {
                        bmp = com.orphos.mobile.core.capture.CaptureFiles
                                .decodeForDisplay(r.body().bytes(), MAX_IMAGE_DISPLAY_PX);
                    } catch (Exception ignored) { }
                }
                cb.onResult(bmp, bmp == null);
            }
            @Override public void onFailure(@NonNull Call<okhttp3.ResponseBody> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    private static List<String> stringList(com.google.gson.JsonElement el) {
        if (el == null || !el.isJsonArray()) return null;
        List<String> out = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement e : el.getAsJsonArray()) {
            if (!e.isJsonNull()) out.add(e.getAsString());
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** A list as a JSON-array form part; null (part omitted) when the list is. */
    private static okhttp3.RequestBody jsonPart(List<String> items, okhttp3.MediaType type) {
        if (items == null) return null;
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String s : items) a.add(s);
        return okhttp3.RequestBody.create(a.toString(), type);
    }

    /** doc-ingest content type for a capture type (drives image OCR vs audio transcription). */
    private static String mimeFor(String type) {
        if (type == null) return "text/plain";
        switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case Capture.TYPE_PHOTO: return "image/jpeg";
            case Capture.TYPE_IMAGE:
            case Capture.TYPE_HANDWRITING: return "image/png";
            case Capture.TYPE_AUDIO:
            case Capture.TYPE_VOICE: return "audio/mp4";
            default: return "text/plain";
        }
    }

    /** Ingestion channel: plain note, image OCR, or audio transcription. */
    private static String ingestChannelFor(String type) {
        if (type == null) return "NOTE";
        switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case Capture.TYPE_PHOTO:
            case Capture.TYPE_IMAGE:
            case Capture.TYPE_HANDWRITING: return "OCR_IMAGE";
            case Capture.TYPE_AUDIO:
            case Capture.TYPE_VOICE: return "TRANSCRIBE_AUDIO";
            default: return "NOTE";
        }
    }

    /** Unique capture id for locally-created captures (MockData ids collide once we stop using its list). */
    private static String newCaptureId() { return "cap-" + System.currentTimeMillis(); }

    public void captureById(String id, DataCallback<Capture> cb) {
        if (demo()) {
            mock(captureStore().find(id), cb);
            return;
        }
        if (id == null) { cb.onResult(null, true); return; }
        api.orphos().mobileCapture(id).enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(parseCapture(r.body()), false);
                else cb.onResult(null, true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    private List<Capture> parseCaptures(com.google.gson.JsonArray a) {
        List<Capture> list = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement e : a) {
            if (e.isJsonObject()) list.add(parseCapture(e.getAsJsonObject()));
        }
        return list;
    }

    private Capture parseCapture(com.google.gson.JsonObject o) {
        String created = str(o, "createdAt");
        Capture c = new Capture(str(o, "id"), str(o, "title"), str(o, "type"), createdLabel(created));
        if (created != null && !created.isEmpty() && created.chars().allMatch(Character::isDigit)) {
            try { c.createdAtMillis = Long.parseLong(created); } catch (NumberFormatException ignored) { }
        }
        c.content = str(o, "content");
        c.transcript = str(o, "transcript");
        c.summary = str(o, "summary");
        c.keyPoints = stringList(o.get("keyPoints"));
        c.actions = stringList(o.get("actions"));
        c.decisions = stringList(o.get("decisions"));
        c.mimeType = str(o, "mimeType");
        c.hasFile = bool(o, "hasFile", false);
        c.ingested = bool(o, "ingested", false);
        return c;
    }

    /**
     * The BFF stamps createdAt as epoch millis; show it as a local date + time. Older rows
     * carry the label the client used to send (e.g. "Just now"), which is kept as-is.
     */
    private static String createdLabel(String raw) {
        if (raw == null || raw.isEmpty() || !raw.chars().allMatch(Character::isDigit)) return raw;
        try {
            return java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a", java.util.Locale.getDefault())
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(Long.parseLong(raw)));
        } catch (Exception e) {
            return raw;
        }
    }

    // --- Financials (read-only) ---

    public void financials(String currency, DataCallback<Financials> cb) {
        mock(MockData.financials(currency), cb);
    }

    /**
     * The CFO Financials screen. Signed in, it is the BFF financials aggregation — the CFO web
     * widget board's sources, never sampled; demo mode shows MockData's sample figures.
     */
    public void financialsView(DataCallback<com.orphos.mobile.data.model.FinancialsView> cb) {
        if (demo()) { mock(MockData.financialsView(), cb); return; }
        api.orphos().mobileFinancials().enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(parseFinancials(r.body()), false);
                else cb.onResult(new com.orphos.mobile.data.model.FinancialsView(), true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(new com.orphos.mobile.data.model.FinancialsView(), true);
            }
        });
    }

    private com.orphos.mobile.data.model.FinancialsView parseFinancials(com.google.gson.JsonObject o) {
        com.orphos.mobile.data.model.FinancialsView v = new com.orphos.mobile.data.model.FinancialsView();
        com.google.gson.JsonObject cf = obj(o, "cashflow");
        if (cf != null) {
            String cur = str(cf, "currency");
            v.fiscalYear = str(cf, "fiscalYear");
            com.google.gson.JsonObject latest = obj(cf, "latest");
            if (latest != null) v.latestMonth = str(latest, "monthName");
            com.google.gson.JsonObject ytd = obj(cf, "ytd");
            if (ytd != null) {
                if (ytd.has("inflow")) v.cashInflow = new com.orphos.mobile.data.model.FinancialsView.Amount(dbl(ytd, "inflow"), cur);
                if (ytd.has("outflow")) v.cashOutflow = new com.orphos.mobile.data.model.FinancialsView.Amount(dbl(ytd, "outflow"), cur);
                if (ytd.has("nett")) v.netCashflow = new com.orphos.mobile.data.model.FinancialsView.Amount(dbl(ytd, "nett"), cur);
            }
            com.google.gson.JsonElement months = cf.get("months");
            if (months != null && months.isJsonArray()) {
                for (com.google.gson.JsonElement e : months.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    com.google.gson.JsonObject mo = e.getAsJsonObject();
                    com.orphos.mobile.data.model.FinancialsView.Month m = new com.orphos.mobile.data.model.FinancialsView.Month();
                    m.label = firstNonBlank(joinSpace(str(mo, "monthName"), str(mo, "fiscalYear")), "—");
                    m.inflow = dbl(mo, "inflow");
                    m.outflow = dbl(mo, "outflow");
                    m.nett = dbl(mo, "nett");
                    m.currency = cur;
                    v.months.add(m);
                }
            }
        }
        // Cost-centre budget lines: what the web "IT Budget Consumed" and "Budget Variance"
        // tiles (and their drill-downs) read from the briefing.
        com.google.gson.JsonObject bo = obj(o, "budgetOverruns");
        com.google.gson.JsonElement res = bo != null ? bo.get("result") : null;
        if (res != null && res.isJsonArray()) {
            for (com.google.gson.JsonElement e : res.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject r = e.getAsJsonObject();
                com.orphos.mobile.data.model.FinancialsView.Overrun ov = new com.orphos.mobile.data.model.FinancialsView.Overrun();
                ov.name = firstNonBlank(str(r, "name"), str(r, "id"), "—");
                ov.spent = dbl(r, "spent");
                ov.budget = dbl(r, "budget");
                ov.currency = str(r, "currency");
                v.overruns.add(ov);
            }
        }

        // Widget-board persona metrics — the very figures the CFO's web dashboard tiles show.
        // Applied last so they override the cashflow-summary aggregates above.
        com.google.gson.JsonObject metrics = obj(o, "metrics");
        com.orphos.mobile.data.model.FinancialsView.Amount a;
        if ((a = metricAmount(metrics, "net-cashflow")) != null) v.netCashflow = a;
        if ((a = metricAmount(metrics, "cash-inflow")) != null) v.cashInflow = a;
        if ((a = metricAmount(metrics, "cash-outflow")) != null) v.cashOutflow = a;
        v.revenueArr = metricAmount(metrics, "revenue-arr");
        v.payroll = metricAmount(metrics, "payroll");
        v.riskExposure = metricAmount(metrics, "risk-exposure");
        v.budgetVariance = metricDisplay(metrics, "budget-variance");
        return v;
    }

    /** A CURRENCY persona metric as a raw amount, or null (missing, not permitted, other unit). */
    private static com.orphos.mobile.data.model.FinancialsView.Amount metricAmount(
            com.google.gson.JsonObject metrics, String id) {
        com.google.gson.JsonObject mv = obj(metrics, id);
        if (mv == null || !bool(mv, "permitted", true) || !bool(mv, "available", false)) return null;
        com.google.gson.JsonElement v = mv.get("value");
        if (v == null || v.isJsonNull() || !"CURRENCY".equals(str(mv, "unit"))) return null;
        return new com.orphos.mobile.data.model.FinancialsView.Amount(dbl(mv, "value"), str(mv, "currency"));
    }

    private static String joinSpace(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + " " + b;
    }

    /**
     * A widgets-service MetricValue rendered like the web tile: CURRENCY in its own
     * currency, PERCENT to one decimal, anything else as-is. {@code null} when the metric
     * is missing, not permitted or has no observation — the tile then keeps its placeholder
     * rather than showing a made-up 0.
     */
    private static String metricDisplay(com.google.gson.JsonObject metrics, String id) {
        com.google.gson.JsonObject mv = obj(metrics, id);
        if (mv == null || !bool(mv, "permitted", true) || !bool(mv, "available", false)) return null;
        com.google.gson.JsonElement v = mv.get("value");
        if (v == null || v.isJsonNull()) return null;
        String unit = str(mv, "unit");
        if ("CURRENCY".equals(unit)) return money(dbl(mv, "value"), str(mv, "currency"));
        if ("PERCENT".equals(unit)) return String.format(java.util.Locale.getDefault(), "%.1f%%", dbl(mv, "value"));
        return v.getAsString();
    }

    private static com.google.gson.JsonArray asArray(com.google.gson.JsonElement el) {
        if (el == null) return null;
        if (el.isJsonArray()) return el.getAsJsonArray();
        if (el.isJsonObject()) {
            com.google.gson.JsonObject o = el.getAsJsonObject();
            for (String key : new String[]{"result", "widgets", "items"}) {
                com.google.gson.JsonElement inner = o.get(key);
                if (inner != null && inner.isJsonArray()) return inner.getAsJsonArray();
            }
        }
        return null;
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    // --- Metrics tab: the caller's own web widget board ---
    //
    // Every persona's Metrics tab is built from /api/mobile/metrics — the widget list the
    // web board resolves for this user (user → tenant default → persona) with each value —
    // so a number on the phone is the number on the dashboard. Demo mode keeps MockData's
    // designed sample screens (the CEO screen uses a bundled sample board instead);
    // connected mode never falls back to them.

    /** The caller's widget board, in board order; null (fromMock=true) when unreachable. */
    public void personaWidgets(DataCallback<List<com.orphos.mobile.data.model.PersonaWidget>> cb) {
        metricsBoard((b, failed) -> cb.onResult(b == null ? null : b.widgets, failed));
    }

    /** Widget tiles plus the records behind them; null (fromMock=true) when unreachable. */
    public void metricsBoard(DataCallback<com.orphos.mobile.data.model.PersonaWidget.Board> cb) {
        api.orphos().mobileMetrics().enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (r.isSuccessful() && r.body() != null) {
                    cb.onResult(parseBoard(r.body()), false);
                } else {
                    cb.onResult(null, true);
                }
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    /** One /api/mobile/metrics body → its widget tiles and the records behind them. */
    private com.orphos.mobile.data.model.PersonaWidget.Board parseBoard(com.google.gson.JsonObject body) {
        com.orphos.mobile.data.model.PersonaWidget.Board b = new com.orphos.mobile.data.model.PersonaWidget.Board();
        b.widgets = parseWidgets(body);
        b.details = parseDetails(obj(body, "details"));
        applyDefinitions(b.widgets, obj(obj(body, "details"), "definitions"));
        return b;
    }

    /**
     * Demo mode's board: a bundled /api/mobile/metrics response (assets/demo/), parsed exactly
     * as a live one so the demo screen is built — and laid out — the same way. Null if unreadable.
     */
    private com.orphos.mobile.data.model.PersonaWidget.Board demoBoard(String asset) {
        try (java.io.Reader in = new java.io.InputStreamReader(
                OrphosApp.get().getAssets().open(asset), java.nio.charset.StandardCharsets.UTF_8)) {
            return parseBoard(gson.fromJson(in, com.google.gson.JsonObject.class));
        } catch (Exception e) {
            return null;
        }
    }

    /** Attaches each widget's ontology definition (metric key, roll-up, direction of good). */
    private static void applyDefinitions(List<com.orphos.mobile.data.model.PersonaWidget> widgets,
                                         com.google.gson.JsonObject defs) {
        if (defs == null) return;
        for (com.orphos.mobile.data.model.PersonaWidget w : widgets) {
            com.google.gson.JsonObject def = w.id == null ? null : obj(defs, w.id);
            if (def == null) continue;
            w.metricKey = str(def, "metricKey");
            w.aggregation = str(def, "aggregation");
            if (def.has("higherIsBetter") && !def.get("higherIsBetter").isJsonNull()) {
                w.higherIsBetter = bool(def, "higherIsBetter", true);
            }
        }
    }

    private com.orphos.mobile.data.model.MetricDetails parseDetails(com.google.gson.JsonObject d) {
        com.orphos.mobile.data.model.MetricDetails m = new com.orphos.mobile.data.model.MetricDetails();
        if (d == null) return m;
        for (com.google.gson.JsonObject o : objects(d.get("okrs"))) {
            com.orphos.mobile.data.model.MetricDetails.Objective ob = new com.orphos.mobile.data.model.MetricDetails.Objective();
            ob.name = str(o, "name");
            ob.progress = (int) dbl(o, "progress");
            for (com.google.gson.JsonObject k : objects(o.get("keyResults"))) {
                com.orphos.mobile.data.model.MetricDetails.KeyResult kr = new com.orphos.mobile.data.model.MetricDetails.KeyResult();
                kr.name = str(k, "name");
                kr.progress = (int) dbl(k, "progress");
                ob.keyResults.add(kr);
            }
            m.objectives.add(ob);
        }
        for (com.google.gson.JsonObject o : objects(d.get("risks"))) {
            com.orphos.mobile.data.model.MetricDetails.Risk r = new com.orphos.mobile.data.model.MetricDetails.Risk();
            r.name = str(o, "name");
            r.category = str(o, "riskCategory");
            r.status = str(o, "riskStatus");
            r.severity = (int) dbl(o, "residualSeverity");
            r.exposure = num(o, "exposureAmount");
            r.currency = str(o, "currency");
            r.dueDate = str(o, "remediationDueDate");
            m.risks.add(r);
        }
        for (com.google.gson.JsonObject o : objects(d.get("projects"))) {
            com.orphos.mobile.data.model.MetricDetails.Project p = new com.orphos.mobile.data.model.MetricDetails.Project();
            p.name = str(o, "name");
            p.status = str(o, "status");
            p.owner = str(o, "owner");
            p.progress = o.has("progress") && !o.get("progress").isJsonNull() ? (int) dbl(o, "progress") : -1;
            p.budget = num(o, "budget");
            p.spent = num(o, "spent");
            p.currency = str(o, "currency");
            p.endDate = str(o, "endDate");
            m.projects.add(p);
        }
        for (com.google.gson.JsonObject o : objects(d.get("pipeline"))) {
            com.orphos.mobile.data.model.MetricDetails.Opportunity op = new com.orphos.mobile.data.model.MetricDetails.Opportunity();
            op.name = str(o, "name");
            op.stage = str(o, "stage");
            op.probability = num(o, "probability");
            op.dealValue = num(o, "dealValue");
            op.weightedValue = num(o, "weightedValue");
            op.currency = str(o, "currency");
            op.closeDate = str(o, "expectedCloseDate");
            m.pipeline.add(op);
        }
        for (com.google.gson.JsonObject o : objects(d.get("overBudget"))) {
            com.orphos.mobile.data.model.MetricDetails.CostCentre cc = new com.orphos.mobile.data.model.MetricDetails.CostCentre();
            cc.name = str(o, "name");
            cc.owner = str(o, "owner");
            cc.budget = num(o, "budget");
            cc.spent = num(o, "spent");
            cc.variance = num(o, "variance");
            cc.currency = str(o, "currency");
            cc.period = str(o, "period");
            m.overBudget.add(cc);
        }
        if (d.has("overBudgetCount") && !d.get("overBudgetCount").isJsonNull()) m.overBudgetCount = (int) dbl(d, "overBudgetCount");
        com.google.gson.JsonObject cash = obj(d, "cashflow");
        if (cash != null) {
            m.cashCurrency = str(cash, "currency");
            m.fiscalYear = str(cash, "fiscalYear");
            for (com.google.gson.JsonObject o : objects(cash.get("months"))) {
                com.orphos.mobile.data.model.MetricDetails.CashMonth cm = new com.orphos.mobile.data.model.MetricDetails.CashMonth();
                cm.label = firstNonBlank(joinSpace(str(o, "monthName"), str(o, "fiscalYear")), "—");
                cm.inflow = dbl(o, "inflow");
                cm.outflow = dbl(o, "outflow");
                cm.nett = dbl(o, "nett");
                m.cashflow.add(cm);
            }
        }
        return m;
    }

    private static List<com.google.gson.JsonObject> objects(com.google.gson.JsonElement el) {
        List<com.google.gson.JsonObject> out = new java.util.ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        for (com.google.gson.JsonElement e : el.getAsJsonArray()) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    /** A numeric field, or NaN when absent / null / not a number. */
    private static double num(com.google.gson.JsonObject o, String key) {
        com.google.gson.JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return Double.NaN;
        try { return e.getAsDouble(); } catch (Exception ex) { return Double.NaN; }
    }

    private List<com.orphos.mobile.data.model.PersonaWidget> parseWidgets(com.google.gson.JsonObject body) {
        List<com.orphos.mobile.data.model.PersonaWidget> out = new java.util.ArrayList<>();
        com.google.gson.JsonArray arr = asArray(body.get("widgets"));
        if (arr == null) return out;
        android.content.Context ctx = OrphosApp.get();
        for (com.google.gson.JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            com.google.gson.JsonObject o = e.getAsJsonObject();
            com.orphos.mobile.data.model.PersonaWidget w = new com.orphos.mobile.data.model.PersonaWidget();
            w.id = str(o, "id");
            w.label = firstNonBlank(str(o, "label"), w.id);
            w.area = str(o, "area");
            w.description = str(o, "description");
            w.source = str(o, "source");
            w.unit = str(o, "unit");
            w.period = str(o, "period");
            w.permitted = bool(o, "permitted", true);
            w.status = ragStatus(str(o, "ragStatus"));
            com.google.gson.JsonElement v = o.get("value");
            boolean has = w.permitted && bool(o, "available", false) && v != null && !v.isJsonNull();
            if (!has) {
                w.caption = ctx.getString(!w.permitted ? R.string.cm_not_permitted
                        : bool(o, "connected", true) ? R.string.cm_no_data : R.string.cm_not_connected);
                out.add(w);
                continue;
            }
            String currency = str(o, "currency");
            try { w.raw = v.getAsDouble(); } catch (Exception ignored) { }
            w.value = formatMetric(v, w.unit, currency);

            // One number, one comparison: attainment against the declared target when
            // there is one, otherwise the source's own caption — then the period.
            List<String> parts = new java.util.ArrayList<>();
            com.google.gson.JsonElement t = o.get("targetValue");
            if (t != null && !t.isJsonNull()) {
                w.target = formatMetric(t, w.unit, currency);
                double target = dbl(o, "targetValue");
                if (target != 0 && !Double.isNaN(w.raw)) {
                    w.attainmentPct = (int) Math.round(w.raw / target * 100);
                    parts.add(ctx.getString(R.string.cm_pct_of_target_fmt, w.attainmentPct, w.target));
                } else {
                    parts.add(ctx.getString(R.string.cm_target_fmt, w.target));
                }
            } else if (str(o, "subtitle") != null) {
                parts.add(str(o, "subtitle"));
            }
            if (w.period != null) parts.add(w.period);
            w.caption = parts.isEmpty() ? null : android.text.TextUtils.join(" · ", parts);
            w.periodElapsedPct = elapsedPct(str(o, "periodStart"), str(o, "periodEnd"));
            out.add(w);
        }
        return out;
    }

    private static String formatMetric(com.google.gson.JsonElement v, String unit, String currency) {
        try {
            if ("CURRENCY".equals(unit)) return com.orphos.mobile.data.model.PersonaWidget.money(v.getAsDouble(), currency);
            if ("PERCENT".equals(unit)) return String.format(java.util.Locale.getDefault(), "%.1f%%", v.getAsDouble());
            if ("COUNT".equals(unit)) return String.format(java.util.Locale.getDefault(), "%,d", v.getAsLong());
        } catch (Exception ignored) { }
        return v.getAsString();
    }

    private static com.orphos.mobile.data.model.PersonaWidget.Status ragStatus(String rag) {
        if (rag == null) return com.orphos.mobile.data.model.PersonaWidget.Status.NONE;
        switch (rag.toUpperCase(java.util.Locale.ROOT)) {
            case "GREEN": return com.orphos.mobile.data.model.PersonaWidget.Status.GOOD;
            case "AMBER": return com.orphos.mobile.data.model.PersonaWidget.Status.WARN;
            case "RED":   return com.orphos.mobile.data.model.PersonaWidget.Status.BAD;
            default:      return com.orphos.mobile.data.model.PersonaWidget.Status.NONE;
        }
    }

    /** Share of [start, end] elapsed now, 0-100; -1 when either bound is missing or unparseable. */
    private static int elapsedPct(String start, String end) {
        long s = epochMillis(start), e = epochMillis(end);
        if (s < 0 || e <= s) return -1;
        double pct = (System.currentTimeMillis() - s) * 100.0 / (e - s);
        return (int) Math.round(Math.max(0, Math.min(100, pct)));
    }

    private static long epochMillis(String iso) {
        if (iso == null || iso.isEmpty()) return -1;
        try { return java.time.Instant.parse(iso).toEpochMilli(); } catch (Exception ignored) { }
        try {
            return java.time.LocalDate.parse(iso.length() > 10 ? iso.substring(0, 10) : iso)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) { return -1; }
    }

    /**
     * The CEO Metrics screen, built from the CEO's own widget board plus the decisions waiting
     * on them — nothing on it is invented, so cards with no source (runway, AI alert, OKR
     * tree, quick-tile sheets) are left out. Demo mode runs a sample CEO board through the
     * same builder, so it shows the same tabs, cards and order as the connected screen.
     */
    public void companyMetrics(DataCallback<com.orphos.mobile.data.model.CompanyMetrics> cb) {
        if (demo()) {
            com.orphos.mobile.data.model.PersonaWidget.Board board = demoBoard("demo/metrics-ceo.json");
            mock(board == null ? null : WidgetScreens.company(OrphosApp.get(), board, MockData.decisions()), cb);
            return;
        }
        metricsBoard((board, failed) -> {
            if (board == null) { cb.onResult(null, true); return; }
            decisions((decisions, stale) ->
                    cb.onResult(WidgetScreens.company(OrphosApp.get(), board, decisions), false));
        });
    }

    /**
     * A role's data-driven work-tab dashboard (see RoleDashboard). Demo mode: the designed
     * CHRO sample. Connected: the role's own widget board, grouped by area.
     */
    public void roleDashboard(String role, DataCallback<com.orphos.mobile.data.model.RoleDashboard> cb) {
        if (demo()) { mock("CHRO".equalsIgnoreCase(role) ? MockData.peopleDashboard() : null, cb); return; }
        personaWidgets((widgets, failed) -> cb.onResult(widgets == null ? null
                : WidgetScreens.role(OrphosApp.get(), widgets), false));
    }

    /**
     * Widget cards {label, value, caption} for the CTO / COO / viewer work tab — the role's
     * own web widget board rather than a fixed per-role list.
     */
    public void roleWidgets(String role, DataCallback<List<String[]>> cb) {
        if (demo()) { mock(roleWidgetsMock(role), cb); return; }
        personaWidgets((widgets, failed) -> {
            List<String[]> out = new java.util.ArrayList<>();
            if (widgets != null) {
                for (com.orphos.mobile.data.model.PersonaWidget w : widgets) {
                    out.add(new String[]{w.label, w.hasValue() ? w.value : "—", w.caption != null ? w.caption : ""});
                }
            }
            cb.onResult(out, widgets == null);
        });
    }

    private List<String[]> roleWidgetsMock(String role) {
        List<String[]> l = new java.util.ArrayList<>();
        if ("CHRO".equalsIgnoreCase(role)) {
            l.add(new String[]{"Headcount", "1,284", "↑ 3%"});
            l.add(new String[]{"Attrition", "8.2%", "↓ 1.1%"});
            l.add(new String[]{"Open roles", "14", ""});
            l.add(new String[]{"Avg. productivity", "78%", "↑ 8%"});
            l.add(new String[]{"Engagement", "82%", "↑ 6%"});
            l.add(new String[]{"Time to hire", "24 days", ""});
        } else if ("COO".equalsIgnoreCase(role)) {
            l.add(new String[]{"On-time delivery", "94%", "↑ 2%"});
            l.add(new String[]{"Open incidents", "3", ""});
            l.add(new String[]{"SLA compliance", "98.6%", "↑ 0.4%"});
            l.add(new String[]{"Capacity utilisation", "87%", ""});
            l.add(new String[]{"Throughput", "1,240 / wk", "↑ 5%"});
            l.add(new String[]{"Budget overruns", "2", ""});
        }
        return l;
    }

    private static double dbl(com.google.gson.JsonObject o, String key) {
        try {
            com.google.gson.JsonElement e = o.get(key);
            return (e != null && !e.isJsonNull()) ? e.getAsDouble() : 0d;
        } catch (Exception ex) { return 0d; }
    }

    private static String money(double amount, String currency) {
        java.text.NumberFormat nf = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.getDefault());
        try { if (currency != null) nf.setCurrency(java.util.Currency.getInstance(currency)); } catch (Exception ignored) { }
        nf.setMaximumFractionDigits(0);
        return nf.format(amount);
    }

    // --- Profile (persona + roles + access grants) ---

    /**
     * Loads GET /api/mobile/profile and populates the central {@link com.orphos.mobile.core.role.AccessState}
     * plus the persisted persona. When {@code applyRoleAsDefault} is true (a fresh login) the
     * mapped executive Role is also written to AppConfig so the role-based UI defaults to the
     * Keycloak persona; on a cold-start refresh it is left untouched so a manual Settings
     * override survives. In demo mode it resolves locally (permissive persona) without a call.
     */
    public void loadProfile(boolean applyRoleAsDefault, DataCallback<Boolean> cb) {
        if (demo()) {
            // Demo keeps the current (permissive) default persona and role.
            OrphosApp.get().accessState().setPersona(config.persona());
            main.post(() -> cb.onResult(true, true));
            return;
        }
        // A profile that arrives after a sign-out belongs to the previous persona — drop it.
        final int epoch = OrphosApp.get().session().epoch();
        api.orphos().mobileProfile().enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (OrphosApp.get().session().epoch() != epoch) {
                    cb.onResult(false, true);
                } else if (r.isSuccessful() && r.body() != null) {
                    apply(r.body());
                    cb.onResult(true, false);
                } else {
                    cb.onResult(false, true);
                }
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(false, true);
            }
            private void apply(com.google.gson.JsonObject body) {
                OrphosApp.get().accessState().applyProfile(body);
                String name = str(body, "name");
                if (name != null && !name.isEmpty()) config.setUserName(name);
                String persona = str(body, "persona");
                if (persona != null && !persona.isEmpty()) {
                    config.setPersona(persona);
                    if (applyRoleAsDefault) {
                        config.setUserRole(com.orphos.mobile.core.role.Role.fromPersona(persona).name());
                    }
                }
            }
        });
    }

    // --- Mobile Access config (Platform Admin → Mobile Access, /api/mobile/config) ---

    /**
     * Loads the per-tenant mobile config and applies it to {@link AppConfig} so the app
     * honours the admin's settings: mobile-access gate, MFA flag, biometric app-lock,
     * alert severities, Busy status-dot thresholds and the refresh interval. In demo mode
     * it applies the built-in defaults locally (no network). Never blocks the UI.
     */
    public void loadConfig(DataCallback<Boolean> cb) {
        if (demo()) { applyConfigDefaults(); main.post(() -> cb.onResult(true, true)); return; }
        api.orphos().mobileConfig().enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override public void onResponse(@NonNull Call<com.google.gson.JsonObject> c,
                                             @NonNull Response<com.google.gson.JsonObject> r) {
                if (r.isSuccessful() && r.body() != null) { applyConfig(r.body()); cb.onResult(true, false); }
                else cb.onResult(false, true);
            }
            @Override public void onFailure(@NonNull Call<com.google.gson.JsonObject> c, @NonNull Throwable t) {
                cb.onResult(false, true);
            }
        });
    }

    /** Demo defaults mirror MobileConfigService.defaults(). */
    private void applyConfigDefaults() {
        config.setMobileAccessEnabled(true);
        config.setMfaEnabled(true);
        config.setAppLockEnabled(true);
        config.setAlertsIncludeHigh(false);
        config.setStatusThresholds(30, 20, 10);
        config.setRefreshMinutes(5);
    }

    private void applyConfig(com.google.gson.JsonObject o) {
        com.google.gson.JsonObject access = obj(o, "access");
        com.google.gson.JsonObject security = obj(o, "security");
        com.google.gson.JsonObject alerts = obj(o, "alerts");
        com.google.gson.JsonObject st = obj(o, "statusThresholds");

        config.setMobileAccessEnabled(bool(access, "enabled", true));
        config.setMfaEnabled(bool(security, "mfaEnabled", true));
        // Biometric flag drives the app lock.
        config.setAppLockEnabled(bool(security, "biometricEnabled", true));
        config.setAlertsIncludeHigh(bool(alerts, "includeHigh", false));
        config.setStatusThresholds(intVal(st, "red", 30), intVal(st, "blue", 20), intVal(st, "green", 10));
        config.setRefreshMinutes(intVal(o, "refreshMinutes", 5));
    }

    private static com.google.gson.JsonObject obj(com.google.gson.JsonObject o, String k) {
        com.google.gson.JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
    private static boolean bool(com.google.gson.JsonObject o, String k, boolean dflt) {
        try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : dflt; }
        catch (Exception e) { return dflt; }
    }
    private static int intVal(com.google.gson.JsonObject o, String k, int dflt) {
        try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : dflt; }
        catch (Exception e) { return dflt; }
    }

    // --- Voice CEO / NL query ---

    public void ask(String question, DataCallback<NlQuery.Response> cb) {
        if (demo()) { mock(MockData.ask(question), cb); return; }
        api.orphos().ask(new NlQuery.Request(question)).enqueue(new Callback<NlQuery.Response>() {
            @Override public void onResponse(@NonNull Call<NlQuery.Response> c, @NonNull Response<NlQuery.Response> r) {
                if (r.isSuccessful() && r.body() != null) cb.onResult(r.body(), false);
                else cb.onResult(errorResponse(r), true);
            }
            @Override public void onFailure(@NonNull Call<NlQuery.Response> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    /**
     * The service's own reason for rejecting a question ({@code { "error": … }}), e.g. no AI
     * provider assigned to the tenant — so the screen can say why instead of "couldn't reach".
     * Null when there is no readable reason.
     */
    private NlQuery.Response errorResponse(Response<?> r) {
        try (okhttp3.ResponseBody body = r.errorBody()) {
            if (body == null) return null;
            NlQuery.Response parsed = gson.fromJson(body.string(), NlQuery.Response.class);
            return parsed != null && parsed.error != null && !parsed.error.isEmpty() ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    // --- AI Co-pilot history (shared with the web dashboard's History ▸ AI tab) ---

    /** Demo mode keeps its sessions in memory only; they are never sent anywhere. */
    private final List<CopilotSession> demoSessions = new java.util.ArrayList<>();

    /** Newest first. Null data = history could not be loaded (offline, or no ai-history grant). */
    public void copilotSessions(DataCallback<List<CopilotSession>> cb) {
        if (demo()) { mock(new java.util.ArrayList<>(demoSessions), cb); return; }
        api.orphos().copilotSessions(100).enqueue(new Callback<CopilotSession.ListResponse>() {
            @Override public void onResponse(@NonNull Call<CopilotSession.ListResponse> c,
                                             @NonNull Response<CopilotSession.ListResponse> r) {
                if (r.isSuccessful() && r.body() != null) {
                    List<CopilotSession> s = r.body().sessions;
                    cb.onResult(s != null ? s : new java.util.ArrayList<>(), false);
                } else {
                    cb.onResult(null, true);
                }
            }
            @Override public void onFailure(@NonNull Call<CopilotSession.ListResponse> c, @NonNull Throwable t) {
                cb.onResult(null, true);
            }
        });
    }

    /** Best-effort upsert, like the web: a failed save must never break the conversation. */
    public void saveCopilotSession(CopilotSession session, DataCallback<Boolean> cb) {
        if (demo()) {
            demoSessions.removeIf(s -> s.id.equals(session.id));
            demoSessions.add(0, session);
            mock(true, cb);
            return;
        }
        api.orphos().saveCopilotSession(session.id, new CopilotSession.Payload(session))
                .enqueue(new Callback<okhttp3.ResponseBody>() {
                    @Override public void onResponse(@NonNull Call<okhttp3.ResponseBody> c,
                                                     @NonNull Response<okhttp3.ResponseBody> r) {
                        if (r.body() != null) r.body().close();
                        cb.onResult(r.isSuccessful(), !r.isSuccessful());
                    }
                    @Override public void onFailure(@NonNull Call<okhttp3.ResponseBody> c, @NonNull Throwable t) {
                        cb.onResult(false, true);
                    }
                });
    }

    public void deleteCopilotSession(String sessionId, DataCallback<Boolean> cb) {
        if (demo()) {
            demoSessions.removeIf(s -> s.id.equals(sessionId));
            mock(true, cb);
            return;
        }
        api.orphos().deleteCopilotSession(sessionId).enqueue(voidCallback(cb));
    }

    public void clearCopilotSessions(DataCallback<Boolean> cb) {
        if (demo()) { demoSessions.clear(); mock(true, cb); return; }
        api.orphos().clearCopilotSessions().enqueue(voidCallback(cb));
    }

    private static Callback<Void> voidCallback(DataCallback<Boolean> cb) {
        return new Callback<Void>() {
            @Override public void onResponse(@NonNull Call<Void> c, @NonNull Response<Void> r) {
                cb.onResult(r.isSuccessful(), !r.isSuccessful());
            }
            @Override public void onFailure(@NonNull Call<Void> c, @NonNull Throwable t) {
                cb.onResult(false, true);
            }
        };
    }
}
