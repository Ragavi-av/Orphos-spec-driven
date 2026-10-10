package io.orphos.eip.dataingestion.component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.dataingestion.vo.ErpConfig;
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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Acquires OAuth2 access tokens for enterprise-system sources using the
 * <b>refresh-token</b> grant (Zoho and OAuth2 REST APIs in general). Modeled on
 * mail-insight's {@code GraphTokenProvider}: a small {@link HttpClient} plus a
 * per-source token cache holding the bearer until shortly before it expires, so
 * an extraction sweep across many pages reuses one token.
 *
 * <p>The refresh token and client secret are never held here — they arrive as a
 * short-lived JSON bundle ({@link OAuthSecret}) that the caller resolves from the
 * vault immediately before each acquisition, and are used only to mint the
 * access token.</p>
 */
@Component
public class ErpTokenProvider {

    private static final Logger log = LogManager.getLogger(ErpTokenProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    /** The two OAuth secrets, stored together as one vault value ({@code IngestDataSource.credentialRef}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OAuthSecret(String clientSecret, String refreshToken) {
        public static OAuthSecret parse(String json) {
            if (json == null || json.isBlank()) return new OAuthSecret("", "");
            try {
                return MAPPER.readValue(json, OAuthSecret.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("OAuth secret must be JSON {clientSecret, refreshToken}");
            }
        }
        public String toJson() {
            try { return MAPPER.writeValueAsString(this); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    /**
     * Returns a bearer token for the source, acquiring (and caching) one if the
     * cached token is missing or within 60s of expiry.
     *
     * @param cacheKey a stable per-source key (e.g. the data-source id); when
     *                 blank the token is acquired without caching (ad-hoc test).
     * @param secretJson the resolved vault value: JSON {@code {clientSecret, refreshToken}}.
     */
    public String token(String cacheKey, ErpConfig cfg, String secretJson) {
        boolean cacheable = cacheKey != null && !cacheKey.isBlank();
        if (cacheable) {
            CachedToken cached = cache.get(cacheKey);
            if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
                return cached.value();
            }
        }
        CachedToken fresh = acquire(cfg, OAuthSecret.parse(secretJson));
        if (cacheable) cache.put(cacheKey, fresh);
        return fresh.value();
    }

    /** Drop a cached token — call when a source's credential is rotated or deleted. */
    public void evict(String cacheKey) {
        if (cacheKey != null) cache.remove(cacheKey);
    }

    private CachedToken acquire(ErpConfig cfg, OAuthSecret secret) {
        String url = tokenUrl(cfg);
        if (secret.refreshToken() == null || secret.refreshToken().isBlank()) {
            throw new IllegalStateException("OAuth2 source has no refresh token — cannot acquire an access token");
        }
        StringBuilder form = new StringBuilder()
                .append("grant_type=refresh_token")
                .append("&refresh_token=").append(enc(secret.refreshToken()))
                .append("&client_id=").append(enc(cfg.clientId()))
                .append("&client_secret=").append(enc(secret.clientSecret()));
        if (cfg.scope() != null && !cfg.scope().isBlank()) {
            form.append("&scope=").append(enc(cfg.scope()));
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("OAuth token request failed: HTTP " + res.statusCode()
                        + " " + truncate(res.body()));
            }
            JsonNode root = MAPPER.readTree(res.body());
            String accessToken = root.path("access_token").asText("");
            long expiresIn = root.path("expires_in").asLong(3600);
            if (accessToken.isEmpty()) {
                // Zoho returns 200 with {"error":"invalid_code"} on a bad refresh token.
                String err = root.path("error").asText("");
                throw new IllegalStateException("OAuth token response had no access_token"
                        + (err.isEmpty() ? "" : " (" + err + ")"));
            }
            return new CachedToken(accessToken, Instant.now().plusSeconds(expiresIn));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OAuth token acquisition failed: " + e.getMessage(), e);
        }
    }

    /** Explicit tokenUrl wins; otherwise derive Zoho's accounts endpoint from the data center. */
    static String tokenUrl(ErpConfig cfg) {
        if (cfg.tokenUrl() != null && !cfg.tokenUrl().isBlank()) return cfg.tokenUrl().trim();
        if (ErpConfig.VENDOR_ZOHO.equalsIgnoreCase(cfg.erpVendor())) {
            String dc = (cfg.dataCenter() == null || cfg.dataCenter().isBlank()) ? "com" : cfg.dataCenter().trim();
            return "https://accounts.zoho." + dc + "/oauth/v2/token";
        }
        throw new IllegalStateException("OAuth2 source has no tokenUrl and no Zoho data center to derive one from");
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private record CachedToken(String value, Instant expiresAt) {}
}
