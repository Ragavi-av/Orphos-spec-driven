package io.orphos.eip.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tunables for the multi-issuer JWT resolver.
 *
 * <p>{@link #getAllowedIssuers()} is the canonical list of trusted issuer URLs
 * (one per active tenant realm). platform-core-service overrides the bean with a
 * DB-backed provider that streams from the {@code Tenant} doc type; all other
 * services read this static list from their {@code application.yml}.</p>
 *
 * <p>{@link #getPlatformIssuer()} names the special {@code platform} realm
 * (cross-tenant superadmins). The bean's {@link AllowedIssuersProvider}
 * implementation always honours it in addition to the per-tenant list.</p>
 */
@ConfigurationProperties(prefix = "eip.security")
public class SecurityProperties {

    /** Issuer URLs accepted by this service. Format: {@code https://host/realms/<realm>}. */
    private List<String> allowedIssuers = new ArrayList<>();

    /** Full issuer URL of the platform-superadmin realm. May be null in dev. */
    private String platformIssuer;

    /**
     * Internal base URL of the Keycloak server, used ONLY for JWKS fetching
     * when the JWT issuer URL (the {@code iss} claim) is browser-facing and
     * therefore unreachable from inside Docker (e.g. {@code http://localhost:8443}).
     *
     * <p>When set, JWT validation rewrites the issuer's host portion to this
     * base when fetching the JWKS, but issuer-claim verification still uses
     * the original {@code iss}. Leave blank if Keycloak is reachable at the
     * same URL the browser uses (e.g. behind a single reverse proxy in prod).</p>
     */
    private String internalIssuerBase;

    /**
     * Authority claim used for {@code @PreAuthorize("hasRole(...)")} mapping.
     * Matches the Keycloak {@code roles} client scope shipped by
     * {@code KeycloakProvisioningClient} and the {@code eip-realm.json}
     * template.
     */
    private String authoritiesClaim = "realm_access.roles";

    /** Prefix added to authorities (Spring's convention). */
    private String authorityPrefix = "ROLE_";

    /** Discovery of trusted issuers by polling platform-core-service. */
    private TenantDiscovery tenantDiscovery = new TenantDiscovery();

    public List<String> getAllowedIssuers() { return allowedIssuers; }
    public void setAllowedIssuers(List<String> allowedIssuers) {
        this.allowedIssuers = allowedIssuers != null ? allowedIssuers : new ArrayList<>();
    }

    public String getPlatformIssuer() { return platformIssuer; }
    public void setPlatformIssuer(String platformIssuer) { this.platformIssuer = platformIssuer; }

    public String getInternalIssuerBase() { return internalIssuerBase; }
    public void setInternalIssuerBase(String internalIssuerBase) { this.internalIssuerBase = internalIssuerBase; }

    public String getAuthoritiesClaim() { return authoritiesClaim; }
    public void setAuthoritiesClaim(String authoritiesClaim) { this.authoritiesClaim = authoritiesClaim; }

    public String getAuthorityPrefix() { return authorityPrefix; }
    public void setAuthorityPrefix(String authorityPrefix) { this.authorityPrefix = authorityPrefix; }

    public TenantDiscovery getTenantDiscovery() { return tenantDiscovery; }
    public void setTenantDiscovery(TenantDiscovery tenantDiscovery) {
        this.tenantDiscovery = tenantDiscovery != null ? tenantDiscovery : new TenantDiscovery();
    }

    /**
     * Configures the platform-core-service-polling {@link AllowedIssuersProvider}.
     * When {@link #getUrl()} is non-blank, eip-common registers a polling
     * provider that fetches the live issuer list from platform-core-service; otherwise
     * it falls back to {@link SecurityProperties#getAllowedIssuers()}.
     */
    public static class TenantDiscovery {
        /** Base URL of platform-core-service (e.g. {@code http://eip-platform-core:8090}). Null/blank disables polling. */
        private String url;
        /** Time-to-live of the cached issuer list. */
        private Duration cacheTtl = Duration.ofSeconds(60);
        /** HTTP connect timeout when calling platform-core-service. */
        private Duration connectTimeout = Duration.ofSeconds(2);
        /** HTTP read timeout when calling platform-core-service. */
        private Duration readTimeout = Duration.ofSeconds(5);
        /**
         * Shared secret sent as {@code X-Service-Token} on every outbound poll.
         * Pairs with platform-core-service's {@code eip.security.internal-service-token}
         * — the values MUST match. Blank/null disables the header (dev mode);
         * production deployments should populate this via OpenBao or env.
         */
        private String serviceToken;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Duration getCacheTtl() { return cacheTtl; }
        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl != null ? cacheTtl : Duration.ofSeconds(60);
        }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public String getServiceToken() { return serviceToken; }
        public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }
    }
}
