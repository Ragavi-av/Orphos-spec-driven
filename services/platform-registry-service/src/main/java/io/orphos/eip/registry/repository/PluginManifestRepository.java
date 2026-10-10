package io.orphos.eip.registry.repository;

import io.orphos.eip.registry.component.RegistryArcadeClient;
import io.orphos.eip.registry.entity.PluginManifest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ArcadeDB-backed store for the {@code PluginManifest} document type, keyed by a
 * unique {@code pluginId} index. Replaces the former Spring Data JPA repository;
 * the method names retained here ({@code findAll}, {@code findById},
 * {@code existsById}, {@code save}) keep {@code PluginRegistryService} unchanged.
 */
@Repository
public class PluginManifestRepository {

    private final RegistryArcadeClient arcade;

    public PluginManifestRepository(RegistryArcadeClient arcade) {
        this.arcade = arcade;
    }

    public List<PluginManifest> findAll() {
        return arcade.query("SELECT FROM PluginManifest", Map.of())
                .stream()
                .map(PluginManifestRepository::map)
                .toList();
    }

    public Optional<PluginManifest> findById(String pluginId) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT FROM PluginManifest WHERE pluginId = :pluginId",
                Map.of("pluginId", pluginId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0)));
    }

    public boolean existsById(String pluginId) {
        return !arcade.query(
                "SELECT pluginId FROM PluginManifest WHERE pluginId = :pluginId LIMIT 1",
                Map.of("pluginId", pluginId)).isEmpty();
    }

    /** Upsert by {@code pluginId}: UPDATE first, INSERT only when no row matched. */
    public PluginManifest save(PluginManifest m) {
        Map<String, Object> params = toParams(m);

        List<Map<String, Object>> updated = arcade.command(
                "UPDATE PluginManifest SET displayName = :displayName, version = :version, "
                        + "remoteScope = :remoteScope, remoteEntryPath = :remoteEntryPath, "
                        + "tabsJson = :tabsJson, adminSectionsJson = :adminSectionsJson, "
                        + "hasSidePanel = :hasSidePanel, builtIn = :builtIn, "
                        + "registeredAt = :registeredAt WHERE pluginId = :pluginId",
                params);

        if (countAffected(updated) == 0) {
            arcade.commandNoResult(
                    "INSERT INTO PluginManifest SET pluginId = :pluginId, displayName = :displayName, "
                            + "version = :version, remoteScope = :remoteScope, "
                            + "remoteEntryPath = :remoteEntryPath, tabsJson = :tabsJson, "
                            + "adminSectionsJson = :adminSectionsJson, hasSidePanel = :hasSidePanel, "
                            + "builtIn = :builtIn, registeredAt = :registeredAt",
                    params);
        }
        return m;
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private static Map<String, Object> toParams(PluginManifest m) {
        Map<String, Object> params = new HashMap<>();
        params.put("pluginId", m.getPluginId());
        params.put("displayName", m.getDisplayName());
        params.put("version", m.getVersion());
        params.put("remoteScope", m.getRemoteScope());
        params.put("remoteEntryPath", m.getRemoteEntryPath());
        params.put("tabsJson", m.getTabsJson());
        params.put("adminSectionsJson", m.getAdminSectionsJson());
        params.put("hasSidePanel", m.isHasSidePanel());
        params.put("builtIn", m.isBuiltIn());
        params.put("registeredAt",
                m.getRegisteredAt() != null ? m.getRegisteredAt().toString() : Instant.now().toString());
        return params;
    }

    private static PluginManifest map(Map<String, Object> row) {
        PluginManifest m = new PluginManifest();
        m.setPluginId(str(row.get("pluginId")));
        m.setDisplayName(str(row.get("displayName")));
        m.setVersion(str(row.get("version")));
        m.setRemoteScope(str(row.get("remoteScope")));
        m.setRemoteEntryPath(str(row.get("remoteEntryPath")));
        m.setTabsJson(str(row.get("tabsJson")));
        m.setAdminSectionsJson(str(row.get("adminSectionsJson")));
        m.setHasSidePanel(bool(row.get("hasSidePanel")));
        m.setBuiltIn(bool(row.get("builtIn")));
        Object ts = row.get("registeredAt");
        if (ts != null && !ts.toString().isBlank()) {
            try {
                m.setRegisteredAt(Instant.parse(ts.toString()));
            } catch (Exception ignored) {
                // Leave the default (Instant.now()) if the stored value is unparseable.
            }
        }
        return m;
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
