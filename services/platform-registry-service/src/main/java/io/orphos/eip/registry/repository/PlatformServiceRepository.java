package io.orphos.eip.registry.repository;

import io.orphos.eip.registry.component.RegistryArcadeClient;
import io.orphos.eip.registry.entity.PlatformService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ArcadeDB-backed store for the {@code PlatformService} document type, keyed by a
 * unique {@code serviceId} index. Mirrors {@link PluginManifestRepository}'s
 * UPDATE-then-INSERT upsert idiom.
 */
@Repository
public class PlatformServiceRepository {

    private final RegistryArcadeClient arcade;

    public PlatformServiceRepository(RegistryArcadeClient arcade) {
        this.arcade = arcade;
    }

    public List<PlatformService> findAll() {
        return arcade.query("SELECT FROM PlatformService", Map.of())
                .stream()
                .map(PlatformServiceRepository::map)
                .toList();
    }

    public Optional<PlatformService> findById(String serviceId) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT FROM PlatformService WHERE serviceId = :serviceId",
                Map.of("serviceId", serviceId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0)));
    }

    /** Upsert by {@code serviceId}: UPDATE first, INSERT only when no row matched. */
    public PlatformService save(PlatformService s) {
        Map<String, Object> params = toParams(s);

        List<Map<String, Object>> updated = arcade.command(
                "UPDATE PlatformService SET displayName = :displayName, tier = :tier, version = :version, "
                        + "healthUrl = :healthUrl, capabilitiesJson = :capabilitiesJson, status = :status, "
                        + "lastSeenAt = :lastSeenAt, registeredAt = :registeredAt WHERE serviceId = :serviceId",
                params);

        if (countAffected(updated) == 0) {
            arcade.commandNoResult(
                    "INSERT INTO PlatformService SET serviceId = :serviceId, displayName = :displayName, "
                            + "tier = :tier, version = :version, healthUrl = :healthUrl, "
                            + "capabilitiesJson = :capabilitiesJson, status = :status, "
                            + "lastSeenAt = :lastSeenAt, registeredAt = :registeredAt",
                    params);
        }
        return s;
    }

    /** Lightweight status update used by the heartbeat poller (no full upsert). */
    public void updateStatus(String serviceId, String status, String lastSeenAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("serviceId", serviceId);
        params.put("status", nn(status));
        params.put("lastSeenAt", nn(lastSeenAt));
        arcade.command(
                "UPDATE PlatformService SET status = :status, lastSeenAt = :lastSeenAt WHERE serviceId = :serviceId",
                params);
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private static Map<String, Object> toParams(PlatformService s) {
        Map<String, Object> params = new HashMap<>();
        params.put("serviceId", s.getServiceId());
        params.put("displayName", nn(s.getDisplayName()));
        params.put("tier", nn(s.getTier()));
        params.put("version", nn(s.getVersion()));
        params.put("healthUrl", nn(s.getHealthUrl()));
        params.put("capabilitiesJson", s.getCapabilitiesJson() == null ? "[]" : s.getCapabilitiesJson());
        params.put("status", nn(s.getStatus()));
        params.put("lastSeenAt", nn(s.getLastSeenAt()));
        params.put("registeredAt",
                s.getRegisteredAt() != null ? s.getRegisteredAt().toString() : Instant.now().toString());
        return params;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static PlatformService map(Map<String, Object> row) {
        PlatformService s = new PlatformService();
        s.setServiceId(str(row.get("serviceId")));
        s.setDisplayName(str(row.get("displayName")));
        s.setTier(str(row.get("tier")));
        s.setVersion(str(row.get("version")));
        s.setHealthUrl(str(row.get("healthUrl")));
        s.setCapabilitiesJson(str(row.get("capabilitiesJson")));
        s.setStatus(str(row.get("status")));
        s.setLastSeenAt(str(row.get("lastSeenAt")));
        Object ts = row.get("registeredAt");
        if (ts != null && !ts.toString().isBlank()) {
            try {
                s.setRegisteredAt(Instant.parse(ts.toString()));
            } catch (Exception ignored) {
                // Leave the default (Instant.now()) if the stored value is unparseable.
            }
        }
        return s;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** ArcadeDB's UPDATE returns a single row {@code {count: n}} on the REST API. */
    private static long countAffected(List<Map<String, Object>> result) {
        if (result == null || result.isEmpty()) return 0;
        Object c = result.get(0).get("count");
        return c instanceof Number n ? n.longValue() : 0;
    }
}
