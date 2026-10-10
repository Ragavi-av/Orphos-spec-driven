package io.orphos.eip.registry.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.agent.spec.McpServerRegistration;
import io.orphos.eip.agent.spec.ToolDescriptor;
import io.orphos.eip.registry.vo.McpServerSummaryDto;
import io.orphos.eip.registry.entity.McpServerDoc;
import io.orphos.eip.registry.entity.TenantFeature;
import io.orphos.eip.registry.repository.McpServerRepository;
import io.orphos.eip.registry.repository.TenantFeatureRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Registry for MCP tool servers (TO-BE design §6). Mirrors {@code PluginRegistryService}: servers
 * self-register their endpoint + advertised tools; per-tenant gating reuses the {@link TenantFeature}
 * model with a namespaced key ({@code mcp:<serverId>}) so it never collides with plugin or agent
 * gates. Gating defaults to <em>disabled</em> (a plugin defaults enabled; an MCP server must be
 * explicitly turned on for a tenant).
 */
@Service
public class McpServerRegistry {

    /** TenantFeature key namespace for MCP servers. */
    public static final String KEY_PREFIX = "mcp:";

    private final McpServerRepository servers;
    private final TenantFeatureRepository features;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpServerRegistry(McpServerRepository servers, TenantFeatureRepository features) {
        this.servers = servers;
        this.features = features;
    }

    /** All registered servers (platform-admin inventory). {@code enabled} is not tenant-scoped here. */
    public List<McpServerSummaryDto> findAll() {
        return servers.findAll().stream().map(s -> toDto(s, false)).toList();
    }

    /** Servers live for a tenant: enabled for the tenant AND not heartbeat-DOWN. */
    public List<McpServerSummaryDto> findActiveForTenant(UUID tenantId) {
        return servers.findAll().stream()
                .filter(s -> isEnabled(tenantId, s.getServerId()))
                .filter(s -> !"DOWN".equalsIgnoreCase(s.getStatus()))
                .map(s -> toDto(s, true))
                .toList();
    }

    /** Upsert an MCP-server registration (called by each server at startup). */
    public McpServerSummaryDto register(McpServerRegistration req) {
        if (req.serverId() == null || req.serverId().isBlank()) {
            throw new IllegalArgumentException("serverId is required");
        }
        McpServerDoc s = servers.findById(req.serverId()).orElseGet(McpServerDoc::new);
        s.setServerId(req.serverId());
        s.setMcpEndpoint(req.mcpEndpoint());
        s.setHealthUrl(req.healthUrl());
        s.setToolsJson(writeJson(req.tools()));
        s.setRegisteredAt(Instant.now());
        return toDto(servers.save(s), false);
    }

    /** Enable or disable a server for a tenant. */
    public void setEnabled(UUID tenantId, String serverId, boolean enabled, String changedBy) {
        if (!servers.existsById(serverId)) {
            throw new NoSuchElementException("Unknown MCP server: " + serverId);
        }
        String key = KEY_PREFIX + serverId;
        TenantFeature tf = features.findByTenantIdAndPluginId(tenantId, key)
                .orElse(new TenantFeature(tenantId, key, enabled, changedBy));
        tf.setEnabled(enabled);
        tf.setChangedAt(Instant.now());
        tf.setChangedBy(changedBy);
        features.save(tf);
    }

    /** Tenant gate for a single server; defaults disabled when no row exists. */
    public boolean isEnabled(UUID tenantId, String serverId) {
        return features.findByTenantIdAndPluginId(tenantId, KEY_PREFIX + serverId)
                .map(TenantFeature::isEnabled)
                .orElse(false);
    }

    /** Tool names advertised by servers that are enabled for a tenant and not DOWN (for coverage checks). */
    public java.util.Set<String> liveToolNamesForTenant(UUID tenantId) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (McpServerDoc s : servers.findAll()) {
            if (!isEnabled(tenantId, s.getServerId())) continue;
            if ("DOWN".equalsIgnoreCase(s.getStatus())) continue;
            for (ToolDescriptor t : readTools(s.getToolsJson())) {
                if (t.name() != null) names.add(t.name());
            }
        }
        return names;
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private McpServerSummaryDto toDto(McpServerDoc s, boolean enabled) {
        return new McpServerSummaryDto(
                s.getServerId(), s.getMcpEndpoint(), readTools(s.getToolsJson()),
                s.getHealthUrl(), s.getStatus(), enabled);
    }

    private List<ToolDescriptor> readTools(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<ToolDescriptor>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(List<ToolDescriptor> tools) {
        try {
            return mapper.writeValueAsString(tools == null ? List.of() : tools);
        } catch (Exception e) {
            return "[]";
        }
    }
}
