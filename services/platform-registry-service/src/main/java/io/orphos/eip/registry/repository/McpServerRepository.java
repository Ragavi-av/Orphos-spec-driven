package io.orphos.eip.registry.repository;

import io.orphos.eip.registry.component.RegistryArcadeClient;
import io.orphos.eip.registry.entity.McpServerDoc;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ArcadeDB-backed store for the {@code McpServer} document type, keyed by a unique
 * {@code serverId} index. Mirrors {@link PluginManifestRepository}.
 */
@Repository
public class McpServerRepository {

    private final RegistryArcadeClient arcade;

    public McpServerRepository(RegistryArcadeClient arcade) {
        this.arcade = arcade;
    }

    public List<McpServerDoc> findAll() {
        return arcade.query("SELECT FROM McpServer", Map.of())
                .stream().map(McpServerRepository::map).toList();
    }

    public Optional<McpServerDoc> findById(String serverId) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT FROM McpServer WHERE serverId = :serverId",
                Map.of("serverId", serverId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0)));
    }

    public boolean existsById(String serverId) {
        return !arcade.query(
                "SELECT serverId FROM McpServer WHERE serverId = :serverId LIMIT 1",
                Map.of("serverId", serverId)).isEmpty();
    }

    /** Upsert by {@code serverId}. Leaves status/lastSeenAt to the heartbeat poller on re-register. */
    public McpServerDoc save(McpServerDoc s) {
        Map<String, Object> params = toParams(s);

        List<Map<String, Object>> updated = arcade.command(
                "UPDATE McpServer SET mcpEndpoint = :mcpEndpoint, toolsJson = :toolsJson, "
                        + "healthUrl = :healthUrl, registeredAt = :registeredAt WHERE serverId = :serverId",
                params);

        if (countAffected(updated) == 0) {
            arcade.commandNoResult(
                    "INSERT INTO McpServer SET serverId = :serverId, mcpEndpoint = :mcpEndpoint, "
                            + "toolsJson = :toolsJson, healthUrl = :healthUrl, status = :status, "
                            + "registeredAt = :registeredAt",
                    params);
        }
        return s;
    }

    /** Heartbeat-owned status update. */
    public void updateStatus(String serverId, String status, String lastSeenAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("serverId", serverId);
        params.put("status", status);
        params.put("lastSeenAt", lastSeenAt != null ? lastSeenAt : "");
        arcade.command(
                "UPDATE McpServer SET status = :status, lastSeenAt = :lastSeenAt WHERE serverId = :serverId",
                params);
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private static Map<String, Object> toParams(McpServerDoc s) {
        Map<String, Object> params = new HashMap<>();
        params.put("serverId", s.getServerId());
        params.put("mcpEndpoint", s.getMcpEndpoint());
        params.put("toolsJson", s.getToolsJson() != null ? s.getToolsJson() : "[]");
        params.put("healthUrl", s.getHealthUrl());
        params.put("status", s.getStatus() != null ? s.getStatus() : "UNKNOWN");
        params.put("registeredAt",
                s.getRegisteredAt() != null ? s.getRegisteredAt().toString() : Instant.now().toString());
        return params;
    }

    private static McpServerDoc map(Map<String, Object> row) {
        McpServerDoc s = new McpServerDoc();
        s.setServerId(str(row.get("serverId")));
        s.setMcpEndpoint(str(row.get("mcpEndpoint")));
        s.setToolsJson(str(row.get("toolsJson")));
        s.setHealthUrl(str(row.get("healthUrl")));
        String status = str(row.get("status"));
        if (status != null && !status.isBlank()) s.setStatus(status);
        s.setLastSeenAt(str(row.get("lastSeenAt")));
        Object ts = row.get("registeredAt");
        if (ts != null && !ts.toString().isBlank()) {
            try {
                s.setRegisteredAt(Instant.parse(ts.toString()));
            } catch (Exception ignored) {
                // Keep the default (Instant.now()) on an unparseable stored value.
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
