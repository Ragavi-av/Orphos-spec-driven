package io.orphos.eip.nlquery.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.nlquery.util.TrustProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Reads the current tenant's deterministic derived facts from
 * okg-reasoning-service ({@code GET /api/okg-reasoning/insights}) so the trust
 * layer can cross-check an LLM answer against ground truth the reasoner proved
 * (overdue / blocked issues, overloaded people).
 *
 * <p>Auth: the reasoning endpoint requires a Bearer JWT (there is no
 * internal-token path), so we forward the caller's own JWT taken from the
 * Spring {@code SecurityContext}. This works because {@code /ask} is always a
 * user-initiated, JWT-authenticated request. If no JWT is bound (e.g. an
 * internal-token completion call), the cross-check simply yields nothing.</p>
 *
 * <p>Strictly best-effort: any failure returns an empty result and the trust
 * layer proceeds without the deterministic signal.</p>
 */
@Component
public class OkgInsightsClient {

    private static final Logger log = LogManager.getLogger(OkgInsightsClient.class);

    private final TrustProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile HttpClient http;

    public OkgInsightsClient(TrustProperties props) {
        this.props = props;
    }

    /** A compact, human-readable line per derived fact type, e.g. "3 overdue issue(s)". */
    public record DerivedFacts(List<String> statements) {
        public boolean isEmpty() { return statements == null || statements.isEmpty(); }
    }

    private HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                }
                client = http;
            }
        }
        return client;
    }

    /**
     * Fetches the derived-fact counts for the current tenant, or an empty
     * result when the cross-check is disabled, no JWT is available, or the
     * reasoning service is unreachable.
     */
    public DerivedFacts fetch() {
        if (!props.isOwlCheckEnabled()) return new DerivedFacts(List.of());
        Optional<String> bearer = currentBearer();
        if (bearer.isEmpty()) {
            log.debug("OWL cross-check skipped: no Bearer JWT bound to the current request");
            return new DerivedFacts(List.of());
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(props.getOkgBaseUrl()) + "/api/okg-reasoning/insights"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + bearer.get())
                    .GET()
                    .build();
            HttpResponse<String> res = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                log.debug("okg-reasoning insights returned {}", res.statusCode());
                return new DerivedFacts(List.of());
            }
            return summarise(mapper.readTree(res.body()));
        } catch (Exception e) {
            log.debug("OWL cross-check unavailable: {}", e.getMessage());
            return new DerivedFacts(List.of());
        }
    }

    /**
     * The insights endpoint returns {@code {type: [ {..}, .. ]}}. We only need
     * the count per type for a contradiction check, so collapse each non-empty
     * bucket into a plain-English count line.
     */
    private DerivedFacts summarise(JsonNode root) {
        List<String> out = new ArrayList<>();
        if (root == null || !root.isObject()) return new DerivedFacts(out);
        Iterator<String> types = root.fieldNames();
        while (types.hasNext()) {
            String type = types.next();
            JsonNode arr = root.get(type);
            int count = (arr != null && arr.isArray()) ? arr.size() : 0;
            if (count > 0) {
                out.add(count + " " + humanType(type) + (count == 1 ? "" : "s"));
            }
        }
        return new DerivedFacts(out);
    }

    /** OverdueIssue → "overdue issue", OverloadedPerson → "overloaded person", etc. */
    private static String humanType(String type) {
        String spaced = type.replaceAll("(?<=[a-z])(?=[A-Z])", " ").toLowerCase();
        return spaced.replace("issue", "issue").replace("person", "person");
    }

    private static Optional<String> currentBearer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            if (jwt != null && jwt.getTokenValue() != null && !jwt.getTokenValue().isBlank()) {
                return Optional.of(jwt.getTokenValue());
            }
        }
        return Optional.empty();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
