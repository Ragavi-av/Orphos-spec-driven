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
 * Reads Microsoft Teams channel messages via the Graph REST API using the same
 * app-only token as {@link GraphConnector} (Teams is also Microsoft Graph). Each
 * message is normalised into a {@link GraphItem} tagged {@code sourceType =
 * "TEAMS_MESSAGE"} for the existing classify → persist pipeline; raw bodies are
 * only held transiently in {@code bodyPreview} for classification.
 *
 * <p>Requires the app registration to hold the {@code ChannelMessage.Read.All}
 * application permission.</p>
 */
@Component
public class TeamsConnector {

    private static final Logger log = LogManager.getLogger(TeamsConnector.class);

    private final GraphTokenProvider tokens;
    private final int maxItems;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TeamsConnector(
            GraphTokenProvider tokens,
            @Value("${graph.max-items:100}") int maxItems,
            @Value("${graph.timeout-seconds:30}") int timeoutSeconds) {
        this.tokens = tokens;
        this.maxItems = maxItems;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
    }

    /**
     * Channel messages created within the trailing {@code windowDays} window.
     * Graph does not support server-side {@code $filter} on channel messages, so
     * we fetch the most recent page and filter by createdDateTime client-side.
     */
    public List<GraphItem> listChannelMessages(GraphConnection conn, String teamId, String channelId, int windowDays) {
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        String url = base(conn) + "/teams/" + enc(teamId) + "/channels/" + enc(channelId) + "/messages"
                + "?$top=" + maxItems;

        JsonNode value = get(conn, url);
        List<GraphItem> out = new ArrayList<>();
        for (JsonNode m : value) {
            // Skip system messages (joins, renames…) — they have no user body.
            if (!m.path("messageType").asText("message").equals("message")) continue;

            Instant created = parseInstant(m.path("createdDateTime").asText(""));
            if (created != null && created.isBefore(since)) continue;

            String body = plainText(m.path("body").path("content").asText(""));
            if (body.isBlank()) continue;

            String sender = m.path("from").path("user").path("displayName").asText("");
            String subject = m.path("subject").asText("");
            if (subject.isBlank()) subject = firstLine(body);
            boolean urgent = "high".equalsIgnoreCase(m.path("importance").asText(""))
                    || "urgent".equalsIgnoreCase(m.path("importance").asText(""));

            out.add(new GraphItem(
                    m.path("id").asText(""),
                    "TEAMS_MESSAGE",
                    subject,
                    body,
                    sender,
                    created,
                    urgent,
                    true,
                    null));
        }
        log.debug("Teams channel {}/{}: {} message(s) in window", teamId, channelId, out.size());
        return out;
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private JsonNode get(GraphConnection conn, String url) {
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
                throw new GraphException("Graph read for Teams channel failed: " + res.statusCode()
                        + " " + truncate(res.body()));
            }
            JsonNode value = mapper.readTree(res.body()).path("value");
            return value.isArray() ? value : mapper.createArrayNode();
        } catch (GraphException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphException("Graph Teams read failed: " + e.getMessage(), e);
        }
    }

    private static String base(GraphConnection conn) {
        String b = conn.graphBaseUrl();
        if (b == null || b.isBlank()) b = "https://graph.microsoft.com/v1.0";
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** Teams message bodies are usually HTML — reduce to plain text for classification. */
    private static String plainText(String content) {
        if (content == null || content.isBlank()) return "";
        // Dependency-free strip: drop tags, unescape the few common entities, collapse whitespace.
        String text = content
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", " ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
                   .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return text.replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
