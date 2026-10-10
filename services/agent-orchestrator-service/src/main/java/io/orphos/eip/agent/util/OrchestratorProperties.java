package io.orphos.eip.agent.util;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the agent orchestrator (TO-BE design §4, §7): a thin scheduler/router that
 * reads the registry and fires agents. It holds no agent or tool state of its own (invariant I4).
 */
@ConfigurationProperties(prefix = "eip.orchestrator")
public class OrchestratorProperties {

    /** Master switch for the scheduled trigger loop. */
    private boolean enabled = true;

    /** platform-registry base URL (agent discovery). */
    private String registryBaseUrl = "http://eip-platform-registry:8080";

    /** Shared internal-service token for service-to-service calls. */
    private String internalToken = "";

    /**
     * Coarse scan interval (ms) at which schedule-triggered agents are fired. Precise per-agent cron
     * evaluation is a Phase 4 enhancement; this slice fires eligible agents on each scan tick.
     */
    private long scanIntervalMs = 900_000;

    /** Enable the Kafka event listener (design §12 Phase 4). HTTP event ingress works regardless. */
    private boolean kafkaEnabled = false;

    /** Kafka topic carrying agent-triggering events ({@code eventType} + payload). */
    private String eventsTopic = "eip.agent-events";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isKafkaEnabled() { return kafkaEnabled; }
    public void setKafkaEnabled(boolean kafkaEnabled) { this.kafkaEnabled = kafkaEnabled; }

    public String getEventsTopic() { return eventsTopic; }
    public void setEventsTopic(String eventsTopic) { this.eventsTopic = eventsTopic; }

    public String getRegistryBaseUrl() { return registryBaseUrl; }
    public void setRegistryBaseUrl(String registryBaseUrl) { this.registryBaseUrl = registryBaseUrl; }

    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public long getScanIntervalMs() { return scanIntervalMs; }
    public void setScanIntervalMs(long scanIntervalMs) { this.scanIntervalMs = scanIntervalMs; }
}
