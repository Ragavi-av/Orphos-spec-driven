package io.orphos.eip.registry.controller;

import io.orphos.eip.registry.entity.TenantFeature;
import io.orphos.eip.registry.repository.TenantFeatureRepository;
import io.orphos.eip.registry.service.AgentRegistry;
import io.orphos.eip.registry.service.McpServerRegistry;
import io.orphos.eip.registry.service.PlatformServiceRegistry;
import io.orphos.eip.registry.service.PluginRegistryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-admin capability catalog + cross-tenant assignment matrix.
 *
 * <p>Returns every registered capability in one payload so the SPA can render a
 * single "Capability Catalog" tab: an informational listing (agents, MCP
 * servers, plugins, platform services) plus, for the opt-in capability types
 * (agents and MCP servers), the set of tenants each one is currently enabled
 * for. The frontend joins {@code enabledTenants} against the tenant list from
 * platform-core to draw the assignment matrix; toggles reuse the existing
 * {@code PUT /api/platform/{agents|mcp-servers}/{id}/tenants/{tenantId}/enabled}
 * endpoints.
 *
 * <p>Plugins and platform services are informational only here: plugins are
 * default-on (gated by explicit disable rows, managed in the Plugins tab) and
 * services are shared infrastructure with no per-tenant gate.
 */
@RestController
@RequestMapping("/api/platform/capabilities")
public class CapabilityCatalogController {

    private final AgentRegistry agents;
    private final McpServerRegistry servers;
    private final PluginRegistryService plugins;
    private final PlatformServiceRegistry services;
    private final TenantFeatureRepository features;

    public CapabilityCatalogController(AgentRegistry agents,
                                       McpServerRegistry servers,
                                       PluginRegistryService plugins,
                                       PlatformServiceRegistry services,
                                       TenantFeatureRepository features) {
        this.agents = agents;
        this.servers = servers;
        this.plugins = plugins;
        this.services = services;
        this.features = features;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public Map<String, Object> catalog() {
        // Group every enabled feature-gate row by its capability key so we can
        // annotate each agent/MCP server with the tenants that opted in.
        Map<String, List<String>> enabledByKey = new HashMap<>();
        for (TenantFeature tf : features.findAllEnabled()) {
            enabledByKey
                    .computeIfAbsent(tf.getPluginId(), k -> new ArrayList<>())
                    .add(tf.getTenantId().toString());
        }

        List<Map<String, Object>> agentList = agents.findAll().stream()
                .map(a -> capability(a, enabledByKey.get(AgentRegistry.KEY_PREFIX + a.agentId())))
                .toList();

        List<Map<String, Object>> serverList = servers.findAll().stream()
                .map(s -> capability(s, enabledByKey.get(McpServerRegistry.KEY_PREFIX + s.serverId())))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agents", agentList);
        out.put("mcpServers", serverList);
        out.put("plugins", plugins.findAll());
        out.put("services", services.findAll());
        return out;
    }

    private static Map<String, Object> capability(Object info, List<String> enabledTenants) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("info", info);
        m.put("enabledTenants", enabledTenants != null ? enabledTenants : List.of());
        return m;
    }
}
