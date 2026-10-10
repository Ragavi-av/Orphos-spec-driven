package io.orphos.eip.voice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks platform-registry whether voice is enabled for a given tenant.
 *
 * <p>The gate is owned by the registry, not by this service, so that the platform
 * admin's switch is the single source of truth and survives this container being
 * restarted or rebuilt. Answers are cached briefly per tenant: the status
 * endpoint is polled by every open composer, and an un-cached lookup would put a
 * registry round-trip on each one.</p>
 *
 * <p>Fails CLOSED. If the registry cannot be reached the answer is "not enabled",
 * which hides the dictation gesture rather than offering a control that would
 * then fail — and, because the caller's own authorization is what is being
 * checked, an unreachable authority must never be read as permission.</p>
 */
@Service
public class VoiceFeatureGate {

    private static final Logger log = LogManager.getLogger(VoiceFeatureGate.class);
    private static final Duration TTL = Duration.ofSeconds(30);

    private record Cached(boolean enabled, Instant at) {}

    private final String registryUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public VoiceFeatureGate(
            @Value("${voice.registry.url:http://eip-platform-registry:8093}") String registryUrl) {
        this.registryUrl = registryUrl == null ? "" : registryUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * @param bearer       the CALLER's Authorization header, forwarded verbatim — the
     *                     registry resolves the tenant from that token, so this service
     *                     never has to trust a tenant id supplied by the browser
     * @param tenantHeader the caller's X-Tenant-Id, forwarded for the platform-admin
     *                     acting-on-tenant case where the JWT carries no tenant claim
     */
    public boolean isEnabled(String bearer, String tenantHeader) {
        if (registryUrl.isEmpty() || bearer == null || bearer.isBlank()) return false;

        String cacheKey = (tenantHeader == null ? "" : tenantHeader) + '|' + bearer.hashCode();
        Cached hit = cache.get(cacheKey);
        if (hit != null && Duration.between(hit.at(), Instant.now()).compareTo(TTL) < 0) {
            return hit.enabled();
        }

        boolean enabled = false;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(registryUrl + "/api/platform/features/voice/enabled"))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", bearer)
                    .GET();
            if (tenantHeader != null && !tenantHeader.isBlank()) {
                b.header("X-Tenant-Id", tenantHeader);
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                enabled = mapper.readTree(res.body()).path("enabled").asBoolean(false);
                // The registry echoes the tenant it resolved; logging it is the only way
                // to tell "this tenant is not enabled" apart from "a DIFFERENT tenant was
                // resolved than the one the admin enabled".
                log.info("Voice gate answer: {}", res.body());
            } else {
                log.warn("Registry voice gate returned HTTP {} body={}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            log.debug("Registry voice gate unreachable ({}) — treating voice as disabled", e.getMessage());
        }

        cache.put(cacheKey, new Cached(enabled, Instant.now()));
        return enabled;
    }

    /** Drops every cached answer — called after an admin flips the switch. */
    public void invalidate() {
        cache.clear();
    }
}
