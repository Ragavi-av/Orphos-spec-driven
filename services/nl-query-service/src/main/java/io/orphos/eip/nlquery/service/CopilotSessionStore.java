package io.orphos.eip.nlquery.service;

import io.orphos.eip.common.tenant.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side store for AI Co-pilot chat sessions, one document per session.
 * Replaces the browser-localStorage history so sessions follow the user
 * across devices and survive browser data clears.
 *
 * <p>Tenant-scoped like AiUsage: every document carries the originating
 * tenant UUID and the user's Keycloak sub, and every query filters by both —
 * a user can only ever read or delete their own sessions within their tenant.</p>
 */
@Service
public class CopilotSessionStore {

    private static final Logger log = LogManager.getLogger(CopilotSessionStore.class);
    private static final String DB = "orphos";
    private static final String TYPE = "CopilotSession";

    /** Same cap the localStorage store enforced (MAX_SESSIONS in sessionStore.js). */
    public static final int MAX_SESSIONS_PER_USER = 100;

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
            command("CREATE PROPERTY " + TYPE + ".tenantId     IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".userSub      IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".sessionId    IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".label        IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".startedAt    IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".endedAt      IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".messagesJson IF NOT EXISTS STRING");
            // UPSERT requires a UNIQUE index covering the WHERE keys.
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId, userSub, sessionId) UNIQUE");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId, userSub) NOTUNIQUE");
            log.info("CopilotSession schema ready on ArcadeDB");
        } catch (Exception e) {
            log.warn("Could not initialise CopilotSession schema (will retry per-call): {}", e.getMessage());
        }
    }

    public void upsert(String userSub, String sessionId, String label,
                       String startedAt, String endedAt, String messagesJson) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId",     tenantId);
        params.put("userSub",      userSub);
        params.put("sessionId",    sessionId);
        params.put("label",        label == null ? "" : label);
        params.put("startedAt",    startedAt == null ? "" : startedAt);
        params.put("endedAt",      endedAt == null ? "" : endedAt);
        params.put("messagesJson", messagesJson == null ? "[]" : messagesJson);
        commandWithParams(
            "UPDATE " + TYPE + " SET "
          + "label = :label, startedAt = :startedAt, endedAt = :endedAt, messagesJson = :messagesJson "
          + "UPSERT WHERE tenantId = :tenantId AND userSub = :userSub AND sessionId = :sessionId",
            params);
        prune(tenantId, userSub);
    }

    public List<Map<String, Object>> list(String userSub, int limit) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("userSub",  userSub);
        // ISO-8601 startedAt strings sort chronologically as plain strings.
        String sql = "SELECT sessionId, label, startedAt, endedAt, messagesJson FROM " + TYPE
                   + " WHERE tenantId = :tenantId AND userSub = :userSub"
                   + " ORDER BY startedAt DESC LIMIT "
                   + Math.max(1, Math.min(limit, MAX_SESSIONS_PER_USER));
        return queryWithParams(sql, params);
    }

    public void delete(String userSub, String sessionId) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId",  tenantId);
        params.put("userSub",   userSub);
        params.put("sessionId", sessionId);
        commandWithParams("DELETE FROM " + TYPE
            + " WHERE tenantId = :tenantId AND userSub = :userSub AND sessionId = :sessionId", params);
    }

    public void deleteAll(String userSub) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("userSub",  userSub);
        commandWithParams("DELETE FROM " + TYPE
            + " WHERE tenantId = :tenantId AND userSub = :userSub", params);
    }

    /** Drop the oldest sessions beyond MAX_SESSIONS_PER_USER, mirroring the old client cap. */
    private void prune(String tenantId, String userSub) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId", tenantId);
            params.put("userSub",  userSub);
            List<Map<String, Object>> all = queryWithParams(
                "SELECT sessionId FROM " + TYPE
              + " WHERE tenantId = :tenantId AND userSub = :userSub ORDER BY startedAt DESC", params);
            for (int i = MAX_SESSIONS_PER_USER; i < all.size(); i++) {
                Object overflowId = all.get(i).get("sessionId");
                if (overflowId != null) delete(userSub, overflowId.toString());
            }
        } catch (Exception e) {
            log.warn("CopilotSession prune failed for user {}: {}", userSub, e.getMessage());
        }
    }

    // ── HTTP plumbing (same pattern as AiUsageRecorder) ────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> queryWithParams(String sql, Map<String, Object> params) {
        HttpHeaders h = headers();
        Map<String, Object> body = Map.of(
                "language", "sql",
                "command",  sql,
                "params",   params);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, h);
        var res = rest.exchange(arcadeUrl + "/api/v1/query/" + DB, HttpMethod.POST, entity, Map.class);
        if (res.getBody() == null) return List.of();
        Object result = res.getBody().get("result");
        return result instanceof List ? (List<Map<String, Object>>) result : List.of();
    }

    private void command(String sql) {
        HttpHeaders h = headers();
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(
            Map.of("language", "sql", "command", sql), h);
        rest.exchange(arcadeUrl + "/api/v1/command/" + DB, HttpMethod.POST, entity, Map.class);
    }

    private void commandWithParams(String sql, Map<String, Object> params) {
        HttpHeaders h = headers();
        Map<String, Object> body = Map.of(
            "language", "sql",
            "command",  sql,
            "params",   params
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, h);
        rest.exchange(arcadeUrl + "/api/v1/command/" + DB, HttpMethod.POST, entity, Map.class);
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBasicAuth(arcadeUser, arcadePassword);
        return h;
    }
}
