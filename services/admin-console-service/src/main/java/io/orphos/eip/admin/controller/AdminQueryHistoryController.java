package io.orphos.eip.admin.controller;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Surfaces AI copilot usage history from the {@code AiUsage} ArcadeDB document
 * type (written by nl-query-service's {@code AiUsageRecorder}).
 *
 * <p>Each row's {@code userSub} may be a Keycloak UUID (from older records) or
 * a human-readable {@code preferred_username} / email (from records written
 * after the display-name fix). This controller resolves UUIDs to display names
 * on-the-fly via the Keycloak Admin REST API and caches results for the
 * lifetime of the process so Keycloak is not queried on every page load.</p>
 */
@RestController
@RequestMapping("/api/admin/query-history")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class AdminQueryHistoryController {

    private static final Logger log = LogManager.getLogger(AdminQueryHistoryController.class);
    private static final int MAX_LIMIT = 100;

    /** Simple regex for a lowercase UUID — used to detect raw Keycloak sub values. */
    private static final java.util.regex.Pattern UUID_RE =
        java.util.regex.Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private final ArcadeDbTemplate arcade;
    private final RestTemplate rest;

    /** UUID → display name cache (process-scoped, intentionally unbounded for MVP). */
    private final ConcurrentHashMap<String, String> userCache = new ConcurrentHashMap<>();

    @Value("${eip.security.internal-issuer-base:http://eip-keycloak:8080}")
    private String keycloakInternalBase;

    @Value("${KEYCLOAK_ADMIN_USER:admin}")
    private String keycloakAdminUser;

    @Value("${KEYCLOAK_ADMIN_PASSWORD:}")
    private String keycloakAdminPassword;

    public AdminQueryHistoryController(ArcadeDbTemplate arcade, RestTemplate rest) {
        this.arcade = arcade;
        this.rest = rest;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> recent(
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) String userId) {

        int cap = Math.min(Math.max(1, limit), MAX_LIMIT);
        UUID tenantId = TenantContextHolder.current()
            .map(ctx -> ctx.tenantId())
            .orElse(null);

        String sql;
        Map<String, Object> params;

        if (tenantId != null && userId != null && !userId.isBlank()) {
            sql = "SELECT tenantId, userSub, providerId, modelName, " +
                  "promptChars, responseChars, success, latencyMs, errorClass, timestamp " +
                  "FROM AiUsage WHERE tenantId = :tenantId AND userSub = :userSub " +
                  "ORDER BY timestamp DESC LIMIT :limit";
            params = Map.of("tenantId", tenantId.toString(), "userSub", userId, "limit", cap);
        } else if (tenantId != null) {
            sql = "SELECT tenantId, userSub, providerId, modelName, " +
                  "promptChars, responseChars, success, latencyMs, errorClass, timestamp " +
                  "FROM AiUsage WHERE tenantId = :tenantId " +
                  "ORDER BY timestamp DESC LIMIT :limit";
            params = Map.of("tenantId", tenantId.toString(), "limit", cap);
        } else {
            sql = "SELECT tenantId, userSub, providerId, modelName, " +
                  "promptChars, responseChars, success, latencyMs, errorClass, timestamp " +
                  "FROM AiUsage ORDER BY timestamp DESC LIMIT :limit";
            params = Map.of("limit", cap);
        }

        try {
            List<Map<String, Object>> rows = arcade.query(sql, params);
            return ResponseEntity.ok(enrichWithDisplayNames(rows, tenantId));
        } catch (ArcadeDbTemplate.ArcadeDbException e) {
            if (e.getMessage() != null && e.getMessage().contains("was not found")) {
                log.debug("AiUsage type not found — no copilot queries recorded yet");
                return ResponseEntity.ok(List.of());
            }
            log.error("Failed to load AI usage history", e);
            return ResponseEntity.internalServerError().build();
        } catch (Exception e) {
            log.error("Failed to load AI usage history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── display-name resolution ───────────────────────────────────────────────

    /**
     * For each row whose {@code userSub} is a UUID, resolves it to
     * {@code preferred_username} (or email) via Keycloak and adds a
     * {@code userDisplay} field. Rows that already have a human-readable
     * identifier (contain {@code @} or are not UUID-shaped) pass through
     * with {@code userDisplay == userSub}.
     */
    private List<Map<String, Object>> enrichWithDisplayNames(
            List<Map<String, Object>> rows, UUID tenantId) {

        if (rows.isEmpty()) return rows;

        // Collect unique UUIDs that need resolution
        List<String> uuidsToResolve = rows.stream()
            .map(r -> (String) r.get("userSub"))
            .filter(s -> s != null && isUuid(s) && !userCache.containsKey(s))
            .distinct()
            .toList();

        if (!uuidsToResolve.isEmpty()) {
            String realm = resolveRealm(tenantId);
            String token = getAdminToken();
            if (token != null) {
                for (String uuid : uuidsToResolve) {
                    String display = resolveUser(token, realm, uuid);
                    if (display != null) userCache.put(uuid, display);
                }
            }
        }

        List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String sub = (String) row.get("userSub");
            String display = sub == null ? null
                : isUuid(sub) ? userCache.getOrDefault(sub, shortUuid(sub))
                : sub;
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.put("userDisplay", display != null ? display : "—");
            enriched.add(out);
        }
        return enriched;
    }

    private boolean isUuid(String s) {
        return UUID_RE.matcher(s).matches();
    }

    /** First 8 chars of UUID — readable fallback when Keycloak lookup fails. */
    private String shortUuid(String uuid) {
        return uuid.length() >= 8 ? uuid.substring(0, 8) + "…" : uuid;
    }

    private String resolveRealm(UUID tenantId) {
        // For now use the demo realm; once per-tenant realm mapping is wired
        // through TenantService this should look up the tenant's realm name.
        return "eip";
    }

    @SuppressWarnings("unchecked")
    private String getAdminToken() {
        if (keycloakAdminPassword == null || keycloakAdminPassword.isBlank()) return null;
        try {
            String tokenUri = keycloakInternalBase + "/realms/master/protocol/openid-connect/token";
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "password");
            form.add("client_id",  "admin-cli");
            form.add("username",   keycloakAdminUser);
            form.add("password",   keycloakAdminPassword);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            Map<String, Object> resp = rest.postForObject(tokenUri,
                new HttpEntity<>(form, h), Map.class);
            return resp != null ? (String) resp.get("access_token") : null;
        } catch (Exception e) {
            log.debug("Could not obtain Keycloak admin token for user resolution: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveUser(String token, String realm, String uuid) {
        try {
            String uri = keycloakInternalBase + "/admin/realms/" + realm + "/users/" + uuid;
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(token);
            ResponseEntity<Map> resp = rest.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(h), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body == null) return null;
            // Prefer preferred_username (usually the login email), then email, then firstName+lastName
            for (String key : new String[]{"username", "email"}) {
                Object val = body.get(key);
                if (val instanceof String s && !s.isBlank()) return s;
            }
            String first = (String) body.get("firstName");
            String last  = (String) body.get("lastName");
            if (first != null || last != null) {
                return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
            }
            return null;
        } catch (Exception e) {
            log.debug("Could not resolve Keycloak user {} in realm {}: {}", uuid, realm, e.getMessage());
            return null;
        }
    }
}
