package io.orphos.eip.mobilebff.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mobilebff.component.MobileArcadeClient;
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
 * Durable registry of push devices per tenant/user for the priority-contact push router,
 * backed by ArcadeDB (type PushDevice). The raw APNs/FCM token should be an OpenBao
 * reference (pushTokenRef) rather than the token itself; this store persists only the
 * registration envelope, scoped by tenantId + ownerSub.
 */
@Repository
public class DeviceStore {

    private static final Logger log = LogManager.getLogger(DeviceStore.class);

    private final MobileArcadeClient arcade;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeviceStore(MobileArcadeClient arcade) {
        this.arcade = arcade;
    }

    /** Register (or re-register) a device for the caller. */
    public Map<String, Object> register(String tenantId, String ownerSub, Map<String, Object> device) {
        Map<String, Object> stored = new LinkedHashMap<>(device);
        stored.putIfAbsent("id", "dev-" + UUID.randomUUID());
        long now = Instant.now().toEpochMilli();
        stored.putIfAbsent("createdAt", now);
        try {
            arcade.commandNoResult(
                    "INSERT INTO PushDevice SET id = :id, tenantId = :t, ownerSub = :o, "
                            + "createdAt = :ca, payload = :p",
                    Map.of("id", stored.get("id"), "t", tenantId, "o", ownerSub,
                            "ca", now, "p", toJson(stored)));
        } catch (Exception e) {
            log.warn("Device registration persist failed for tenant {}: {}", tenantId, e.getMessage());
        }
        return Map.of("ok", true, "registered", device.getOrDefault("platform", "unknown"));
    }

    /** Devices registered by this caller in this tenant. */
    public List<Map<String, Object>> devices(String tenantId, String ownerSub) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT payload FROM PushDevice WHERE tenantId = :t AND ownerSub = :o",
                    Map.of("t", tenantId, "o", ownerSub));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object p = row.get("payload");
                if (p == null) continue;
                try {
                    out.add(mapper.readValue(p.toString(), new TypeReference<Map<String, Object>>() {}));
                } catch (Exception e) {
                    log.debug("Skipping unreadable device payload: {}", e.getMessage());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Device list failed for tenant {}: {}", tenantId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String toJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise device payload: " + e.getMessage(), e);
        }
    }
}
