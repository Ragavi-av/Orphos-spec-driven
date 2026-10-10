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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable, tenant-scoped record of each provider's <em>non-secret</em> admin
 * configuration: whether it is enabled for the tenant, whether it is the tenant
 * default selection, and the preferred model.
 *
 * <p>The API key itself stays in OpenBao (see {@link TenantProviderConfigStore})
 * — but the enabled / default / model choices are not secrets and belong in a
 * durable store. OpenBao runs in dev mode (in-memory) in this environment and is
 * wiped on every restart, which is why the admin's "default provider" choice did
 * not survive. Mirroring the non-secret state here (ArcadeDB has a persistent
 * volume) makes that choice durable, exactly as {@link UserProviderConfigStore}
 * already does for per-user setup.</p>
 *
 * <p>One {@code TenantAiProviderConfig} document per {@code (tenantId, providerId)}.
 * Self-contained ArcadeDB HTTP plumbing mirrors {@link UserProviderConfigStore}.</p>
 */
@Component
public class TenantProviderMetaStore {

    private static final Logger log = LogManager.getLogger(TenantProviderMetaStore.class);
    private static final String DB = "orphos";
    private static final String TYPE = "TenantAiProviderConfig";

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
            command("CREATE PROPERTY " + TYPE + ".providerId IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".enabled    IF NOT EXISTS BOOLEAN");
            command("CREATE PROPERTY " + TYPE + ".isDefault  IF NOT EXISTS BOOLEAN");
            command("CREATE PROPERTY " + TYPE + ".model      IF NOT EXISTS STRING");
            // updatedAt kept as STRING on purpose: ArcadeDB DATETIME columns store
            // null for ISO 'T...Z' strings, so a STRING audit stamp is simplest.
            command("CREATE PROPERTY " + TYPE + ".updatedAt  IF NOT EXISTS STRING");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId, providerId) UNIQUE");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId) NOTUNIQUE");
            log.info("TenantAiProviderConfig schema ready on ArcadeDB");
        } catch (Exception e) {
            log.warn("Could not initialise TenantAiProviderConfig schema (will retry per-call): {}", e.getMessage());
        }
    }

    /**
     * The stored non-secret state for one (tenant, provider), or empty defaults
     * (disabled, not-default, no model) when nothing is persisted yet.
     */
    public TenantProviderMeta read(UUID tenantId, String providerId) {
        if (tenantId == null || providerId == null || providerId.isBlank()) {
            return TenantProviderMeta.empty();
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId", tenantId.toString());
            params.put("providerId", providerId);
            List<Map<String, Object>> rows = queryWithParams(
                "SELECT enabled, isDefault, model FROM " + TYPE
              + " WHERE tenantId = :tenantId AND providerId = :providerId LIMIT 1", params);
            if (rows.isEmpty()) return TenantProviderMeta.empty();
            Map<String, Object> r = rows.get(0);
            Object model = r.get("model");
            return new TenantProviderMeta(
                    Boolean.TRUE.equals(r.get("enabled")),
                    Boolean.TRUE.equals(r.get("isDefault")),
                    Optional.ofNullable(model).map(Object::toString).filter(s -> !s.isBlank()));
        } catch (Exception e) {
            log.warn("TenantAiProviderConfig read failed for tenant {} provider {}: {}",
                tenantId, providerId, e.getMessage());
            return TenantProviderMeta.empty();
        }
    }

    /** Upsert the non-secret state for one (tenant, provider). */
    public void upsert(UUID tenantId, String providerId, boolean enabled,
                       boolean isDefault, Optional<String> model) {
        if (tenantId == null || providerId == null || providerId.isBlank()) return;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId.toString());
        params.put("providerId", providerId);
        params.put("enabled", enabled);
        params.put("isDefault", isDefault);
        params.put("model", model == null ? null : model.orElse(""));
        params.put("updatedAt", Instant.now().toString());
        try {
            commandWithParams(
                "UPDATE " + TYPE + " SET "
              + "tenantId = :tenantId, providerId = :providerId, enabled = :enabled, "
              + "isDefault = :isDefault, model = :model, updatedAt = :updatedAt "
              + "UPSERT WHERE tenantId = :tenantId AND providerId = :providerId",
                params);
        } catch (Exception e) {
            // Unlike read/delete, a failed write MUST NOT be swallowed — the
            // caller relies on it having persisted. Log consistently with the
            // sibling methods and rethrow a clear signal the API layer can turn
            // into a meaningful message instead of an opaque 500.
            log.warn("TenantAiProviderConfig upsert failed for tenant {} provider {}: {}",
                tenantId, providerId, e.getMessage());
            throw new IllegalStateException(
                "Could not persist provider configuration to ArcadeDB: " + e.getMessage(), e);
        }
    }

    public void delete(UUID tenantId, String providerId) {
        if (tenantId == null || providerId == null || providerId.isBlank()) return;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId", tenantId.toString());
            params.put("providerId", providerId);
            commandWithParams("DELETE FROM " + TYPE
                + " WHERE tenantId = :tenantId AND providerId = :providerId", params);
        } catch (Exception e) {
            log.warn("TenantAiProviderConfig delete failed for tenant {} provider {}: {}",
                tenantId, providerId, e.getMessage());
        }
    }

    /** Immutable view of a tenant provider's non-secret config. */
    public record TenantProviderMeta(boolean enabled, boolean isDefault, Optional<String> model) {
        public TenantProviderMeta {
            if (model == null) model = Optional.empty();
        }
        public static TenantProviderMeta empty() {
            return new TenantProviderMeta(false, false, Optional.empty());
        }
    }

    // ── HTTP plumbing (mirrors UserProviderConfigStore / AiUsageRecorder) ───────

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
