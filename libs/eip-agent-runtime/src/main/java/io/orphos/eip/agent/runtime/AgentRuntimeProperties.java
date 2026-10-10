package io.orphos.eip.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for {@code eip-agent-runtime} (TO-BE design §5.3). A standalone agent overrides
 * these in its own {@code application.yml}; the defaults target the in-cluster service names.
 */
@ConfigurationProperties(prefix = "eip.agent.runtime")
public class AgentRuntimeProperties {

    /** Base URL of platform-registry-service (agent + MCP discovery, self-registration). */
    private String registryBaseUrl = "http://eip-platform-registry:8080";

    /**
     * Ops-editable goal prompts, keyed by an agent manifest's {@code goalPromptKey} (design §5.2).
     * Keeps prompts out of compiled code; resolved by {@link GoalPromptResolver}.
     */
    private Map<String, String> prompts = new LinkedHashMap<>();

    /** Shared internal-service token — must match the platform {@code internal-service-token}. */
    private String internalToken = "";

    /** This agent's own base URL, advertised at registration so the orchestrator can call /invoke. */
    private String baseUrl = "";

    /** Path appended to {@link #baseUrl} for the health probe the registry heartbeat polls. */
    private String healthPath = "/actuator/health";

    /** Self-register the agent's manifest at startup. */
    private boolean registerOnStartup = true;

    /** Planner-loop settings (design §8, §9). */
    private final Planner planner = new Planner();

    public static class Planner {
        /** Tool-calling-capable model transport. Open item §15: production routes via nl-query. */
        private String baseUrl = "http://eip-ollama:11434";
        private String model = "qwen2.5:7b";
        /** Max plan→act→observe iterations before the loop stops (budget, design §9). */
        private int maxSteps = 8;
        private int timeoutSeconds = 120;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxSteps() { return maxSteps; }
        public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public String getRegistryBaseUrl() { return registryBaseUrl; }
    public void setRegistryBaseUrl(String registryBaseUrl) { this.registryBaseUrl = registryBaseUrl; }

    public Map<String, String> getPrompts() { return prompts; }
    public void setPrompts(Map<String, String> prompts) {
        this.prompts = prompts == null ? new LinkedHashMap<>() : prompts;
    }

    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getHealthPath() { return healthPath; }
    public void setHealthPath(String healthPath) { this.healthPath = healthPath; }

    public boolean isRegisterOnStartup() { return registerOnStartup; }
    public void setRegisterOnStartup(boolean registerOnStartup) { this.registerOnStartup = registerOnStartup; }

    public Planner getPlanner() { return planner; }
}
