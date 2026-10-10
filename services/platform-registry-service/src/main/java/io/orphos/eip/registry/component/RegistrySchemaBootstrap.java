package io.orphos.eip.registry.component;

import io.orphos.eip.registry.entity.PlatformService;
import io.orphos.eip.registry.entity.PluginManifest;
import io.orphos.eip.registry.entity.TenantFeature;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the {@code PluginManifest} and {@code TenantFeature} document types in
 * ArcadeDB on startup (formerly the H2 {@code plugin_manifest} / {@code tenant_feature}
 * tables + {@code data.sql} seed), then seeds the well-known plugin rows so the
 * registry works before any plugin container has started.
 *
 * <p>ArcadeDB notes:
 * <ul>
 *   <li>{@code CREATE DOCUMENT TYPE} supports {@code IF NOT EXISTS} — idempotent.</li>
 *   <li>{@code CREATE PROPERTY} does NOT support {@code IF NOT EXISTS} — a duplicate
 *       throws, so each is wrapped in {@link #tryExec}.</li>
 *   <li>{@code CREATE INDEX} supports {@code IF NOT EXISTS} and requires the
 *       properties to exist first.</li>
 * </ul>
 *
 * <p>Seed rows are inserted only when the {@code pluginId} is absent, so a live
 * registration (POST /register with the real version + remoteEntryPath) is never
 * clobbered by a restart.</p>
 */
@Component
@Order(1)
public class RegistrySchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(RegistrySchemaBootstrap.class);

    private final RegistryArcadeClient arcade;

    /** Carries the connection binder's schema mode ({@code eip.connections.schema-mode}). */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    public RegistrySchemaBootstrap(RegistryArcadeClient arcade) {
        this.arcade = arcade;
    }

    @Override
    public void run(ApplicationArguments args) {
        // EXISTING schema mode → the assigned database is operator-managed; do not
        // create types or seed rows (both are writes against a database the
        // operator has told us they own and have already provisioned).
        if (io.orphos.eip.core.dbregistry.SchemaModeGuard.isExisting(environment)) {
            log.info("Platform-registry schema bootstrap skipped: assigned connection uses schemaMode=EXISTING "
                    + "(operator-managed schema) — no DDL or seed rows applied.");
            return;
        }
        log.info("Applying platform-registry schema to ArcadeDB...");
        try {
            createPluginManifest();
            createTenantFeature();
            createPlatformService();
            createAgentRegistration();
            createMcpServer();
            createAgentTenantPolicy();
            seedWellKnownPlugins();
            seedPlatformServices();
            log.info("Platform-registry schema ready.");
        } catch (Exception e) {
            log.error("Schema bootstrap failed — plugin registry will not persist: {}", e.getMessage(), e);
        }
    }

    private void createPlatformService() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE PlatformService IF NOT EXISTS", Map.of());

        for (String prop : List.of(
                "CREATE PROPERTY PlatformService.serviceId        STRING",
                "CREATE PROPERTY PlatformService.displayName      STRING",
                "CREATE PROPERTY PlatformService.tier             STRING",
                "CREATE PROPERTY PlatformService.version          STRING",
                "CREATE PROPERTY PlatformService.healthUrl        STRING",
                "CREATE PROPERTY PlatformService.capabilitiesJson STRING",
                "CREATE PROPERTY PlatformService.status           STRING",
                "CREATE PROPERTY PlatformService.lastSeenAt        STRING",
                "CREATE PROPERTY PlatformService.registeredAt     STRING")) {
            tryExec(prop);
        }

        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON PlatformService (serviceId) UNIQUE", Map.of());
    }

    /**
     * Seed the Tier-2 shared bases so they exist before their containers self-register.
     * Today the nl-query AI gateway is the sole base; add embedding/reasoning bases here
     * as they are promoted. Consistent with how well-known plugins are seeded above.
     */
    private void seedPlatformServices() {
        seedService("nl-query-gateway", "NL Query — AI Gateway", "BASE", "1.0.0",
                "http://eip-nl-query:8082/api/nl-query/health",
                "[\"ai.answer\",\"ai.plan\",\"ai.classify\",\"ai.embed\"]");
        // Voice is seeded even though voice-service also self-registers on startup:
        // it is an opt-in functionality whose container is normally STOPPED, so
        // without a seed row it would be missing from the Capability Catalog
        // entirely until the first time someone started it — exactly the moment an
        // admin is trying to find out whether the platform has voice at all. The
        // heartbeat poller reports it DOWN until it is actually running.
        seedService("voice-transcription", "Voice — Speech to Text", "BASE", "1.0.0",
                "http://eip-voice:8099/actuator/health",
                "[\"voice.transcribe\",\"voice.status\"]");
    }

    /** Insert a seed service row only when the serviceId is not already present. */
    private void seedService(String serviceId, String displayName, String tier, String version,
                             String healthUrl, String capabilitiesJson) {
        try {
            boolean exists = !arcade.query(
                    "SELECT serviceId FROM PlatformService WHERE serviceId = :serviceId LIMIT 1",
                    Map.of("serviceId", serviceId)).isEmpty();
            if (exists) return;

            Map<String, Object> params = new HashMap<>();
            params.put("serviceId", serviceId);
            params.put("displayName", displayName);
            params.put("tier", tier);
            params.put("version", version);
            params.put("healthUrl", healthUrl);
            params.put("capabilitiesJson", capabilitiesJson);
            params.put("status", "UNKNOWN");
            params.put("registeredAt", Instant.now().toString());

            arcade.commandNoResult(
                    "INSERT INTO PlatformService SET serviceId = :serviceId, displayName = :displayName, "
                            + "tier = :tier, version = :version, healthUrl = :healthUrl, "
                            + "capabilitiesJson = :capabilitiesJson, status = :status, "
                            + "registeredAt = :registeredAt",
                    params);
            log.info("Seeded platform service '{}'.", serviceId);
        } catch (Exception e) {
            log.warn("Failed to seed platform service '{}': {}", serviceId, e.getMessage());
        }
    }

    /** Agent registry document type (design §6). Full manifest stored as JSON in {@code manifestJson}. */
    private void createAgentRegistration() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE AgentRegistration IF NOT EXISTS", Map.of());

        for (String prop : List.of(
                "CREATE PROPERTY AgentRegistration.agentId                  STRING",
                "CREATE PROPERTY AgentRegistration.displayName              STRING",
                "CREATE PROPERTY AgentRegistration.version                  STRING",
                "CREATE PROPERTY AgentRegistration.manifestJson             STRING",
                "CREATE PROPERTY AgentRegistration.requiredCapabilitiesJson STRING",
                "CREATE PROPERTY AgentRegistration.baseUrl                  STRING",
                "CREATE PROPERTY AgentRegistration.healthUrl                STRING",
                "CREATE PROPERTY AgentRegistration.status                   STRING",
                "CREATE PROPERTY AgentRegistration.lastSeenAt               STRING",
                "CREATE PROPERTY AgentRegistration.registeredAt             STRING")) {
            tryExec(prop);
        }

        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON AgentRegistration (agentId) UNIQUE", Map.of());
    }

    /** MCP tool-server registry document type (design §6). Advertised tools stored as JSON in {@code toolsJson}. */
    private void createMcpServer() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE McpServer IF NOT EXISTS", Map.of());

        for (String prop : List.of(
                "CREATE PROPERTY McpServer.serverId     STRING",
                "CREATE PROPERTY McpServer.mcpEndpoint  STRING",
                "CREATE PROPERTY McpServer.toolsJson    STRING",
                "CREATE PROPERTY McpServer.healthUrl    STRING",
                "CREATE PROPERTY McpServer.status       STRING",
                "CREATE PROPERTY McpServer.lastSeenAt   STRING",
                "CREATE PROPERTY McpServer.registeredAt STRING")) {
            tryExec(prop);
        }

        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON McpServer (serverId) UNIQUE", Map.of());
    }

    /** Per-tenant agent autonomy override (design §12 Phase 4). */
    private void createAgentTenantPolicy() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE AgentTenantPolicy IF NOT EXISTS", Map.of());

        for (String prop : List.of(
                "CREATE PROPERTY AgentTenantPolicy.tenantId  STRING",
                "CREATE PROPERTY AgentTenantPolicy.agentId   STRING",
                "CREATE PROPERTY AgentTenantPolicy.autonomy  STRING",
                "CREATE PROPERTY AgentTenantPolicy.changedAt STRING",
                "CREATE PROPERTY AgentTenantPolicy.changedBy STRING")) {
            tryExec(prop);
        }

        arcade.commandNoResult(
                "CREATE INDEX IF NOT EXISTS ON AgentTenantPolicy (tenantId, agentId) UNIQUE", Map.of());
    }

    private void createPluginManifest() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE PluginManifest IF NOT EXISTS", Map.of());

        for (String prop : List.of(
                "CREATE PROPERTY PluginManifest.pluginId          STRING",
                "CREATE PROPERTY PluginManifest.displayName       STRING",
                "CREATE PROPERTY PluginManifest.version           STRING",
                "CREATE PROPERTY PluginManifest.remoteScope       STRING",
                "CREATE PROPERTY PluginManifest.remoteEntryPath   STRING",
                "CREATE PROPERTY PluginManifest.tabsJson          STRING",
                "CREATE PROPERTY PluginManifest.adminSectionsJson STRING",
                "CREATE PROPERTY PluginManifest.hasSidePanel      BOOLEAN",
                "CREATE PROPERTY PluginManifest.builtIn           BOOLEAN",
                "CREATE PROPERTY PluginManifest.registeredAt      STRING")) {
            tryExec(prop);
        }

        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON PluginManifest (pluginId) UNIQUE", Map.of());
    }

    private void createTenantFeature() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE TenantFeature IF NOT EXISTS", Map.of());

        for (String prop : List.of(
                "CREATE PROPERTY TenantFeature.tenantId  STRING",
                "CREATE PROPERTY TenantFeature.pluginId  STRING",
                "CREATE PROPERTY TenantFeature.enabled   BOOLEAN",
                "CREATE PROPERTY TenantFeature.changedAt STRING",
                "CREATE PROPERTY TenantFeature.changedBy STRING")) {
            tryExec(prop);
        }

        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON TenantFeature (tenantId) NOTUNIQUE", Map.of());
        // One row per (tenant, plugin) — makes the UPDATE-then-INSERT upsert safe.
        arcade.commandNoResult(
                "CREATE INDEX IF NOT EXISTS ON TenantFeature (tenantId, pluginId) UNIQUE", Map.of());
    }

    // ── seed data (was data.sql) ─────────────────────────────────────────────

    private void seedWellKnownPlugins() {
        seed("ai-copilot", "AI Co-pilot", "plugin_ai_copilot",
                "/remotes/ai-copilot/assets/remoteEntry.js",
                "[{\"key\":\"ai-history\",\"labelKey\":\"nav.aiHistory\",\"roles\":[\"user\",\"admin\"]}]",
                "[\"LLMPromptsTab\",\"AIProvidersTab\",\"QueryHistoryTab\"]",
                true, false);

        seed("email-scanner", "Email Scanner", "plugin_email_scanner",
                "/remotes/email-scanner/assets/remoteEntry.js",
                "[{\"key\":\"tasks-for-action\",\"labelKey\":\"nav.tasks\",\"roles\":[\"user\",\"admin\"]}," +
                "{\"key\":\"upcoming-meetings\",\"labelKey\":\"nav.meetings\",\"roles\":[\"user\",\"admin\"]},"
                        + "{\"key\":\"notifications\",\"labelKey\":\"nav.notifications\",\"roles\":[\"user\",\"admin\",\"Viewer\"]}]",
                "[\"EmailConfigTab\",\"MailMonitorTab\",\"EmailAITab\"]",
                false, false);

        seed("doc-ingest", "Document Ingest", "plugin_doc_ingest",
                "/remotes/doc-ingest/assets/remoteEntry.js",
                "[{\"key\":\"doc-search\",\"labelKey\":\"nav.docSearch\",\"roles\":[\"user\",\"admin\",\"Viewer\"]}]",
                "[\"DocIngestListTab\",\"DocIngestQueriesTab\",\"DocIngestSettingsTab\",\"IngestionDefaultsTab\",\"SourceConnectorsTab\",\"DocumentAITab\"]",
                false, false);

        seed("rag-search", "RAG Search & Widgets", "plugin_rag_search",
                "/remotes/rag-search/assets/remoteEntry.js",
                "[]", "[\"SearchSettingsTab\"]",
                false, false);

        seed("widgets", "Widgets", "plugin_widgets",
                "/remotes/widgets/assets/remoteEntry.js",
                "[]", "[\"DashboardDefaultsTab\"]",
                false, false);

        seed("decision-log", "Decision Log", "plugin_decision_log",
                "/remotes/decision-log/assets/remoteEntry.js",
                "[{\"key\":\"decision-log\",\"labelKey\":\"nav.decisionLog\",\"roles\":[\"user\",\"admin\"]}]",
                "[]", false, false);
    }

    /** Insert a seed row only when the plugin is not already present. */
    private void seed(String pluginId, String displayName, String remoteScope, String remoteEntryPath,
                      String tabsJson, String adminSectionsJson, boolean hasSidePanel, boolean builtIn) {
        try {
            boolean exists = !arcade.query(
                    "SELECT pluginId FROM PluginManifest WHERE pluginId = :pluginId LIMIT 1",
                    Map.of("pluginId", pluginId)).isEmpty();
            if (exists) return;

            Map<String, Object> params = new HashMap<>();
            params.put("pluginId", pluginId);
            params.put("displayName", displayName);
            params.put("version", "1.0.0");
            params.put("remoteScope", remoteScope);
            params.put("remoteEntryPath", remoteEntryPath);
            params.put("tabsJson", tabsJson);
            params.put("adminSectionsJson", adminSectionsJson);
            params.put("hasSidePanel", hasSidePanel);
            params.put("builtIn", builtIn);
            params.put("registeredAt", Instant.now().toString());

            arcade.commandNoResult(
                    "INSERT INTO PluginManifest SET pluginId = :pluginId, displayName = :displayName, "
                            + "version = :version, remoteScope = :remoteScope, "
                            + "remoteEntryPath = :remoteEntryPath, tabsJson = :tabsJson, "
                            + "adminSectionsJson = :adminSectionsJson, hasSidePanel = :hasSidePanel, "
                            + "builtIn = :builtIn, registeredAt = :registeredAt",
                    params);
            log.info("Seeded well-known plugin '{}'.", pluginId);
        } catch (Exception e) {
            log.warn("Failed to seed plugin '{}': {}", pluginId, e.getMessage());
        }
    }

    private void tryExec(String sql) {
        try {
            arcade.commandNoResult(sql, Map.of());
        } catch (Exception e) {
            log.debug("DDL skipped (likely already exists): {} — {}", sql.trim(), e.getMessage());
        }
    }
}
