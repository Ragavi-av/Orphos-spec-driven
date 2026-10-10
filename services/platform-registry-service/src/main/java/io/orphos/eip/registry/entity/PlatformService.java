package io.orphos.eip.registry.entity;

import io.orphos.eip.registry.component.RegistrySchemaBootstrap;
import java.time.Instant;

/**
 * A platform <em>service</em> in the tier model (see the Modular Agents & MCP
 * design, §16): substrate → control plane → <b>shared bases</b> → plugins.
 * Unlike {@link PluginManifest} — which describes a Module-Federation UI remote —
 * this describes a backend capability provider such as the nl-query AI gateway.
 *
 * <p>Registered (today: seeded by {@code RegistrySchemaBootstrap}) with a health
 * URL and the capabilities it advertises, so consumers can discover live bases
 * and so a base that fails its heartbeat is reported DOWN — letting dependent
 * agents degrade gracefully rather than hard-fail (invariant I7).</p>
 *
 * <p>Persisted as the {@code PlatformService} document type in ArcadeDB (shared
 * {@code orphos} database); the row mapping lives in
 * {@code PlatformServiceRepository}.</p>
 */
public class PlatformService {

    /** Stable slug: "nl-query-gateway". */
    private String serviceId;

    private String displayName;

    /** Tier bucket — currently always "BASE" (a Tier-2 shared base). */
    private String tier = "BASE";

    private String version;

    /** Absolute health-probe URL, e.g. http://eip-nl-query:8082/api/nl-query/health. */
    private String healthUrl;

    /** JSON array of advertised capability names, e.g. ["ai.answer","ai.plan","ai.classify","ai.embed"]. */
    private String capabilitiesJson;

    /** Last observed heartbeat status: UP | DOWN | UNKNOWN. */
    private String status = "UNKNOWN";

    /** ISO-8601 instant of the last successful health probe, or null. */
    private String lastSeenAt;

    private Instant registeredAt = Instant.now();

    public PlatformService() {}

    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getHealthUrl() { return healthUrl; }
    public void setHealthUrl(String healthUrl) { this.healthUrl = healthUrl; }

    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(String lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
}
