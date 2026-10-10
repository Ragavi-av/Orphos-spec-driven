package io.orphos.eip.common.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Tunables for {@link TenantResolutionFilter}.
 *
 * <p>Defaults are safe for dev: the filter is enabled, the JWT claim is
 * {@code tenant_id} (matching the Keycloak protocol mapper in {@code eip-realm.json}),
 * and {@code X-Tenant-Id} headers are accepted only on the loopback interface.
 * Production deployments should pin {@link #getTrustedHeaderSources()} to the
 * known internal CIDR ranges or the service-mesh ingress.</p>
 */
@ConfigurationProperties(prefix = "eip.tenant")
public class TenantProperties {

    /** Master switch. When {@code false} the filter is registered but no-ops. */
    private boolean enabled = true;

    /** JWT claim name carrying the tenant UUID. Must match the Keycloak protocol mapper. */
    private String jwtClaim = "tenant_id";

    /** Inbound request header used for cross-service tenant propagation. */
    private String headerName = "X-Tenant-Id";

    /**
     * Remote-address prefixes from which an unauthenticated {@code X-Tenant-Id}
     * header is accepted. Empty list means "never trust the header without a JWT".
     * Default is loopback only.
     */
    private List<String> trustedHeaderSources = List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    /**
     * Paths the filter should skip entirely (no context bound, no lookup).
     * Defaults match the actuator + Swagger probes already permitted by every
     * service's {@code SecurityConfig}.
     */
    private List<String> excludePathPrefixes = List.of(
            "/actuator/", "/v3/api-docs", "/swagger-ui", "/swagger-ui.html");

    /**
     * Realm whose JWTs identify platform-superadmins. A principal authenticated
     * via this realm is allowed to act cross-tenant by supplying
     * {@code X-Tenant-Id} explicitly.
     */
    private String platformAdminRealm = "platform";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getJwtClaim() { return jwtClaim; }
    public void setJwtClaim(String jwtClaim) { this.jwtClaim = jwtClaim; }

    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }

    public List<String> getTrustedHeaderSources() { return trustedHeaderSources; }
    public void setTrustedHeaderSources(List<String> trustedHeaderSources) {
        this.trustedHeaderSources = trustedHeaderSources;
    }

    public List<String> getExcludePathPrefixes() { return excludePathPrefixes; }
    public void setExcludePathPrefixes(List<String> excludePathPrefixes) {
        this.excludePathPrefixes = excludePathPrefixes;
    }

    public String getPlatformAdminRealm() { return platformAdminRealm; }
    public void setPlatformAdminRealm(String platformAdminRealm) {
        this.platformAdminRealm = platformAdminRealm;
    }
}
