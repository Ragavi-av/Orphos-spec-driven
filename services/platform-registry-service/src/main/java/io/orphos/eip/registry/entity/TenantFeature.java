package io.orphos.eip.registry.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Records whether a plugin is enabled or disabled for a specific tenant.
 * A missing row means "use the platform default" (enabled for non-built-in plugins).
 *
 * <p>Persisted as the {@code TenantFeature} document type in ArcadeDB, keyed by a
 * unique {@code (tenantId, pluginId)} index — a plain POJO; the mapping to/from
 * ArcadeDB rows lives in {@code TenantFeatureRepository}.</p>
 */
public class TenantFeature {

    private UUID tenantId;

    private String pluginId;

    private boolean enabled;

    private Instant changedAt = Instant.now();

    /** Subject (preferred_username or sub) of the user who last changed this flag. */
    private String changedBy;

    public TenantFeature() {}

    public TenantFeature(UUID tenantId, String pluginId, boolean enabled, String changedBy) {
        this.tenantId = tenantId;
        this.pluginId = pluginId;
        this.enabled = enabled;
        this.changedBy = changedBy;
        this.changedAt = Instant.now();
    }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getPluginId() { return pluginId; }
    public void setPluginId(String pluginId) { this.pluginId = pluginId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getChangedAt() { return changedAt; }
    public void setChangedAt(Instant changedAt) { this.changedAt = changedAt; }

    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
}
