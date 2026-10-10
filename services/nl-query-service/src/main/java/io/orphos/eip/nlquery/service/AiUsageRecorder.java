package io.orphos.eip.nlquery.service;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.vo.AiUsageEvent;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records one row per AI chat call to ArcadeDB for audit and quota tracking.
 * Tenant-scoped: every row carries the originating tenant UUID, and every
 * read query filters by it.
 *
 * <p>Fire-and-forget: callers never see exceptions from this path. If
 * ArcadeDB is unreachable the failure is logged and the chat response still
 * goes back to the user.</p>
 */
@Service
public class AiUsageRecorder {

    private static final Logger log = LogManager.getLogger(AiUsageRecorder.class);
    private static final String DB = "orphos";
    private static final String TYPE = "AiUsage";

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
            command("CREATE PROPERTY " + TYPE + ".tenantId   IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".userSub    IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".providerId IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".modelName  IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".timestamp  IF NOT EXISTS DATETIME");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId) NOTUNIQUE");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (userSub) NOTUNIQUE");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (providerId) NOTUNIQUE");
            log.info("AiUsage schema ready on ArcadeDB");
        } catch (Exception e) {
            log.warn("Could not initialise AiUsage schema (will retry per-call): {}", e.getMessage());
        }
    }

    @Async
    public void record(AiUsageEvent event) {
        if (event == null) return;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId",      event.tenantId() == null ? "" : event.tenantId());
            params.put("userSub",       event.userSub() == null ? "anonymous" : event.userSub());
            params.put("providerId",    event.providerId());
            params.put("modelName",     event.modelName());
            params.put("promptChars",   event.promptChars());
            params.put("responseChars", event.responseChars());
            params.put("success",       event.success());
            params.put("latencyMs",     event.latencyMs());
            params.put("errorClass",    event.errorClass() == null ? "" : event.errorClass());
            // Epoch millis, NOT Instant.toString(): ArcadeDB's DATETIME column
            // rejects the ISO-8601 'T...Z' form (it parses to null), but accepts
            // an epoch-millis number and round-trips it as 'yyyy-MM-dd HH:mm:ss'.
            params.put("timestamp",     event.timestamp().toEpochMilli());

            commandWithParams(
                "INSERT INTO " + TYPE + " SET "
              + "tenantId = :tenantId, "
              + "userSub = :userSub, "
              + "providerId = :providerId, "
              + "modelName = :modelName, "
              + "promptChars = :promptChars, "
              + "responseChars = :responseChars, "
              + "success = :success, "
              + "latencyMs = :latencyMs, "
              + "errorClass = :errorClass, "
              + "timestamp = :timestamp",
                params);
        } catch (Exception e) {
            log.warn("AiUsage write failed for tenant {} user {}/{}: {}",
                event.tenantId(), event.userSub(), event.providerId(), e.getMessage());
        }
    }

    public List<Map<String, Object>> queryRecent(String userSub, int limit) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        StringBuilder sql = new StringBuilder("SELECT FROM ").append(TYPE)
                .append(" WHERE tenantId = :tenantId");
        if (userSub != null && !userSub.isBlank()) {
            sql.append(" AND userSub = :userSub");
            params.put("userSub", userSub);
        }
        sql.append(" ORDER BY timestamp DESC LIMIT ")
                .append(Math.max(1, Math.min(limit, 500)));
        return queryWithParams(sql.toString(), params);
    }

    public List<Map<String, Object>> queryAggregates(String userSub) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        StringBuilder where = new StringBuilder("WHERE tenantId = :tenantId");
        if (userSub != null && !userSub.isBlank()) {
            where.append(" AND userSub = :userSub");
            params.put("userSub", userSub);
        }
        String sql = "SELECT providerId, modelName, "
                   + "COUNT(*) AS calls, "
                   + "SUM(CASE WHEN success = true THEN 1 ELSE 0 END) AS successCalls, "
                   + "SUM(promptChars) AS totalPromptChars, "
                   + "SUM(responseChars) AS totalResponseChars, "
                   + "AVG(latencyMs) AS avgLatencyMs "
                   + "FROM " + TYPE + " " + where + " "
                   + "GROUP BY providerId, modelName";
        return queryWithParams(sql, params);
    }

    // ── HTTP plumbing ──────────────────────────────────────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> queryWithParams(String sql, Map<String, Object> params) {
        try {
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
        } catch (Exception e) {
            log.warn("AiUsage query failed: {}", e.getMessage());
            return List.of();
        }
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
