package io.orphos.eip.nlquery.component;

import io.orphos.eip.nlquery.constants.AiUsageTypes;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable, tenant-scoped assignment of an AI provider (and optional model) to
 * each {@link AiUsageTypes usage type} — "which AI answers the co-pilot",
 * "which AI classifies mail", etc.
 *
 * <p>Nothing here is secret (keys stay in the vault via
 * {@link TenantProviderConfigStore}), so the whole record lives in ArcadeDB.
 * One {@code TenantAiUsageAssignment} document per {@code (tenantId, usageType)}.
 * Self-contained ArcadeDB HTTP plumbing mirrors {@link TenantProviderMetaStore}.</p>
 */
@Component
public class TenantAiUsageAssignmentStore {

    private static final Logger log = LogManager.getLogger(TenantAiUsageAssignmentStore.class);
    private static final String DB = "orphos";
    private static final String TYPE = "TenantAiUsageAssignment";

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
            command("CREATE PROPERTY " + TYPE + ".usageType  IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".providerId IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".model      IF NOT EXISTS STRING");
            // updatedAt kept as STRING on purpose: ArcadeDB DATETIME columns store
            // null for ISO 'T...Z' strings, so a STRING audit stamp is simplest.
            command("CREATE PROPERTY " + TYPE + ".updatedAt  IF NOT EXISTS STRING");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId, usageType) UNIQUE");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId) NOTUNIQUE");
            log.info("TenantAiUsageAssignment schema ready on ArcadeDB");
        } catch (Exception e) {
            log.warn("Could not initialise TenantAiUsageAssignment schema (will retry per-call): {}", e.getMessage());
        }
    }

    /** The stored assignment for one (tenant, usageType), or empty when unassigned. */
    public Optional<Assignment> read(UUID tenantId, String usageType) {
        if (tenantId == null || usageType == null || usageType.isBlank()) return Optional.empty();
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId", tenantId.toString());
            params.put("usageType", usageType);
            List<Map<String, Object>> rows = queryWithParams(
                "SELECT providerId, model FROM " + TYPE
              + " WHERE tenantId = :tenantId AND usageType = :usageType LIMIT 1", params);
            if (rows.isEmpty()) return Optional.empty();
            return toAssignment(rows.get(0));
        } catch (Exception e) {
            log.warn("TenantAiUsageAssignment read failed for tenant {} usage {}: {}",
                tenantId, usageType, e.getMessage());
            return Optional.empty();
        }
    }

    /** All assignments for a tenant, keyed by usage type. */
    public Map<String, Assignment> readAll(UUID tenantId) {
        Map<String, Assignment> out = new LinkedHashMap<>();
        if (tenantId == null) return out;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId", tenantId.toString());
            List<Map<String, Object>> rows = queryWithParams(
                "SELECT usageType, providerId, model FROM " + TYPE
              + " WHERE tenantId = :tenantId", params);
            for (Map<String, Object> r : rows) {
                Object type = r.get("usageType");
                if (type == null) continue;
                toAssignment(r).ifPresent(a -> out.put(type.toString(), a));
            }
        } catch (Exception e) {
            log.warn("TenantAiUsageAssignment readAll failed for tenant {}: {}", tenantId, e.getMessage());
        }
        return out;
    }

    /** Upsert the assignment for one (tenant, usageType). */
    public void upsert(UUID tenantId, String usageType, String providerId, Optional<String> model) {
        if (tenantId == null || usageType == null || usageType.isBlank()
                || providerId == null || providerId.isBlank()) return;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId.toString());
        params.put("usageType", usageType);
        params.put("providerId", providerId);
        params.put("model", model == null ? "" : model.orElse(""));
        params.put("updatedAt", Instant.now().toString());
        commandWithParams(
            "UPDATE " + TYPE + " SET "
          + "tenantId = :tenantId, usageType = :usageType, providerId = :providerId, "
          + "model = :model, updatedAt = :updatedAt "
          + "UPSERT WHERE tenantId = :tenantId AND usageType = :usageType",
            params);
    }

    public void delete(UUID tenantId, String usageType) {
        if (tenantId == null || usageType == null || usageType.isBlank()) return;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId", tenantId.toString());
            params.put("usageType", usageType);
            commandWithParams("DELETE FROM " + TYPE
                + " WHERE tenantId = :tenantId AND usageType = :usageType", params);
        } catch (Exception e) {
            log.warn("TenantAiUsageAssignment delete failed for tenant {} usage {}: {}",
                tenantId, usageType, e.getMessage());
        }
    }

    /** Immutable view of one usage-type assignment. */
    public record Assignment(String providerId, Optional<String> model) {
        public Assignment {
            if (model == null) model = Optional.empty();
        }
    }

    private static Optional<Assignment> toAssignment(Map<String, Object> row) {
        Object provider = row.get("providerId");
        if (provider == null || provider.toString().isBlank()) return Optional.empty();
        Object model = row.get("model");
        return Optional.of(new Assignment(
                provider.toString(),
                Optional.ofNullable(model).map(Object::toString).filter(s -> !s.isBlank())));
    }

    // ── HTTP plumbing (mirrors TenantProviderMetaStore) ─────────────────────────

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
