package io.orphos.eip.mailinsight.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orphos.eip.mailinsight.graph.GraphConnection;
import io.orphos.eip.mailinsight.graph.GraphItem;
import io.orphos.eip.mailinsight.graph.GraphTokenProvider;
import io.orphos.eip.mailinsight.graph.TeamsConnector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Teams channel connector: a first-class {@link CommChannelConnector}
 * over the existing app-only Graph plumbing ({@link TeamsConnector} for reads,
 * {@link GraphTokenProvider} for tokens). Each monitored account's
 * {@code externalId} is {@code "<teamId>/<channelId>"}.
 *
 * <p>Config carries the Azure app registration ({@code directoryId},
 * {@code clientId}, {@code graphBaseUrl}); the credential JSON carries
 * {@code {"clientSecret": …}}. Reads need {@code ChannelMessage.Read.All};
 * sending needs {@code ChannelMessage.Send} — a tenant whose app lacks it gets
 * an actionable error rather than a silent drop.</p>
 */
@Component
public class TeamsChannelConnector implements CommChannelConnector {

    private static final Logger log = LogManager.getLogger(TeamsChannelConnector.class);

    private final TeamsConnector teams;
    private final GraphTokenProvider tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public TeamsChannelConnector(TeamsConnector teams, GraphTokenProvider tokens) {
        this.teams = teams;
        this.tokens = tokens;
    }

    @Override
    public CommChannelKind kind() {
        return CommChannelKind.MS_TEAMS;
    }

    @Override
    public ProbeResult test(Descriptor desc) {
        GraphConnection conn = connection(desc);
        if (!conn.isComplete()) {
            return ProbeResult.fail("directoryId, clientId and a client secret are required for Microsoft Teams");
        }
        try {
            tokens.token(conn);   // acquires an app-only token; throws on bad creds
            return ProbeResult.ok(0);
        } catch (Exception e) {
            return ProbeResult.fail(rootMessage(e));
        }
    }

    @Override
    public List<GraphItem> listMessages(Descriptor desc, String externalId, int windowDays) {
        GraphConnection conn = connection(desc);
        if (!conn.isComplete()) {
            throw new IllegalStateException("Microsoft Teams connection is incomplete — re-enter the app "
                    + "client secret for this connection and save.");
        }
        String[] tc = splitExternalId(externalId);
        return teams.listChannelMessages(conn, tc[0], tc[1], windowDays);
    }

    @Override
    public void sendMessage(Descriptor desc, String externalId, String text) {
        GraphConnection conn = connection(desc);
        if (!conn.isComplete()) {
            throw new IllegalStateException("Microsoft Teams connection is incomplete — cannot send.");
        }
        String[] tc = splitExternalId(externalId);
        String url = base(conn) + "/teams/" + enc(tc[0]) + "/channels/" + enc(tc[1]) + "/messages";
        try {
            ObjectNode body = mapper.createObjectNode();
            body.putObject("body").put("content", text == null ? "" : text);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + tokens.token(conn))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("Teams send returned HTTP " + res.statusCode()
                        + " (the app registration may lack the ChannelMessage.Send permission)");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Teams send failed: " + e.getMessage(), e);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private GraphConnection connection(Descriptor desc) {
        Map<String, Object> cfg = desc == null || desc.config() == null ? Map.of() : desc.config();
        return new GraphConnection(
                str(cfg, "directoryId"),
                str(cfg, "clientId"),
                clientSecret(desc),
                str(cfg, "graphBaseUrl"));
    }

    private String clientSecret(Descriptor desc) {
        String json = desc == null ? "" : desc.credentialJson();
        if (json == null || json.isBlank()) return "";
        try {
            return mapper.readTree(json).path("clientSecret").asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** externalId = "teamId/channelId"; the channelId itself contains no slash. */
    private static String[] splitExternalId(String externalId) {
        if (externalId == null || !externalId.contains("/")) {
            throw new IllegalStateException("Teams channel account must be identified as \"teamId/channelId\"");
        }
        int slash = externalId.indexOf('/');
        String teamId = externalId.substring(0, slash).trim();
        String channelId = externalId.substring(slash + 1).trim();
        if (teamId.isBlank() || channelId.isBlank()) {
            throw new IllegalStateException("Teams channel account must be \"teamId/channelId\" (both non-blank)");
        }
        return new String[]{teamId, channelId};
    }

    private static String base(GraphConnection conn) {
        String b = conn.graphBaseUrl();
        if (b == null || b.isBlank()) b = "https://graph.microsoft.com/v1.0";
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
