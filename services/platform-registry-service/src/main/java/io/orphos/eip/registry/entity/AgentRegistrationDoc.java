package io.orphos.eip.registry.entity;

import java.time.Instant;

/**
 * A registered agent (TO-BE design §6). An agent self-registers its
 * {@link io.orphos.eip.agent.spec.AgentManifest} plus where to reach and health-check it; the
 * orchestrator discovers live agents for a tenant via {@code GET /api/platform/agents/active}.
 *
 * <p>Persisted as the {@code AgentRegistration} document type in ArcadeDB, keyed by a unique
 * {@code agentId} index — a plain POJO; the row mapping lives in
 * {@code AgentRegistrationRepository}. Mirrors {@link PluginManifest}.</p>
 *
 * <p>The full manifest is stored as {@code manifestJson} (so the wire contract can evolve without
 * schema churn); {@code requiredCapabilitiesJson} is stored alongside so the {@code active}
 * endpoint can compute capability coverage / DEGRADED without deserializing every manifest.</p>
 */
public class AgentRegistrationDoc {

    /** Stable slug: "briefing", "email-triage". */
    private String agentId;

    private String displayName;

    private String version;

    /** Full AgentManifest, serialized as JSON. */
    private String manifestJson;

    /** JSON array of required tool names — mirrors manifest.requiredCapabilities for fast coverage checks. */
    private String requiredCapabilitiesJson;

    /** Base URL the orchestrator posts /invoke to. */
    private String baseUrl;

    /** Absolute health-probe URL. */
    private String healthUrl;

    /** Heartbeat status: UP / DOWN / UNKNOWN (owned by the heartbeat poller). */
    private String status = "UNKNOWN";

    private String lastSeenAt;

    private Instant registeredAt = Instant.now();

    public AgentRegistrationDoc() {}

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getManifestJson() { return manifestJson; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }

    public String getRequiredCapabilitiesJson() { return requiredCapabilitiesJson; }
    public void setRequiredCapabilitiesJson(String requiredCapabilitiesJson) { this.requiredCapabilitiesJson = requiredCapabilitiesJson; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getHealthUrl() { return healthUrl; }
    public void setHealthUrl(String healthUrl) { this.healthUrl = healthUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(String lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
}
