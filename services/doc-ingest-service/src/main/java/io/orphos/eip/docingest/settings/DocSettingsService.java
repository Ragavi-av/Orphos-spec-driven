package io.orphos.eip.docingest.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.docingest.arcade.DocArcadeClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and persists per-tenant {@link DocSettings}.
 *
 * <p>Stored as one row per tenant in the {@code DocIngestSettings} ArcadeDB
 * document type — a single {@code settingsJson} STRING column holds the
 * serialised {@link DocSettings}, so adding settings fields never requires a
 * schema migration. A blank/absent row yields {@link DocSettings#defaults()}.</p>
 */
@Service
public class DocSettingsService {

    private static final Logger log = LogManager.getLogger(DocSettingsService.class);

    private final DocArcadeClient arcade;
    // Tolerate fields that aren't on the current record (e.g. after a rollback) —
    // normalized() backfills anything missing.
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DocSettingsService(DocArcadeClient arcade) {
        this.arcade = arcade;
    }

    /** Returns the tenant's settings, or platform defaults if none saved yet. */
    public DocSettings get(String tenantId) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                "SELECT settingsJson FROM DocIngestSettings WHERE tenantId = :tenantId",
                Map.of("tenantId", tenantId));
            if (rows.isEmpty()) return DocSettings.defaults();
            Object json = rows.get(0).get("settingsJson");
            if (json == null || json.toString().isBlank()) return DocSettings.defaults();
            return mapper.readValue(json.toString(), DocSettings.class).normalized();
        } catch (Exception e) {
            log.warn("Failed to read DocIngestSettings for tenant {} — falling back to defaults: {}",
                tenantId, e.getMessage());
            return DocSettings.defaults();
        }
    }

    /** Upserts the tenant's settings, returning the normalised, persisted value. */
    public DocSettings save(String tenantId, DocSettings incoming, String updatedBy) {
        DocSettings normalized = incoming.normalized();
        String json;
        try {
            json = mapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise settings: " + e.getMessage(), e);
        }

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId",     tenantId);
        params.put("settingsJson", json);
        params.put("updatedAt",    Instant.now().toString());
        params.put("updatedBy",    updatedBy != null ? updatedBy : "");

        // ArcadeDB has no UPSERT for document types without a unique-key UPDATE;
        // emulate it: UPDATE first, INSERT only when no row matched.
        try {
            List<Map<String, Object>> updated = arcade.command(
                "UPDATE DocIngestSettings SET settingsJson = :settingsJson, " +
                "updatedAt = :updatedAt, updatedBy = :updatedBy WHERE tenantId = :tenantId",
                params);
            long count = countAffected(updated);
            if (count == 0) {
                arcade.commandNoResult(
                    "INSERT INTO DocIngestSettings SET tenantId = :tenantId, " +
                    "settingsJson = :settingsJson, updatedAt = :updatedAt, updatedBy = :updatedBy",
                    params);
            }
        } catch (Exception e) {
            log.error("Failed to persist DocIngestSettings for tenant {}: {}", tenantId, e.getMessage());
            throw new IllegalStateException("Could not save settings", e);
        }
        return normalized;
    }

    /** Resets a tenant to platform defaults by deleting its row. */
    public void reset(String tenantId) {
        try {
            arcade.commandNoResult(
                "DELETE FROM DocIngestSettings WHERE tenantId = :tenantId",
                Map.of("tenantId", tenantId));
        } catch (Exception e) {
            log.warn("Failed to reset DocIngestSettings for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /** ArcadeDB's UPDATE returns a single row {@code {count: n}} on the REST API. */
    private static long countAffected(List<Map<String, Object>> result) {
        if (result == null || result.isEmpty()) return 0;
        Object c = result.get(0).get("count");
        if (c instanceof Number n) return n.longValue();
        return 0;
    }
}
