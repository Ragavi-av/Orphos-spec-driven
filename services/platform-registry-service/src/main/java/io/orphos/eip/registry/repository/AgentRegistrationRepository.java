package io.orphos.eip.registry.repository;

import io.orphos.eip.registry.component.RegistryArcadeClient;
import io.orphos.eip.registry.entity.AgentRegistrationDoc;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ArcadeDB-backed store for the {@code AgentRegistration} document type, keyed by a unique
 * {@code agentId} index. Mirrors {@link PluginManifestRepository}: UPDATE-then-INSERT upsert,
 * defensive row mapping.
 */
@Repository
public class AgentRegistrationRepository {

    private final RegistryArcadeClient arcade;

    public AgentRegistrationRepository(RegistryArcadeClient arcade) {
        this.arcade = arcade;
    }

    public List<AgentRegistrationDoc> findAll() {
        return arcade.query("SELECT FROM AgentRegistration", Map.of())
                .stream().map(AgentRegistrationRepository::map).toList();
    }

    public Optional<AgentRegistrationDoc> findById(String agentId) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT FROM AgentRegistration WHERE agentId = :agentId",
                Map.of("agentId", agentId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0)));
    }

    public boolean existsById(String agentId) {
        return !arcade.query(
                "SELECT agentId FROM AgentRegistration WHERE agentId = :agentId LIMIT 1",
                Map.of("agentId", agentId)).isEmpty();
    }

    /** Upsert by {@code agentId}. Leaves status/lastSeenAt to the heartbeat poller on re-register. */
    public AgentRegistrationDoc save(AgentRegistrationDoc a) {
        Map<String, Object> params = toParams(a);

        List<Map<String, Object>> updated = arcade.command(
                "UPDATE AgentRegistration SET displayName = :displayName, version = :version, "
                        + "manifestJson = :manifestJson, requiredCapabilitiesJson = :requiredCapabilitiesJson, "
                        + "baseUrl = :baseUrl, healthUrl = :healthUrl, registeredAt = :registeredAt "
                        + "WHERE agentId = :agentId",
                params);

        if (countAffected(updated) == 0) {
            arcade.commandNoResult(
                    "INSERT INTO AgentRegistration SET agentId = :agentId, displayName = :displayName, "
                            + "version = :version, manifestJson = :manifestJson, "
                            + "requiredCapabilitiesJson = :requiredCapabilitiesJson, baseUrl = :baseUrl, "
                            + "healthUrl = :healthUrl, status = :status, registeredAt = :registeredAt",
                    params);
        }
        return a;
    }

    /** Heartbeat-owned status update — leaves the manifest untouched. */
    public void updateStatus(String agentId, String status, String lastSeenAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("agentId", agentId);
        params.put("status", status);
        params.put("lastSeenAt", lastSeenAt != null ? lastSeenAt : "");
        arcade.command(
                "UPDATE AgentRegistration SET status = :status, lastSeenAt = :lastSeenAt WHERE agentId = :agentId",
                params);
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private static Map<String, Object> toParams(AgentRegistrationDoc a) {
        Map<String, Object> params = new HashMap<>();
        params.put("agentId", a.getAgentId());
        params.put("displayName", a.getDisplayName());
        params.put("version", a.getVersion());
        params.put("manifestJson", a.getManifestJson());
        params.put("requiredCapabilitiesJson", a.getRequiredCapabilitiesJson() != null ? a.getRequiredCapabilitiesJson() : "[]");
        params.put("baseUrl", a.getBaseUrl());
        params.put("healthUrl", a.getHealthUrl());
        params.put("status", a.getStatus() != null ? a.getStatus() : "UNKNOWN");
        params.put("registeredAt",
                a.getRegisteredAt() != null ? a.getRegisteredAt().toString() : Instant.now().toString());
        return params;
    }

    private static AgentRegistrationDoc map(Map<String, Object> row) {
        AgentRegistrationDoc a = new AgentRegistrationDoc();
        a.setAgentId(str(row.get("agentId")));
        a.setDisplayName(str(row.get("displayName")));
        a.setVersion(str(row.get("version")));
        a.setManifestJson(str(row.get("manifestJson")));
        a.setRequiredCapabilitiesJson(str(row.get("requiredCapabilitiesJson")));
        a.setBaseUrl(str(row.get("baseUrl")));
        a.setHealthUrl(str(row.get("healthUrl")));
        String status = str(row.get("status"));
        if (status != null && !status.isBlank()) a.setStatus(status);
        a.setLastSeenAt(str(row.get("lastSeenAt")));
        Object ts = row.get("registeredAt");
        if (ts != null && !ts.toString().isBlank()) {
            try {
                a.setRegisteredAt(Instant.parse(ts.toString()));
            } catch (Exception ignored) {
                // Keep the default (Instant.now()) on an unparseable stored value.
            }
        }
        return a;
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
