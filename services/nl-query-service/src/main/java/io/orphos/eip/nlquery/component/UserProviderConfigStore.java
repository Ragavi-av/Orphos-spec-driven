package io.orphos.eip.nlquery.component;

import io.orphos.eip.nlquery.service.AiUsageRecorder;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable, tenant-user-scoped record of each user's personal AI-provider setup.
 *
 * <p>The actual API key lives only in OpenBao (see {@link UserApiKeyStore}).
 * This store keeps the <em>non-secret</em> metadata in ArcadeDB — one
 * {@code AiProviderConfig} document per {@code (tenantId, userSub, providerId)}
 * — so that:</p>
 * <ul>
 *   <li>the per-user setup survives an OpenBao dev-mode wipe (the UI can still
 *       tell the user they previously configured a key and prompt a re-entry);</li>
 *   <li>"who configured a personal key for which provider" is queryable and
 *       auditable from the same database as the rest of the tenant config.</li>
 * </ul>
 *
 * <p>Mirrors the self-contained ArcadeDB HTTP pattern used by
 * {@link AiUsageRecorder} rather than introducing a shared client.</p>
 */
@Component
public class UserProviderConfigStore {

    private static final Logger log = LogManager.getLogger(UserProviderConfigStore.class);
    private static final String DB = "orphos";
    private static final String TYPE = "AiProviderConfig";

    @Autowired
    private RestTemplate rest;

    @Value("${arcadedb.url:http://eip-arcadedb:2480}")
    private String arcadeUrl;

    @Value("${arcadedb.user:root}")
    private String arcadeUser;

    @Value("${arcadedb.password:}")
    private String arcadePassword;

    @PostConstruct
    public void ensureSchema() {
        try {
            command("CREATE DOCUMENT TYPE " + TYPE + " IF NOT EXISTS");
            command("CREATE PROPERTY " + TYPE + ".tenantId      IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".userSub       IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".providerId    IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".keyConfigured IF NOT EXISTS BOOLEAN");
            command("CREATE PROPERTY " + TYPE + ".updatedAt     IF NOT EXISTS DATETIME");
            command("CREATE PROPERTY " + TYPE + ".updatedBy     IF NOT EXISTS STRING");
            // One config row per (tenant, user, provider); fast tenant-user listing.
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE
                    + " (tenantId, userSub, providerId) UNIQUE");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId) NOTUNIQUE");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId, userSub) NOTUNIQUE");
            log.info("AiProviderConfig schema ready on ArcadeDB");
        } catch (Exception e) {
            log.warn("Could not initialise AiProviderConfig schema (will retry per-call): {}", e.getMessage());
        }
    }

    /** Upsert the non-secret state for one (tenant, user, provider). Best-effort. */
    public void upsert(UUID tenantId, String userSub, String providerId,
                       boolean keyConfigured, String updatedBy) {
        if (tenantId == null || userSub == null || userSub.isBlank()
                || providerId == null || providerId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId",      tenantId.toString());
            params.put("userSub",       userSub);
            params.put("providerId",    providerId);
            params.put("keyConfigured", keyConfigured);
            params.put("updatedAt",     OffsetDateTime.now().toString());
            params.put("updatedBy",     updatedBy == null ? "" : updatedBy);
            commandWithParams(
                "UPDATE " + TYPE + " SET "
              + "tenantId = :tenantId, userSub = :userSub, providerId = :providerId, "
              + "keyConfigured = :keyConfigured, updatedAt = :updatedAt, updatedBy = :updatedBy "
              + "UPSERT WHERE tenantId = :tenantId AND userSub = :userSub AND providerId = :providerId",
                params);
        } catch (Exception e) {
            log.warn("AiProviderConfig upsert failed for tenant {} user {} provider {}: {}",
                tenantId, userSub, providerId, e.getMessage());
        }
    }

    /** Returns providerId → keyConfigured for one tenant-user, from the durable mirror. */
    public Map<String, Boolean> listConfigured(UUID tenantId, String userSub) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (tenantId == null || userSub == null || userSub.isBlank()) return out;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId", tenantId.toString());
            params.put("userSub", userSub);
            List<Map<String, Object>> rows = queryWithParams(
                "SELECT providerId, keyConfigured FROM " + TYPE
              + " WHERE tenantId = :tenantId AND userSub = :userSub", params);
            for (Map<String, Object> row : rows) {
                Object pid = row.get("providerId");
                if (pid != null) {
                    out.put(pid.toString(), Boolean.TRUE.equals(row.get("keyConfigured")));
                }
            }
        } catch (Exception e) {
            log.warn("AiProviderConfig list failed for tenant {} user {}: {}",
                tenantId, userSub, e.getMessage());
        }
        return out;
    }

    // ── HTTP plumbing (mirrors AiUsageRecorder) ─────────────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> queryWithParams(String sql, Map<String, Object> params) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
            Map.of("language", "sql", "command", sql, "params", params), headers());
        var res = rest.exchange(arcadeUrl + "/api/v1/query/" + DB, HttpMethod.POST, entity, Map.class);
        if (res.getBody() == null) return List.of();
        Object result = res.getBody().get("result");
        return result instanceof List ? (List<Map<String, Object>>) result : List.of();
    }

    private void command(String sql) {
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(
            Map.of("language", "sql", "command", sql), headers());
        rest.exchange(arcadeUrl + "/api/v1/command/" + DB, HttpMethod.POST, entity, Map.class);
    }

    private void commandWithParams(String sql, Map<String, Object> params) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
            Map.of("language", "sql", "command", sql, "params", params), headers());
        rest.exchange(arcadeUrl + "/api/v1/command/" + DB, HttpMethod.POST, entity, Map.class);
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBasicAuth(arcadeUser, arcadePassword);
        return h;
    }
}
