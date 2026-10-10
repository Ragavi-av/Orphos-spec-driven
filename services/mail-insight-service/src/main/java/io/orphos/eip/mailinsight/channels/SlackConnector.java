package io.orphos.eip.mailinsight.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mailinsight.graph.GraphItem;
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
 * Reads Slack channel messages via the Slack Web API using a bot token
 * ({@code xoxb-…}) from the connection's credential. Each message is normalised
 * into a {@link GraphItem} tagged {@code sourceType = "SLACK_MESSAGE"} for the
 * existing classify → persist pipeline; raw text is only held transiently in
 * {@code bodyPreview} for classification.
 *
 * <p>The monitored account's {@code externalId} is a Slack conversation (channel)
 * id. Requires the bot to be a member of the channel and to hold the
 * {@code channels:history}/{@code groups:history} + {@code chat:write} scopes.</p>
 */
@Component
public class SlackConnector implements CommChannelConnector {

    private static final Logger log = LogManager.getLogger(SlackConnector.class);

    private final String apiBase;
    private final int maxItems;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SlackConnector(
            @Value("${mail-insight.channels.slack.api-base:https://slack.com/api}") String apiBase,
            @Value("${mail-insight.channels.slack.max-items:100}") int maxItems,
            @Value("${mail-insight.channels.slack.timeout-seconds:30}") int timeoutSeconds) {
        this.apiBase = stripTrailingSlash(apiBase);
        this.maxItems = maxItems;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
    }

    @Override
    public CommChannelKind kind() {
        return CommChannelKind.SLACK;
    }

    @Override
    public ProbeResult test(Descriptor desc) {
        String token = botToken(desc);
        if (token.isBlank()) return ProbeResult.fail("A Slack bot token (xoxb-…) is required");
        try {
            JsonNode root = call("auth.test", "", token);
            if (!root.path("ok").asBoolean(false)) {
                return ProbeResult.fail("Slack auth.test failed: " + root.path("error").asText("unknown"));
            }
            return ProbeResult.ok(0);
        } catch (Exception e) {
            return ProbeResult.fail(rootMessage(e));
        }
    }

    @Override
    public List<GraphItem> listMessages(Descriptor desc, String externalId, int windowDays) {
        String token = botToken(desc);
        if (token.isBlank()) throw new IllegalStateException("Slack bot token is not configured for this connection");
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalStateException("Slack channel id (externalId) is required");
        }
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        String oldest = String.valueOf(since.getEpochSecond());
        String q = "channel=" + enc(externalId) + "&oldest=" + oldest + "&limit=" + maxItems;

        JsonNode root = call("conversations.history", q, token);
        if (!root.path("ok").asBoolean(false)) {
            throw new IllegalStateException("Slack conversations.history failed: "
                    + root.path("error").asText("unknown"));
        }
        List<GraphItem> out = new ArrayList<>();
        for (JsonNode m : root.path("messages")) {
            // Skip channel-join/leave and other system subtypes — no actionable user body.
            String subtype = m.path("subtype").asText("");
            if (!subtype.isBlank() && !"thread_broadcast".equals(subtype)) continue;

            String text = m.path("text").asText("");
            if (text.isBlank()) continue;

            Instant ts = parseSlackTs(m.path("ts").asText(""));
            String sender = m.path("user").asText("");
            if (sender.isBlank()) sender = m.path("username").asText("Slack user");

            out.add(new GraphItem(
                    m.path("ts").asText(""),   // sourceRef = message ts (unique per channel)
                    "SLACK_MESSAGE",
                    firstLine(text),
                    text,
                    sender,
                    ts,
                    false,
                    true,
                    null));
        }
        log.debug("Slack channel {}: {} message(s) in window", externalId, out.size());
        return out;
    }

    @Override
    public void sendMessage(Descriptor desc, String externalId, String text) {
        String token = botToken(desc);
        if (token.isBlank()) throw new IllegalStateException("Slack bot token is not configured for this connection");
        try {
            String form = "channel=" + enc(externalId) + "&text=" + enc(text == null ? "" : text);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat.postMessage"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = mapper.readTree(res.body());
            if (res.statusCode() / 100 != 2 || !root.path("ok").asBoolean(false)) {
                throw new IllegalStateException("Slack chat.postMessage failed: "
                        + root.path("error").asText("HTTP " + res.statusCode()));
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Slack send failed: " + e.getMessage(), e);
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private JsonNode call(String method, String query, String token) {
        try {
            String url = apiBase + "/" + method + (query == null || query.isBlank() ? "" : "?" + query);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("Slack " + method + " returned HTTP " + res.statusCode());
            }
            return mapper.readTree(res.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Slack " + method + " failed: " + e.getMessage(), e);
        }
    }

    private String botToken(Descriptor desc) {
        String json = desc == null ? "" : desc.credentialJson();
        if (json == null || json.isBlank()) return "";
        try {
            return mapper.readTree(json).path("botToken").asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Instant parseSlackTs(String ts) {
        if (ts == null || ts.isBlank()) return null;
        try {
            double epoch = Double.parseDouble(ts);
            return Instant.ofEpochMilli((long) (epoch * 1000));
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
