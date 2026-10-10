package io.orphos.eip.registry.entity;

import io.orphos.eip.registry.component.RegistrySchemaBootstrap;
import java.time.Instant;

/**
 * Describes a Module Federation remote plugin — its JS scope, remote entry path,
 * and the tab/panel slots it contributes to the shell.
 *
 * Plugins self-register at startup via POST /api/platform/plugins/register.
 * The well-known plugins are seeded by {@code RegistrySchemaBootstrap} so they
 * exist before their containers come up.
 *
 * <p>Persisted as the {@code PluginManifest} document type in ArcadeDB (shared
 * {@code orphos} database) — this is a plain POJO; the mapping to/from ArcadeDB
 * rows lives in {@code PluginManifestRepository}.</p>
 */
public class PluginManifest {

    /** Stable slug used everywhere: "ai-copilot", "email-scanner", etc. */
    private String pluginId;

    private String displayName;

    private String version;

    /**
     * JS identifier used as the Webpack/Vite federation scope name.
     * Must be a valid JS identifier (no hyphens): "plugin_ai_copilot".
     */
    private String remoteScope;

    /**
     * Path (relative to the origin) to the remoteEntry.js served by this plugin.
     * In production: /remotes/{pluginId}/assets/remoteEntry.js (Traefik routes).
     * In dev: full URL including port.
     */
    private String remoteEntryPath;

    /**
     * JSON array of tab definitions the plugin contributes to the shell nav.
     * Schema: [{"key":"ai-copilot","labelKey":"nav.aiCopilot","roles":["user","admin"]}]
     */
    private String tabsJson;

    /**
     * JSON array of admin section IDs this plugin contributes to AdminPage.
     * E.g. ["LLMPromptsTab","AIProvidersTab"]
     */
    private String adminSectionsJson;

    /** True when this plugin also occupies the right side-panel slot (AI Co-pilot). */
    private boolean hasSidePanel = false;

    /**
     * Core plugins (admin, notifications) are always enabled for every tenant
     * and cannot be disabled via the API.
     */
    private boolean builtIn = false;

    private Instant registeredAt = Instant.now();

    public PluginManifest() {}

    public String getPluginId() { return pluginId; }
    public void setPluginId(String pluginId) { this.pluginId = pluginId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getRemoteScope() { return remoteScope; }
    public void setRemoteScope(String remoteScope) { this.remoteScope = remoteScope; }

    public String getRemoteEntryPath() { return remoteEntryPath; }
    public void setRemoteEntryPath(String remoteEntryPath) { this.remoteEntryPath = remoteEntryPath; }

    public String getTabsJson() { return tabsJson; }
    public void setTabsJson(String tabsJson) { this.tabsJson = tabsJson; }

    public String getAdminSectionsJson() { return adminSectionsJson; }
    public void setAdminSectionsJson(String adminSectionsJson) { this.adminSectionsJson = adminSectionsJson; }

    public boolean isHasSidePanel() { return hasSidePanel; }
    public void setHasSidePanel(boolean hasSidePanel) { this.hasSidePanel = hasSidePanel; }

    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
}
