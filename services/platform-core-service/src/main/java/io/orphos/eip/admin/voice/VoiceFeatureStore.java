package io.orphos.eip.admin.voice;

import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only view of the per-tenant voice toggle.
 *
 * <p>The toggle is owned by platform-registry ({@code TenantFeatureController}),
 * which writes it as a {@code TenantFeature} row keyed {@code feature:voice} in
 * the shared {@code orphos} database. platform-core reads the same rows directly
 * rather than calling the registry: that endpoint is platform-admin only, and a
 * startup reconciler has no user token to present.</p>
 */
@Repository
public class VoiceFeatureStore {

    /** Must match {@code TenantFeatureController.KEY_PREFIX + "voice"}. */
    static final String VOICE_KEY = "feature:voice";

    private final ArcadeDbTemplate arcade;
    private final TenantRepository tenants;

    public VoiceFeatureStore(ArcadeDbTemplate arcade, TenantRepository tenants) {
        this.arcade = arcade;
        this.tenants = tenants;
    }

    /**
     * Ids of the non-deleted tenants voice is switched on for. A deleted tenant's
     * row is kept by the registry, so it is filtered here — otherwise a tenant
     * nobody can reach would keep a 3 GB model running.
     *
     * @throws RuntimeException when ArcadeDB cannot be read (including the
     *         {@code TenantFeature} type not existing yet on a first boot)
     */
    public Set<String> enabledTenantIds() {
        Set<String> enabled = arcade.query(
                "SELECT tenantId FROM TenantFeature WHERE pluginId = :key AND enabled = true",
                Map.of("key", VOICE_KEY))
                .stream()
                .map(row -> row.get("tenantId"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toCollection(HashSet::new));
        if (enabled.isEmpty()) return enabled;

        Set<String> active = tenants.listActive().stream()
                .map(Tenant::id)
                .map(Object::toString)
                .collect(Collectors.toSet());
        enabled.retainAll(active);
        return enabled;
    }
}
