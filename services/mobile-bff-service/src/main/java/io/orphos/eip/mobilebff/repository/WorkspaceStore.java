package io.orphos.eip.mobilebff.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mobilebff.component.MobileArcadeClient;
import io.orphos.eip.mobilebff.component.MobileSchemaBootstrap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Generic owner-scoped envelope store for personal workspace types (PriorityContact,
 * Trip, NotificationPreference). Every row is scoped by tenantId + ownerSub and the
 * full object is preserved verbatim in a JSON {@code payload}. Types are created by
 * {@link io.orphos.eip.mobilebff.component.MobileSchemaBootstrap}.
 */
@Repository
public class WorkspaceStore {

    private static final Logger log = LogManager.getLogger(WorkspaceStore.class);

    private final MobileArcadeClient arcade;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkspaceStore(MobileArcadeClient arcade) {
        this.arcade = arcade;
    }

    /** Rows of {@code type} owned by this caller, newest first. */
    public List<Map<String, Object>> list(String type, String tenantId, String ownerSub) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT payload FROM " + type + " WHERE tenantId = :t AND ownerSub = :o "
                            + "ORDER BY createdAt DESC",
                    Map.of("t", tenantId, "o", ownerSub));
            return parsePayloads(rows);
        } catch (Exception e) {
            log.warn("{} list failed for tenant {}: {}", type, tenantId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Persist a new row; injects id + createdAt and returns the stored object. */
    public Map<String, Object> add(String type, String tenantId, String ownerSub, Map<String, Object> body) {
        Map<String, Object> stored = new LinkedHashMap<>(body);
        stored.putIfAbsent("id", type.toLowerCase() + "-" + UUID.randomUUID());
        long now = Instant.now().toEpochMilli();
        stored.putIfAbsent("createdAt", now);
        arcade.commandNoResult(
                "INSERT INTO " + type + " SET id = :id, tenantId = :t, ownerSub = :o, "
                        + "createdAt = :ca, payload = :p",
                Map.of("id", stored.get("id"), "t", tenantId, "o", ownerSub,
                        "ca", now, "p", toJson(stored)));
        return stored;
    }

    /** The owner's row of {@code type} with this id, or {@code null}. */
    public Map<String, Object> findById(String type, String tenantId, String ownerSub, String id) {
        for (Map<String, Object> m : list(type, tenantId, ownerSub)) {
            if (id != null && id.equals(String.valueOf(m.get("id")))) return m;
        }
        return null;
    }

    /**
     * Merge {@code patch} into the existing row identified by {@code id} (owner-scoped),
     * preserving its original {@code createdAt}. Returns the merged object, or {@code null}
     * if no such row exists. Used to record the executive's decision on an approval.
     */
    public Map<String, Object> updateById(String type, String tenantId, String ownerSub,
                                          String id, Map<String, Object> patch) {
        Map<String, Object> existing = findById(type, tenantId, ownerSub, id);
        if (existing == null) return null;
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll(patch);
        // One statement, so a failure can never leave the row deleted without its replacement.
        arcade.commandNoResult(
                "UPDATE " + type + " SET payload = :p WHERE id = :id AND tenantId = :t AND ownerSub = :o",
                Map.of("id", id, "t", tenantId, "o", ownerSub, "p", toJson(merged)));
        return merged;
    }

    /**
     * Idempotent insert keyed by the inferred item's {@code sourceId}: if a row with the
     * same sourceId already exists for this owner it is returned UNCHANGED (so a
     * re-inference never clobbers a decision the exec has already recorded); otherwise the
     * item is inserted. This is how auto-inferred Approvals/Travel stay persistent.
     */
    public Map<String, Object> upsertBySource(String type, String tenantId, String ownerSub,
                                             Map<String, Object> body) {
        Object src = body.get("sourceId");
        if (src != null) {
            for (Map<String, Object> m : list(type, tenantId, ownerSub)) {
                if (src.equals(m.get("sourceId"))) return m;
            }
        }
        return add(type, tenantId, ownerSub, body);
    }


    /** Single-record-per-owner upsert (e.g. NotificationPreference): replace then insert. */
    public Map<String, Object> upsertSingleton(String type, String tenantId, String ownerSub,
                                               Map<String, Object> body) {
        try {
            arcade.commandNoResult(
                    "DELETE FROM " + type + " WHERE tenantId = :t AND ownerSub = :o",
                    Map.of("t", tenantId, "o", ownerSub));
        } catch (Exception e) {
            log.debug("{} singleton clear skipped: {}", type, e.getMessage());
        }
        return add(type, tenantId, ownerSub, body);
    }

    /** The single record for this owner, or an empty map. */
    public Map<String, Object> single(String type, String tenantId, String ownerSub) {
        List<Map<String, Object>> all = list(type, tenantId, ownerSub);
        return all.isEmpty() ? Map.of() : all.get(0);
    }

    private List<Map<String, Object>> parsePayloads(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object p = row.get("payload");
            if (p == null) continue;
            try {
                out.add(mapper.readValue(p.toString(), new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.debug("Skipping unreadable payload: {}", e.getMessage());
            }
        }
        return out;
    }

    private String toJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise workspace payload: " + e.getMessage(), e);
        }
    }
}
