package io.orphos.eip.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.agent.spec.AgentManifest;
import io.orphos.eip.agent.spec.AgentRegistration;
import io.orphos.eip.agent.spec.Autonomy;
import io.orphos.eip.registry.vo.AgentSummaryDto;
import io.orphos.eip.registry.entity.AgentRegistrationDoc;
import io.orphos.eip.registry.entity.TenantFeature;
import io.orphos.eip.registry.repository.AgentRegistrationRepository;
import io.orphos.eip.registry.repository.AgentTenantPolicyRepository;
import io.orphos.eip.registry.repository.TenantFeatureRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Registry for agents (TO-BE design §6). Mirrors {@code PluginRegistryService}: agents self-register
 * their {@link AgentManifest}; per-tenant gating reuses {@link TenantFeature} with a namespaced key
 * ({@code agent:<agentId>}) and defaults <em>disabled</em>.
 *
 * <p>DEGRADED (design §6 diagram note): an agent is degraded for a tenant when not all of its
 * {@code requiredCapabilities} are advertised by some enabled, non-DOWN MCP server for that tenant.
 * The full DEGRADED <em>transition</em> tied to the runtime lands in Phase 2 (design §16.4.5); here
 * we compute it read-time so the orchestrator/admin UI can already see coverage gaps.</p>
 */
@Service
public class AgentRegistry {

    /** TenantFeature key namespace for agents. */
    public static final String KEY_PREFIX = "agent:";

    private final AgentRegistrationRepository agents;
    private final TenantFeatureRepository features;
    private final McpServerRegistry mcpServers;
    private final AgentTenantPolicyRepository policies;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentRegistry(AgentRegistrationRepository agents, TenantFeatureRepository features,
                         McpServerRegistry mcpServers, AgentTenantPolicyRepository policies) {
        this.agents = agents;
        this.features = features;
        this.mcpServers = mcpServers;
        this.policies = policies;
    }

    /** All registered agents (platform-admin inventory). {@code enabled}/{@code degraded} are not tenant-scoped here. */
    public List<AgentSummaryDto> findAll() {
        return agents.findAll().stream().map(a -> toDto(a, null, false, Set.of())).toList();
    }

    /** Agents live for a tenant: enabled for the tenant AND not heartbeat-DOWN. DEGRADED is annotated, not filtered. */
    public List<AgentSummaryDto> findActiveForTenant(UUID tenantId) {
        Set<String> liveTools = mcpServers.liveToolNamesForTenant(tenantId);
        return agents.findAll().stream()
                .filter(a -> isEnabled(tenantId, a.getAgentId()))
                .filter(a -> !"DOWN".equalsIgnoreCase(a.getStatus()))
                .map(a -> toDto(a, tenantId, true, liveTools))
                .toList();
    }

    /** Upsert an agent registration (called by each agent at startup). */
    public AgentSummaryDto register(AgentRegistration req) {
        AgentManifest m = req.manifest();
        if (m == null || m.agentId() == null || m.agentId().isBlank()) {
            throw new IllegalArgumentException("manifest.agentId is required");
        }
        AgentRegistrationDoc a = agents.findById(m.agentId()).orElseGet(AgentRegistrationDoc::new);
        a.setAgentId(m.agentId());
        a.setDisplayName(m.displayName());
        a.setVersion(m.version());
        a.setManifestJson(writeJson(m));
        a.setRequiredCapabilitiesJson(writeJson(
                m.requiredCapabilities() == null ? List.of() : m.requiredCapabilities()));
        a.setBaseUrl(req.baseUrl());
        a.setHealthUrl(req.healthUrl());
        a.setRegisteredAt(Instant.now());
        return toDto(agents.save(a), null, false, Set.of());
    }

    /** Enable or disable an agent for a tenant. */
    public void setEnabled(UUID tenantId, String agentId, boolean enabled, String changedBy) {
        if (!agents.existsById(agentId)) {
            throw new NoSuchElementException("Unknown agent: " + agentId);
        }
        String key = KEY_PREFIX + agentId;
        TenantFeature tf = features.findByTenantIdAndPluginId(tenantId, key)
                .orElse(new TenantFeature(tenantId, key, enabled, changedBy));
        tf.setEnabled(enabled);
        tf.setChangedAt(Instant.now());
        tf.setChangedBy(changedBy);
        features.save(tf);
    }

    /** Tenant gate for a single agent; defaults disabled when no row exists. */
    public boolean isEnabled(UUID tenantId, String agentId) {
        return features.findByTenantIdAndPluginId(tenantId, KEY_PREFIX + agentId)
                .map(TenantFeature::isEnabled)
                .orElse(false);
    }

    /** Set the per-tenant autonomy override for an agent (design §12 Phase 4, per-tenant policy). */
    public void setAutonomyOverride(UUID tenantId, String agentId, Autonomy autonomy, String changedBy) {
        if (!agents.existsById(agentId)) {
            throw new NoSuchElementException("Unknown agent: " + agentId);
        }
        policies.saveAutonomy(tenantId, agentId, autonomy.name(), changedBy);
    }

    /**
     * The effective autonomy for an agent + tenant: the per-tenant override if set, otherwise the
     * agent's declared manifest autonomy. This is what the runtime enforces.
     */
    public Autonomy effectiveAutonomy(UUID tenantId, String agentId) {
        Optional<String> override = policies.findAutonomy(tenantId, agentId);
        if (override.isPresent()) {
            Autonomy parsed = parseAutonomy(override.get());
            if (parsed != null) return parsed;
        }
        AgentManifest m = agents.findById(agentId).map(d -> readManifest(d.getManifestJson())).orElse(null);
        return m != null && m.autonomy() != null ? m.autonomy() : Autonomy.SUGGEST_ONLY;
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private AgentSummaryDto toDto(AgentRegistrationDoc a, UUID tenantId, boolean enabled, Set<String> liveTools) {
        AgentManifest manifest = readManifest(a.getManifestJson());
        // Apply the per-tenant autonomy override (if any) so callers see the effective autonomy.
        if (manifest != null && tenantId != null) {
            Optional<String> override = policies.findAutonomy(tenantId, a.getAgentId());
            if (override.isPresent()) {
                Autonomy eff = parseAutonomy(override.get());
                if (eff != null && eff != manifest.autonomy()) {
                    manifest = new AgentManifest(manifest.agentId(), manifest.version(), manifest.displayName(),
                            manifest.goalPromptKey(), manifest.requiredCapabilities(), manifest.optionalCapabilities(),
                            manifest.trigger(), eff, manifest.plannerUsageType());
                }
            }
        }
        List<String> required = manifest != null && manifest.requiredCapabilities() != null
                ? manifest.requiredCapabilities() : List.of();
        // Coverage only matters in a tenant context (enabled=true call); admin inventory skips it.
        List<String> missing = enabled
                ? required.stream().filter(cap -> !liveTools.contains(cap)).toList()
                : List.of();
        boolean degraded = enabled && !missing.isEmpty();
        return new AgentSummaryDto(
                a.getAgentId(), a.getDisplayName(), a.getVersion(), manifest,
                a.getBaseUrl(), a.getHealthUrl(), a.getStatus(), enabled, degraded, missing);
    }

    private AgentManifest readManifest(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, AgentManifest.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static Autonomy parseAutonomy(String s) {
        try {
            return Autonomy.valueOf(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
