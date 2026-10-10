package io.orphos.eip.core.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

/**
 * Zero-boilerplate {@link SecurityFilterChain} for services with no custom
 * authorization rules — extracted from the byte-identical {@code SecurityConfig}
 * classes that used to be hand-copied into decision-log-service,
 * priority-engine-service, okg-ingest-service, graph-proxy-service,
 * notification-service, briefing-service, and agent-orchestrator-service.
 *
 * <p>Backs off entirely (the filter-chain bean is {@code @ConditionalOnMissingBean})
 * if the service declares its own {@code SecurityFilterChain}, so services with
 * genuinely custom rules (admin-service's successors, nl-query-service,
 * doc-ingest-service, mail-insight-service) are unaffected by adding this
 * dependency — they simply keep their local {@code SecurityConfig}.</p>
 */
@AutoConfiguration
@EnableWebSecurity
@ConditionalOnClass(SecurityFilterChain.class)
@ConditionalOnProperty(prefix = "eip.security.default-chain", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EipDefaultSecurityProperties.class)
public class DefaultSecurityFilterChainAutoConfiguration {

    /**
     * Role names from eip-common's {@code EipRoles} (not a dependency of this module):
     * {@code member} is derived for any user holding a role beyond guest;
     * {@code service_internal} is granted by {@link InternalServiceTokenAuthenticationFilter}.
     */
    static final String MEMBER = "member";
    static final String SERVICE_INTERNAL = "service_internal";

    private static final List<String> BUILT_IN_PERMIT_ALL = List.of(
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/prometheus"
    );

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain eipDefaultSecurityFilterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> jwtAuthenticationManagerResolver,
            EipDefaultSecurityProperties props,
            @Value("${eip.security.internal-service-token:}") String internalServiceToken) throws Exception {

        List<String> permitAll = new ArrayList<>(BUILT_IN_PERMIT_ALL);
        permitAll.addAll(props.getPermitAll());
        String[] permitAllPatterns = permitAll.toArray(new String[0]);

        // Service-to-service auth: a valid X-Internal-Token grants ROLE_service_internal before the
        // JWT bearer filter runs, so internal callers need no user JWT. Additive — no token, no-op.
        InternalServiceTokenAuthenticationFilter internalTokenFilter =
                new InternalServiceTokenAuthenticationFilter(internalServiceToken);

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(permitAllPatterns).permitAll();
                if (props.isRequireMember()) {
                    auth.anyRequest().hasAnyRole(MEMBER, SERVICE_INTERNAL);
                } else {
                    auth.anyRequest().authenticated();
                }
            })
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(jwtAuthenticationManagerResolver)
            )
            .addFilterBefore(internalTokenFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Enables {@code @PreAuthorize}/{@code @PostAuthorize} processing unless a service
     * opts out ({@code eip.security.default-chain.method-security=false}) or already
     * enabled it itself — detected by the interceptor bean {@code @EnableMethodSecurity}
     * registers. Without this, an annotation on a default-chain service is silently
     * ignored and the endpoint is open to any authenticated caller.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "eip.security.default-chain", name = "method-security", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "preAuthorizeAuthorizationMethodInterceptor")
    @EnableMethodSecurity
    public static class MethodSecurityConfiguration {
    }
}
