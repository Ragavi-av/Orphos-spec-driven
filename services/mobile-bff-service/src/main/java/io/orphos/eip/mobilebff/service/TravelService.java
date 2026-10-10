package io.orphos.eip.mobilebff.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.orphos.eip.mobilebff.component.DownstreamClient;
import io.orphos.eip.mobilebff.repository.WorkspaceStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-inferred, persistent Travel. Trips are detected from downstream meetings and
 * decisions using the tenant's AI-Admin travel rules ({@link MobileConfigService}) — a
 * From→To route plus a mode keyword — stored once (idempotent by sourceId), and shown in
 * the app's Travel section like Meetings. Fully inferred; there is no user-entered part.
 */
@Service
public class TravelService {

    private static final Logger log = LogManager.getLogger(TravelService.class);
    private static final String TYPE = "Travel";

    // "Bangalore to Delhi", "Pune -> Mumbai", "London – Paris"
    private static final Pattern ROUTE = Pattern.compile(
            "\\b([A-Z][a-zA-Z]{2,})\\s*(?:to|->|→|–|-)\\s*([A-Z][a-zA-Z]{2,})\\b");

    /**
     * How far ahead to read the calendar when inferring trips.
     *
     * mail-insight defaults an absent window to now..+24h, which is the wrong horizon
     * here: a flight is usually booked into a meeting days or weeks out, so the default
     * silently hid every trip worth showing. The window is sent explicitly.
     */
    private static final int INFERENCE_HORIZON_DAYS = 30;

    private final DownstreamClient ds;
    private final WorkspaceStore workspace;
    private final MobileConfigService config;
    private final String mailInsightUrl;
    private final String decisionLogUrl;

    /*
     * There is no built-in demo seed here. Demo trips come from demo calendar entries
     * (scripts/sample-data/SeedUpcomingSchedule.java), which are inferred below like any
     * booking email. That keeps one source of truth for the web rail and the mobile app.
     * The seed that used to live here wrote year-less dates ("Mon, 22 Sep") that fell
     * behind the calendar.
     */
    public TravelService(DownstreamClient ds, WorkspaceStore workspace, MobileConfigService config,
                         @Value("${eip.downstream.mail-insight-url:http://eip-mail-insight:8092}") String mailInsightUrl,
                         @Value("${eip.downstream.decision-log-url:http://eip-decision-log:8086}") String decisionLogUrl) {
        this.ds = ds;
        this.workspace = workspace;
        this.config = config;
        this.mailInsightUrl = mailInsightUrl;
        this.decisionLogUrl = decisionLogUrl;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listAndSync(String tenantId, String ownerSub, String auth) {
        Map<String, Object> cfg = config.get(tenantId);
        Map<String, Object> tc = (Map<String, Object>) cfg.getOrDefault("travel", Map.of());
        if (Boolean.TRUE.equals(tc.getOrDefault("enabled", true))) {
            try {
                infer(tenantId, ownerSub, auth, tc,
                        ds.get(mailInsightUrl, "/api/mail-insight/meetings" + window(), auth, tenantId),
                        "meeting");
                infer(tenantId, ownerSub, auth, tc,
                        ds.get(decisionLogUrl, "/api/decisions", auth, tenantId), "decision");
            } catch (Exception e) {
                log.warn("Travel inference skipped for tenant {}: {}", tenantId, e.getMessage());
            }
        }
        return workspace.list(TYPE, tenantId, ownerSub);
    }

    /** `?from=…&to=…` covering the inference horizon, in the instants mail-insight expects. */
    private static String window() {
        Instant now = Instant.now();
        return "?from=" + now + "&to=" + now.plus(INFERENCE_HORIZON_DAYS, ChronoUnit.DAYS);
    }

    /**
     * The row array inside a downstream answer.
     *
     * The two sources do not agree on shape: decision-log returns a bare array, while
     * mail-insight wraps its rows in {@code {"result":[…],"count":n}}. This used to test
     * {@code isArray()} alone, so every meeting-derived trip was dropped on the floor —
     * silently, since no trips is also what a quiet calendar looks like.
     */
    private static JsonNode rows(JsonNode answer) {
        if (answer == null) return null;
        if (answer.isArray()) return answer;
        for (String field : new String[] { "result", "items", "data" }) {
            JsonNode inner = answer.get(field);
            if (inner != null && inner.isArray()) return inner;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void infer(String tenantId, String ownerSub, String auth, Map<String, Object> tc,
                       JsonNode answer, String kind) {
        JsonNode items = rows(answer);
        if (items == null) return;
        Map<String, Object> modeKeywords = (Map<String, Object>) tc.getOrDefault("modeKeywords", Map.of());
        List<String> keywords = ((List<Object>) tc.getOrDefault("keywords", List.of()))
                .stream().map(String::valueOf).map(String::toLowerCase).toList();

        for (JsonNode it : items) {
            String id = firstNonBlank(text(it, "id"), text(it, "messageId"));
            if (id.isEmpty()) continue;
            String title = firstNonBlank(text(it, "title"), text(it, "subject"));
            String body = title + " " + text(it, "summary") + " " + detail(it, "locationText")
                    + " " + text(it, "rationale");
            String lower = body.toLowerCase();

            boolean travelSignal = keywords.stream().anyMatch(k -> !k.isEmpty() && lower.contains(k));
            Matcher m = ROUTE.matcher(body);
            boolean hasRoute = m.find();
            if (!travelSignal && !hasRoute) continue;

            String from = hasRoute ? m.group(1) : "";
            String to = hasRoute ? m.group(2) : "";
            String mode = "UNSPECIFIED";
            for (Map.Entry<String, Object> e : modeKeywords.entrySet()) {
                if (lower.contains(e.getKey().toLowerCase())) { mode = String.valueOf(e.getValue()); break; }
            }

            Map<String, Object> t = new LinkedHashMap<>();
            t.put("sourceId", kind + "-" + id);
            t.put("source", kind.toUpperCase());
            t.put("sourceRef", id);
            t.put("from", from);
            t.put("to", to);
            // One instant, written to both fields — the rail parses either, and the
            // seeded rows are the only ones that carry a human date and a separate time.
            String startsAt = firstNonBlank(detail(it, "startAt"), text(it, "dueDate"),
                    text(it, "receivedAt"), text(it, "date"), text(it, "createdAt"));
            t.put("travelDate", startsAt);
            t.put("time", startsAt);
            t.put("mode", mode);
            t.put("purpose", title.isEmpty() ? "Travel" : title);
            t.put("confirmationStatus", "TENTATIVE");
            t.put("inferredAt", Instant.now().toEpochMilli());
            workspace.upsertBySource(TYPE, tenantId, ownerSub, t);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
    /**
     * A field of the item's nested {@code meeting} detail, or "".
     *
     * mail-insight puts a meeting's `startAt`, `locationText` and organiser inside a
     * {@code meeting} object (MailInsightDto.MeetingDetail), not at the top level. Read
     * flat, `startAt` came back empty for every meeting and the trip arrived undated.
     * Falls back to the top level so a source that does flatten them still works.
     */
    private static String detail(JsonNode n, String field) {
        JsonNode m = n.get("meeting");
        String nested = m == null || m.isNull() ? "" : text(m, field);
        return nested.isEmpty() ? text(n, field) : nested;
    }

    /** The first value that is neither null nor blank, or "". */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
