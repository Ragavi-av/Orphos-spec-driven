package io.orphos.eip.admin.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Fetches the current tenant's dynamic (data-onboarded) widget ids from
 * platform-core-service's {@code GET /api/dashboard/widgets}, so a saved
 * layout can include data-driven widgets alongside the static catalog.
 *
 * <p>Widgets-service was carved out of the former admin-service, where this
 * was an in-process call to {@code TenantDataRegistry} (onboarding package,
 * now in platform-core-service). Since the two are separate services, this
 * forwards the caller's own JWT to platform-core-service's already-authenticated
 * endpoint rather than introducing a new internal-token flow — the endpoint
 * only requires a valid JWT (no special role), matching what any authenticated
 * tenant user could already call directly.</p>
 *
 * <p>Best-effort: on any failure (platform-core-service unreachable, no
 * onboarded data yet, no bound JWT), returns an empty set so callers fall
 * back to the static catalog — mirroring the original in-process code's
 * {@code catch (Exception ignored)} behavior.</p>
 */
@Component
public class OnboardingWidgetsClient {

    private static final Logger log = LogManager.getLogger(OnboardingWidgetsClient.class);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OnboardingWidgetsClient(
            @Value("${eip.onboarding.widgets-url:http://eip-platform-core:8090}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public Set<String> dynamicWidgetIds() {
        String token = currentJwtTokenValue();
        if (token == null) return Set.of();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/dashboard/widgets"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return Set.of();
            JsonNode arr = mapper.readTree(res.body());
            Set<String> ids = new LinkedHashSet<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String id = n.path("id").asText(null);
                    if (id != null && !id.isBlank()) ids.add(id);
                }
            }
            return ids;
        } catch (Exception e) {
            log.debug("Could not fetch dynamic widget ids from platform-core-service: {}", e.getMessage());
            return Set.of();
        }
    }

    private static String currentJwtTokenValue() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getTokenValue();
        }
        return null;
    }
}
