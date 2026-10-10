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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Acquires Microsoft Graph access tokens using the OAuth2 client-credentials
 * (app-only) flow: {@code POST {login}/{directoryId}/oauth2/v2.0/token}.
 *
 * Tokens are cached per (directoryId, clientId) until shortly before expiry so a
 * scan sweep across many mailboxes reuses one token.
 */
@Component
public class GraphTokenProvider {

    private static final Logger log = LogManager.getLogger(GraphTokenProvider.class);

    private final String loginBase;
    private final String scope;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public GraphTokenProvider(
            @Value("${graph.login-base:https://login.microsoftonline.com}") String loginBase,
            @Value("${graph.scope:https://graph.microsoft.com/.default}") String scope,
            @Value("${graph.timeout-seconds:30}") int timeoutSeconds) {
        this.loginBase = stripTrailingSlash(loginBase);
        this.scope = scope;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
    }

    /** Returns a bearer token for the connection, acquiring one if not cached. */
    public String token(GraphConnection conn) {
        String key = conn.directoryId() + "|" + conn.clientId();
        CachedToken cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return cached.value();
        }
        CachedToken fresh = acquire(conn);
        cache.put(key, fresh);
        return fresh.value();
    }

    private CachedToken acquire(GraphConnection conn) {
        String form = "client_id=" + enc(conn.clientId())
                + "&scope=" + enc(scope)
                + "&client_secret=" + enc(conn.clientSecret())
                + "&grant_type=client_credentials";
        String url = loginBase + "/" + conn.directoryId() + "/oauth2/v2.0/token";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new GraphException("Graph token request failed: " + res.statusCode()
                        + " " + truncate(res.body()));
            }
            JsonNode root = mapper.readTree(res.body());
            String accessToken = root.path("access_token").asText("");
            long expiresIn = root.path("expires_in").asLong(3600);
            if (accessToken.isEmpty()) {
                throw new GraphException("Graph token response had no access_token");
            }
            return new CachedToken(accessToken, Instant.now().plusSeconds(expiresIn));
        } catch (GraphException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphException("Graph token acquisition failed: " + e.getMessage(), e);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private record CachedToken(String value, Instant expiresAt) {}
}
