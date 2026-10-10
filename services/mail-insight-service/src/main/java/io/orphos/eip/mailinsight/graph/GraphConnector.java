package io.orphos.eip.mailinsight.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads mail and calendar items for a mailbox via the Microsoft Graph REST API
 * using an app-only token. Returns normalized {@link GraphItem}s; never persists
 * or logs raw bodies/addresses.
 */
@Component
public class GraphConnector {

    private static final Logger log = LogManager.getLogger(GraphConnector.class);

    private final GraphTokenProvider tokens;
    private final int maxItems;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GraphConnector(
            GraphTokenProvider tokens,
            @Value("${graph.max-items:100}") int maxItems,
            @Value("${graph.timeout-seconds:30}") int timeoutSeconds) {
        this.tokens = tokens;
        this.maxItems = maxItems;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
    }

    /** Messages received within the trailing {@code windowDays} window. */
    public List<GraphItem> listMessages(GraphConnection conn, String mailbox, int windowDays) {
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        String filter = "receivedDateTime ge " + since.toString();
        String url = base(conn) + "/users/" + enc(mailbox) + "/messages"
                + "?$select=id,subject,bodyPreview,from,toRecipients,importance,receivedDateTime"
                + "&$top=" + maxItems
                + "&$orderby=receivedDateTime%20desc"
                + "&$filter=" + enc(filter);

        JsonNode value = get(conn, url, mailbox);
        List<GraphItem> out = new ArrayList<>();
        for (JsonNode m : value) {
            out.add(new GraphItem(
                    m.path("id").asText(""),
                    "EMAIL",
                    m.path("subject").asText(""),
                    m.path("bodyPreview").asText(""),
                    m.path("from").path("emailAddress").path("name").asText(""),
                    parseInstant(m.path("receivedDateTime").asText("")),
                    "high".equalsIgnoreCase(m.path("importance").asText("")),
                    isDirectRecipient(m, mailbox),
                    null));
        }
        return out;
    }

    /** Calendar events spanning the window (recent + upcoming) for due-date hints. */
    public List<GraphItem> listEvents(GraphConnection conn, String mailbox, int windowDays) {
        Instant start = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        Instant end = Instant.now().plus(windowDays, ChronoUnit.DAYS);
        String url = base(conn) + "/users/" + enc(mailbox) + "/calendarView"
                + "?startDateTime=" + enc(start.toString())
                + "&endDateTime=" + enc(end.toString())
                + "&$select=id,subject,bodyPreview,organizer,start,end,location,"
                + "isOnlineMeeting,onlineMeeting,attendees"
                + "&$top=" + maxItems
                + "&$orderby=start/dateTime";

        JsonNode value = get(conn, url, mailbox);
        List<GraphItem> out = new ArrayList<>();
        for (JsonNode e : value) {
            JsonNode attendees = e.path("attendees");
            int attendeeCount = attendees.isArray() ? attendees.size() : 0;
            boolean isMeeting = e.path("isOnlineMeeting").asBoolean(false) || attendeeCount > 1;
            Instant startAt = parseGraphDateTime(e.path("start"));
            GraphItem.MeetingInfo meeting = new GraphItem.MeetingInfo(
                    parseGraphDateTime(e.path("end")),
                    attendeesJson(attendees),
                    attendeeCount,
                    e.path("location").path("displayName").asText(""),
                    e.path("onlineMeeting").path("joinUrl").asText(""));
            out.add(new GraphItem(
                    e.path("id").asText(""),
                    isMeeting ? "MEETING" : "EVENT",
                    e.path("subject").asText(""),
                    e.path("bodyPreview").asText(""),
                    e.path("organizer").path("emailAddress").path("name").asText(""),
                    startAt,                 // treat event start as the "received"/relevant time
                    false,
                    true,
                    startAt,
                    meeting));
        }
        return out;
    }

    /**
     * Builds [{"displayName":…,"response":…}] from Graph attendees — display name and
     * response status only, NEVER the email address (PII-minimising).
     */
    private String attendeesJson(JsonNode attendees) {
        if (!attendees.isArray() || attendees.isEmpty()) return "[]";
        var arr = mapper.createArrayNode();
        for (JsonNode a : attendees) {
            var o = mapper.createObjectNode();
            o.put("displayName", a.path("emailAddress").path("name").asText(""));
            o.put("response", a.path("status").path("response").asText(""));
            arr.add(o);
        }
        try {
            return mapper.writeValueAsString(arr);
        } catch (Exception ex) {
            return "[]";
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private JsonNode get(GraphConnection conn, String url, String mailbox) {
        String token = tokens.token(conn);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new GraphException("Graph read for mailbox failed: " + res.statusCode()
                        + " " + truncate(res.body()));
            }
            JsonNode value = mapper.readTree(res.body()).path("value");
            return value.isArray() ? value : mapper.createArrayNode();
        } catch (GraphException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphException("Graph read failed: " + e.getMessage(), e);
        }
    }

    private static String base(GraphConnection conn) {
        String b = conn.graphBaseUrl();
        if (b == null || b.isBlank()) b = "https://graph.microsoft.com/v1.0";
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static boolean isDirectRecipient(JsonNode message, String mailbox) {
        JsonNode to = message.path("toRecipients");
        if (!to.isArray()) return true;
        for (JsonNode r : to) {
            String addr = r.path("emailAddress").path("address").asText("");
            if (addr.equalsIgnoreCase(mailbox)) return true;
        }
        return false;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private static Instant parseGraphDateTime(JsonNode startNode) {
        // Graph returns { "dateTime": "2026-06-15T09:00:00.0000000", "timeZone": "UTC" }
        String dt = startNode.path("dateTime").asText("");
        if (dt.isBlank()) return null;
        try {
            // Normalise to an instant assuming UTC when no offset is present.
            String iso = dt.contains("Z") || dt.contains("+") ? dt : dt + "Z";
            return Instant.parse(iso.length() > 24 ? iso.substring(0, 19) + "Z" : iso);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
