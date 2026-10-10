package io.orphos.eip.registry.service;

import io.orphos.eip.registry.vo.PluginManifestDto;
import io.orphos.eip.registry.vo.RegisterPluginRequest;
import io.orphos.eip.registry.entity.PluginManifest;
import io.orphos.eip.registry.entity.TenantFeature;
import io.orphos.eip.registry.repository.PluginManifestRepository;
import io.orphos.eip.registry.repository.TenantFeatureRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PluginRegistryService {

    private final PluginManifestRepository manifests;
    private final TenantFeatureRepository features;

    public PluginRegistryService(PluginManifestRepository manifests, TenantFeatureRepository features) {
        this.manifests = manifests;
        this.features = features;
    }

    /** All registered plugins (platform admin view). */
    public List<PluginManifestDto> findAll() {
        return manifests.findAll().stream()
                .map(m -> PluginManifestDto.from(m, true))
                .toList();
    }

    /**
     * Returns the plugins active for a tenant. Built-in plugins are always
     * included. Non-built-in plugins default to enabled unless explicitly
     * disabled via a TenantFeature row.
     */
    public List<PluginManifestDto> findActiveForTenant(UUID tenantId) {
        List<PluginManifest> all = manifests.findAll();
        Map<String, Boolean> overrides = features.findAllByTenantId(tenantId).stream()
                .collect(Collectors.toMap(TenantFeature::getPluginId, TenantFeature::isEnabled));

        return all.stream()
                .filter(m -> m.isBuiltIn() || overrides.getOrDefault(m.getPluginId(), true))
                .map(m -> PluginManifestDto.from(m, true))
                .toList();
    }

    /** Upsert a plugin manifest (called by plugin services at startup). */
    public PluginManifestDto register(RegisterPluginRequest req) {
        PluginManifest m = manifests.findById(req.pluginId()).orElse(new PluginManifest());
        m.setPluginId(req.pluginId());
        m.setDisplayName(req.displayName());
        m.setVersion(req.version());
        m.setRemoteScope(req.remoteScope());
        m.setRemoteEntryPath(req.remoteEntryPath());
        m.setTabsJson(req.tabsJson());
        m.setAdminSectionsJson(req.adminSectionsJson());
        m.setHasSidePanel(req.hasSidePanel());
        m.setBuiltIn(req.builtIn());
        m.setRegisteredAt(Instant.now());
        return PluginManifestDto.from(manifests.save(m), true);
    }

    /** Enable or disable a plugin for a specific tenant. */
    public void setEnabled(UUID tenantId, String pluginId, boolean enabled, String changedBy) {
        if (!manifests.existsById(pluginId)) {
            throw new NoSuchElementException("Unknown plugin: " + pluginId);
        }
        PluginManifest manifest = manifests.findById(pluginId).get();
        if (manifest.isBuiltIn()) {
            throw new IllegalArgumentException("Built-in plugin '" + pluginId + "' cannot be toggled");
        }
        TenantFeature tf = features.findByTenantIdAndPluginId(tenantId, pluginId)
                .orElse(new TenantFeature(tenantId, pluginId, enabled, changedBy));
        tf.setEnabled(enabled);
        tf.setChangedAt(Instant.now());
        tf.setChangedBy(changedBy);
        features.save(tf);
    }

    /** Returns the enabled state for a single plugin + tenant (backs the nginx auth_request gate). */
    public boolean isEnabled(UUID tenantId, String pluginId) {
        PluginManifest m = manifests.findById(pluginId).orElseThrow(
                () -> new NoSuchElementException("Unknown plugin: " + pluginId));
        if (m.isBuiltIn()) return true;
        return features.findByTenantIdAndPluginId(tenantId, pluginId)
                .map(TenantFeature::isEnabled)
                .orElse(true);
    }
}
