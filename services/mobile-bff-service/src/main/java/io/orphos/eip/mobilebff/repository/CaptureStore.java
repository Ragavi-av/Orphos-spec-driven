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
 * Durable capture index + thread store, backed by ArcadeDB (types Capture / CaptureMessage,
 * created by {@link io.orphos.eip.mobilebff.component.MobileSchemaBootstrap}). Personal app
 * state: every row is scoped by tenantId + ownerSub, and the full object is preserved
 * verbatim in a JSON {@code payload} so the app's API contract is unchanged. The raw note
 * body never leaves this tenant's database.
 */
@Repository
public class CaptureStore {

    private static final Logger log = LogManager.getLogger(CaptureStore.class);

    private final MobileArcadeClient arcade;
    private final ObjectMapper mapper = new ObjectMapper();

    public CaptureStore(MobileArcadeClient arcade) {
        this.arcade = arcade;
    }

    /** Captures owned by this caller in this tenant, newest first. */
    public List<Map<String, Object>> list(String tenantId, String ownerSub) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT payload FROM Capture WHERE tenantId = :t AND ownerSub = :o ORDER BY createdAt DESC",
                    Map.of("t", tenantId, "o", ownerSub));
            return parsePayloads(rows);
        } catch (Exception e) {
            log.warn("Capture list failed for tenant {}: {}", tenantId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Persist a new capture; injects id + owner + createdAt and returns the stored object. */
    public Map<String, Object> add(String tenantId, String ownerSub, Map<String, Object> capture) {
        Map<String, Object> stored = new LinkedHashMap<>(capture);
        stored.putIfAbsent("id", "cap-" + UUID.randomUUID());
        long now = Instant.now().toEpochMilli();
        stored.putIfAbsent("createdAt", now);
        arcade.commandNoResult(
                "INSERT INTO Capture SET id = :id, tenantId = :t, ownerSub = :o, "
                        + "captureType = :ct, createdAt = :ca, payload = :p",
                Map.of("id", stored.get("id"), "t", tenantId, "o", ownerSub,
                        "ct", String.valueOf(stored.getOrDefault("type", "")),
                        "ca", now, "p", toJson(stored)));
        return stored;
    }

    /** One capture owned by this caller, or null when it is not theirs / not found. */
    public Map<String, Object> get(String tenantId, String ownerSub, String captureId) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT payload FROM Capture WHERE id = :id AND tenantId = :t AND ownerSub = :o",
                    Map.of("id", captureId, "t", tenantId, "o", ownerSub));
            List<Map<String, Object>> parsed = parsePayloads(rows);
            return parsed.isEmpty() ? null : parsed.get(0);
        } catch (Exception e) {
            log.warn("Capture load failed for {}: {}", captureId, e.getMessage());
            return null;
        }
    }

    /** Rewrite a stored capture's payload (e.g. to record the doc-ingest documentId). */
    public void update(String tenantId, String ownerSub, Map<String, Object> capture) {
        arcade.commandNoResult(
                "UPDATE Capture SET payload = :p WHERE id = :id AND tenantId = :t AND ownerSub = :o",
                Map.of("id", String.valueOf(capture.get("id")), "t", tenantId, "o", ownerSub,
                        "p", toJson(capture)));
    }

    /**
     * Store a capture's original bytes (e.g. the handwriting image) as a CaptureFile,
     * base64 in the payload. Kept out of the Capture payload so the list stays small.
     */
    public void addFile(String tenantId, String ownerSub, String captureId, String mimeType, byte[] bytes) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("mimeType", mimeType);
        file.put("data", java.util.Base64.getEncoder().encodeToString(bytes));
        arcade.commandNoResult(
                "INSERT INTO CaptureFile SET id = :id, tenantId = :t, ownerSub = :o, "
                        + "captureId = :c, createdAt = :ca, payload = :p",
                Map.of("id", "file-" + UUID.randomUUID(), "t", tenantId, "o", ownerSub,
                        "c", captureId, "ca", Instant.now().toEpochMilli(), "p", toJson(file)));
    }

    /** The stored original for a capture owned by this caller: {mimeType, bytes}, or null. */
    public StoredFile file(String tenantId, String ownerSub, String captureId) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT payload FROM CaptureFile WHERE tenantId = :t AND ownerSub = :o AND captureId = :c "
                            + "ORDER BY createdAt DESC LIMIT 1",
                    Map.of("t", tenantId, "o", ownerSub, "c", captureId));
            List<Map<String, Object>> parsed = parsePayloads(rows);
            if (parsed.isEmpty() || parsed.get(0).get("data") == null) return null;
            Map<String, Object> f = parsed.get(0);
            return new StoredFile(String.valueOf(f.getOrDefault("mimeType", "application/octet-stream")),
                    java.util.Base64.getDecoder().decode(f.get("data").toString()));
        } catch (Exception e) {
            log.warn("Capture file load failed for {}: {}", captureId, e.getMessage());
            return null;
        }
    }

    /** A capture's original bytes and their content type. */
    public record StoredFile(String mimeType, byte[] bytes) {}

    /** Thread messages for a capture, oldest first. */
    public List<Map<String, Object>> thread(String tenantId, String captureId) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT payload FROM CaptureMessage WHERE tenantId = :t AND captureId = :c ORDER BY createdAt ASC",
                    Map.of("t", tenantId, "c", captureId));
            return parsePayloads(rows);
        } catch (Exception e) {
            log.warn("Capture thread load failed for {}: {}", captureId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Append a message to a capture's thread. */
    public Map<String, Object> addMessage(String tenantId, String ownerSub,
                                          String captureId, Map<String, Object> message) {
        Map<String, Object> stored = new LinkedHashMap<>(message);
        stored.putIfAbsent("id", "msg-" + UUID.randomUUID());
        long now = Instant.now().toEpochMilli();
        stored.putIfAbsent("createdAt", now);
        arcade.commandNoResult(
                "INSERT INTO CaptureMessage SET id = :id, tenantId = :t, ownerSub = :o, "
                        + "captureId = :c, createdAt = :ca, payload = :p",
                Map.of("id", stored.get("id"), "t", tenantId, "o", ownerSub,
                        "c", captureId, "ca", now, "p", toJson(stored)));
        return stored;
    }

    // --- helpers ---

    private List<Map<String, Object>> parsePayloads(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object p = row.get("payload");
            if (p == null) continue;
            try {
                out.add(mapper.readValue(p.toString(), new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.debug("Skipping unreadable capture payload: {}", e.getMessage());
            }
        }
        return out;
    }

    private String toJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise capture payload: " + e.getMessage(), e);
        }
    }
}
