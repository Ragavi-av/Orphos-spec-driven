package io.orphos.eip.registry.repository;

import io.orphos.eip.registry.component.RegistryArcadeClient;
import io.orphos.eip.registry.entity.TenantFeature;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ArcadeDB-backed store for the {@code TenantFeature} document type, keyed by a
 * unique {@code (tenantId, pluginId)} index. Replaces the former Spring Data JPA
 * repository; the method names are retained so {@code PluginRegistryService} is
 * unchanged.
 */
@Repository
public class TenantFeatureRepository {

    private final RegistryArcadeClient arcade;

    public TenantFeatureRepository(RegistryArcadeClient arcade) {
        this.arcade = arcade;
    }

    public List<TenantFeature> findAllByTenantId(UUID tenantId) {
        return arcade.query(
                "SELECT FROM TenantFeature WHERE tenantId = :tenantId",
                Map.of("tenantId", tenantId.toString()))
                .stream()
                .map(TenantFeatureRepository::map)
                .toList();
    }

    /** Every enabled feature-gate row across all tenants — backs the platform-admin assignment matrix. */
    public List<TenantFeature> findAllEnabled() {
        return arcade.query("SELECT FROM TenantFeature WHERE enabled = true", Map.of())
                .stream()
                .map(TenantFeatureRepository::map)
                .toList();
    }

    public Optional<TenantFeature> findByTenantIdAndPluginId(UUID tenantId, String pluginId) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT FROM TenantFeature WHERE tenantId = :tenantId AND pluginId = :pluginId",
                Map.of("tenantId", tenantId.toString(), "pluginId", pluginId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0)));
    }

    /** Upsert by {@code (tenantId, pluginId)}: UPDATE first, INSERT only when no row matched. */
    public TenantFeature save(TenantFeature tf) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tf.getTenantId().toString());
        params.put("pluginId", tf.getPluginId());
        params.put("enabled", tf.isEnabled());
        params.put("changedAt",
                tf.getChangedAt() != null ? tf.getChangedAt().toString() : Instant.now().toString());
        params.put("changedBy", tf.getChangedBy() != null ? tf.getChangedBy() : "");

        List<Map<String, Object>> updated = arcade.command(
                "UPDATE TenantFeature SET enabled = :enabled, changedAt = :changedAt, "
                        + "changedBy = :changedBy WHERE tenantId = :tenantId AND pluginId = :pluginId",
                params);

        if (countAffected(updated) == 0) {
            arcade.commandNoResult(
                    "INSERT INTO TenantFeature SET tenantId = :tenantId, pluginId = :pluginId, "
                            + "enabled = :enabled, changedAt = :changedAt, changedBy = :changedBy",
                    params);
        }
        return tf;
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private static TenantFeature map(Map<String, Object> row) {
        TenantFeature tf = new TenantFeature();
        Object tenant = row.get("tenantId");
        if (tenant != null) tf.setTenantId(UUID.fromString(tenant.toString()));
        tf.setPluginId(str(row.get("pluginId")));
        tf.setEnabled(bool(row.get("enabled")));
        Object ts = row.get("changedAt");
        if (ts != null && !ts.toString().isBlank()) {
            try {
                tf.setChangedAt(Instant.parse(ts.toString()));
            } catch (Exception ignored) {
                // Keep the default (Instant.now()) on an unparseable stored value.
            }
        }
        tf.setChangedBy(str(row.get("changedBy")));
        return tf;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && Boolean.parseBoolean(o.toString());
    }

    /** ArcadeDB's UPDATE returns a single row {@code {count: n}} on the REST API. */
    private static long countAffected(List<Map<String, Object>> result) {
        if (result == null || result.isEmpty()) return 0;
        Object c = result.get(0).get("count");
        return c instanceof Number n ? n.longValue() : 0;
    }
}
