package io.orphos.eip.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CORS settings shared by every service. Origins are normally pinned to the
 * SPA URL ({@code http://localhost:3001} in dev, the public hostname in
 * deployed envs). Leave the default for dev convenience.
 */
@ConfigurationProperties("eip.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of("http://localhost:3001");
    private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private List<String> allowedHeaders = List.of("*");
    private boolean allowCredentials = true;
    private long maxAgeSeconds = 3600;

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> v) { this.allowedOrigins = v; }

    public List<String> getAllowedMethods() { return allowedMethods; }
    public void setAllowedMethods(List<String> v) { this.allowedMethods = v; }

    public List<String> getAllowedHeaders() { return allowedHeaders; }
    public void setAllowedHeaders(List<String> v) { this.allowedHeaders = v; }

    public boolean isAllowCredentials() { return allowCredentials; }
    public void setAllowCredentials(boolean v) { this.allowCredentials = v; }

    public long getMaxAgeSeconds() { return maxAgeSeconds; }
    public void setMaxAgeSeconds(long v) { this.maxAgeSeconds = v; }
}
