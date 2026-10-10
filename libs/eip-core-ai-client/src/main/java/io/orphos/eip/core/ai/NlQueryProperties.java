package io.orphos.eip.core.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for {@link NlQueryInternalClient}. Callers point at
 * nl-query-service's internal completion endpoint instead of embedding their
 * own LLM provider stack — nl-query-service is the platform's single AI
 * configuration authority (tenant/user provider selection, API keys, model
 * defaults all live there via its ChatProviderRegistry).
 */
@ConfigurationProperties(prefix = "eip.ai.nl-query")
public class NlQueryProperties {

    private boolean enabled = true;
    private String baseUrl = "http://eip-nl-query:8082";

    /** Shared secret — must match nl-query-service's {@code eip.security.internal-service-token}. */
    private String internalToken = "";

    private int timeoutSeconds = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
