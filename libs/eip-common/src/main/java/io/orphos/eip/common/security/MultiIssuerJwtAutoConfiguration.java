package io.orphos.eip.common.security;

import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
import io.orphos.eip.common.tenant.TenantProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Wires multi-issuer JWT validation for every service that depends on
 * {@code eip-common}. A service's {@code SecurityConfig} just resolves the
 * {@code AuthenticationManagerResolver<HttpServletRequest>} bean and passes
 * it to {@code .oauth2ResourceServer(o -> o.authenticationManagerResolver(...))}
 * — no per-service multi-tenant code needed.
 *
 * <p>The resolver:
 * <ol>
 *   <li>Extracts the {@code iss} claim from the incoming JWT.</li>
 *   <li>Asks {@link AllowedIssuersProvider#allowedIssuers()} whether the issuer
 *       is trusted — if not, returns {@code null} so Spring throws
 *       {@code InvalidBearerTokenException} (401).</li>
 *   <li>Looks up (or lazily builds and caches) a per-issuer
 *       {@link AuthenticationManager}, which uses
 *       {@link JwtDecoders#fromIssuerLocation} to fetch the JWKS via OIDC
 *       discovery on first hit.</li>
 * </ol>
 *
 * <p>Conditional on Spring Security's {@code JwtAuthenticationToken} class
 * being on the classpath, so libraries can depend on {@code eip-common}
 * without dragging in resource-server.</p>
 */
@AutoConfiguration
@ConditionalOnClass(JwtAuthenticationConverter.class)
@EnableConfigurationProperties(SecurityProperties.class)
public class MultiIssuerJwtAutoConfiguration {

    private static final Logger log = LogManager.getLogger(MultiIssuerJwtAutoConfiguration.class);

    /**
     * Default static-config provider. Used by services that don't (or can't)
     * poll platform-core-service for the issuer list. Overridden by the polling bean
     * below when {@code eip.security.tenant-discovery.url} is set, and
     * overridden by platform-core-service's {@code @Primary} DB-backed bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public AllowedIssuersProvider allowedIssuersProvider(SecurityProperties props) {
        return new ConfigBackedAllowedIssuersProvider(props);
    }

    /**
     * Admin-service-polling provider. Registered only when
     * {@code eip.security.tenant-discovery.url} is set, and marked
     * {@code @Primary} so it wins over the static default. platform-core-service itself
     * should NOT set this property — its own {@code DbBackedAllowedIssuersProvider}
     * is also {@code @Primary}, and two primary beans would conflict.
     */
    @Bean
    @ConditionalOnProperty(prefix = "eip.security.tenant-discovery", name = "url")
    @Primary
    public AllowedIssuersProvider adminPollingAllowedIssuersProvider(
            SecurityProperties props, EipCircuitBreakers circuitBreakers) {
        SecurityProperties.TenantDiscovery td = props.getTenantDiscovery();
        return new AdminPollingAllowedIssuersProvider(
                td.getUrl(), td.getCacheTtl(), td.getConnectTimeout(), td.getReadTimeout(), props, circuitBreakers);
    }

    /**
     * Default authority mapper — see {@link EipJwtAuthoritiesConverter} for the
     * trust rules (nested claim path, synthetic roles dropped, {@code platform_admin}
     * bound to the platform realm, derived {@code member} and custom-role bases).
     *
     * <p>Spring's stock {@code JwtGrantedAuthoritiesConverter} does a flat
     * {@code jwt.getClaim(name)} lookup that doesn't navigate nested JSON,
     * so a Keycloak JWT with roles at {@code $.realm_access.roles} would
     * produce <em>zero</em> authorities under the literal claim name
     * {@code realm_access.roles}.</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "jwtAuthenticationConverter")
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter(
            SecurityProperties props,
            ObjectProvider<TenantProperties> tenantProps,
            ObjectProvider<CustomRoleBaseSource> customRoleBases) {
        TenantProperties tp = tenantProps.getIfAvailable(TenantProperties::new);
        EipJwtAuthoritiesConverter authoritiesConverter = new EipJwtAuthoritiesConverter(
                props.getAuthoritiesClaim(), props.getAuthorityPrefix(),
                tp.getPlatformAdminRealm(), props.getPlatformIssuer(), tp.getJwtClaim(),
                customRoleBases::getIfAvailable);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    /**
     * Custom-role inheritance for services that reach platform-core through the
     * tenant-discovery URL. platform-core itself (which owns the role registry)
     * registers its own local {@link CustomRoleBaseSource}.
     */
    @Bean
    @ConditionalOnMissingBean(CustomRoleBaseSource.class)
    @ConditionalOnProperty(prefix = "eip.security.tenant-discovery", name = "url")
    public CustomRoleBaseSource httpCustomRoleBaseSource(
            SecurityProperties props, EipRestTemplates restTemplates,
            @Value("${eip.security.internal-service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}") String internalToken) {
        SecurityProperties.TenantDiscovery td = props.getTenantDiscovery();
        String token = td.getServiceToken() != null && !td.getServiceToken().isBlank()
                ? td.getServiceToken() : internalToken;
        return new HttpCustomRoleBaseSource(restTemplates, td.getUrl(), token);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthenticationManagerResolver<HttpServletRequest> multiIssuerAuthenticationManagerResolver(
            AllowedIssuersProvider allowedIssuers,
            Converter<Jwt, AbstractAuthenticationToken> converter,
            SecurityProperties props) {

        ConcurrentMap<String, AuthenticationManager> cache = new ConcurrentHashMap<>();
        String internalBase = props.getInternalIssuerBase();

        return new JwtIssuerAuthenticationManagerResolver(issuer -> {
            Set<String> allowed = allowedIssuers.allowedIssuers();
            if (!allowed.contains(issuer)) {
                log.debug("Rejecting JWT with disallowed issuer '{}' (allowed: {})", issuer, allowed);
                return null;
            }
            return cache.computeIfAbsent(issuer,
                    iss -> buildAuthenticationManager(iss, internalBase, converter));
        });
    }

    private static AuthenticationManager buildAuthenticationManager(
            String issuer, String internalBase, Converter<Jwt, AbstractAuthenticationToken> converter) {
        JwtDecoder decoder;
        String rewrittenJwksUri = rewriteToInternalJwksUri(issuer, internalBase);
        if (rewrittenJwksUri != null) {
            // Browser-facing issuer (e.g. http://localhost:8443/...) is unreachable
            // from inside Docker; fetch JWKS from the internal Keycloak host
            // instead, but keep validating the iss claim against the external URL.
            NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(rewrittenJwksUri).build();
            nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            decoder = nimbus;
            log.info("Built JWT auth manager for issuer '{}' (JWKS rewritten to '{}')",
                    issuer, rewrittenJwksUri);
        } else {
            decoder = JwtDecoders.fromIssuerLocation(issuer);
            log.info("Built JWT auth manager for issuer '{}' (via OIDC discovery)", issuer);
        }
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return provider::authenticate;
    }

    /**
     * If an {@code internalIssuerBase} is configured, swap the host part of the
     * issuer URL with it and append the standard Keycloak JWKS suffix. Returns
     * {@code null} when no rewrite is configured (the caller falls back to
     * OIDC discovery against the original URL).
     *
     * <p>Example: issuer {@code http://localhost:8443/realms/eip} with internal
     * base {@code http://eip-keycloak:8080} becomes JWKS URI
     * {@code http://eip-keycloak:8080/realms/eip/protocol/openid-connect/certs}.</p>
     */
    static String rewriteToInternalJwksUri(String issuer, String internalBase) {
        if (internalBase == null || internalBase.isBlank()) return null;
        try {
            URI iss = URI.create(issuer);
            String path = iss.getRawPath() == null ? "" : iss.getRawPath();
            String trimmedBase = internalBase.endsWith("/")
                    ? internalBase.substring(0, internalBase.length() - 1)
                    : internalBase;
            return trimmedBase + path + "/protocol/openid-connect/certs";
        } catch (IllegalArgumentException e) {
            log.warn("Could not rewrite issuer '{}' against internal base '{}': {}",
                    issuer, internalBase, e.getMessage());
            return null;
        }
    }
}
