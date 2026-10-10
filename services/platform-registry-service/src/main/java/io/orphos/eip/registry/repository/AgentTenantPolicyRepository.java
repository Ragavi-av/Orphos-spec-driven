package io.orphos.eip.registry.repository;

import io.orphos.eip.registry.component.RegistryArcadeClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-tenant agent policy store (TO-BE design §12 Phase 4): the tenant-specific autonomy override
 * for an agent, keyed by {@code (tenantId, agentId)}. A missing row means "use the agent's declared
 * manifest autonomy". Persisted as the {@code AgentTenantPolicy} document type in ArcadeDB.
 */
@Repository
public class AgentTenantPolicyRepository {

    private final RegistryArcadeClient arcade;

    public AgentTenantPolicyRepository(RegistryArcadeClient arcade) {
        this.arcade = arcade;
    }

    /** The autonomy override for a tenant+agent, or empty when none is set. */
    public Optional<String> findAutonomy(UUID tenantId, String agentId) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT autonomy FROM AgentTenantPolicy WHERE tenantId = :tenantId AND agentId = :agentId",
                Map.of("tenantId", tenantId.toString(), "agentId", agentId));
        if (rows.isEmpty()) return Optional.empty();
        Object a = rows.get(0).get("autonomy");
        return a == null || a.toString().isBlank() ? Optional.empty() : Optional.of(a.toString());
    }

    /** Upsert the autonomy override for a tenant+agent. */
    public void saveAutonomy(UUID tenantId, String agentId, String autonomy, String changedBy) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId.toString());
        params.put("agentId", agentId);
        params.put("autonomy", autonomy);
        params.put("changedAt", Instant.now().toString());
        params.put("changedBy", changedBy != null ? changedBy : "");

        List<Map<String, Object>> updated = arcade.command(
                "UPDATE AgentTenantPolicy SET autonomy = :autonomy, changedAt = :changedAt, "
                        + "changedBy = :changedBy WHERE tenantId = :tenantId AND agentId = :agentId",
                params);

        if (countAffected(updated) == 0) {
            arcade.commandNoResult(
                    "INSERT INTO AgentTenantPolicy SET tenantId = :tenantId, agentId = :agentId, "
                            + "autonomy = :autonomy, changedAt = :changedAt, changedBy = :changedBy",
                    params);
        }
    }

    private static long countAffected(List<Map<String, Object>> result) {
        if (result == null || result.isEmpty()) return 0;
        Object c = result.get(0).get("count");
        return c instanceof Number n ? n.longValue() : 0;
    }
}
