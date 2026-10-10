package io.orphos.eip.mailinsight.config;

import io.orphos.eip.core.security.InternalServiceTokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Stateless JWT security following the standard Orphos pattern (mirrors
 * doc-ingest-service).
 *
 * Public: actuator probes + OpenAPI spec + the internal callback (guarded by a
 * shared service token, not by JWT).
 * Admin config endpoints: admin / platform_admin / CXO roles.
 * Item read + action endpoints: any authenticated user (rows are tenant-scoped).
 *
 * Multi-issuer JWT validation is provided by eip-common's
 * MultiIssuerJwtAutoConfiguration via the injected AuthenticationManagerResolver.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> jwtAuthenticationManagerResolver,
            @Value("${eip.security.internal-service-token:}") String internalServiceToken) throws Exception {
        http
            // Service-to-service callers (e.g. eip-mcp-mail's list/draft/send tools) authenticate with the
            // shared X-Internal-Token, mapped to ROLE_service_internal so the non-admin item/action
            // endpoints (the member/service_internal rule) accept them. The /admin/** role gate below is
            // unaffected. Additive — no token configured ⇒ no-op.
            .addFilterBefore(new InternalServiceTokenAuthenticationFilter(internalServiceToken),
                    BearerTokenAuthenticationFilter.class)
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/prometheus",
                    "/actuator/info",
                    "/api/mail-insight/health",
                    // Source inventory for the data-flow aggregator; the controller
                    // validates the shared X-Internal-Token.
                    "/api/internal/sources",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                // Connection + mailbox configuration: admins and the C-suite.
                .requestMatchers("/api/mail-insight/admin/**")
                    .hasAnyRole("admin", "platform_admin", "ceo", "cto", "cfo", "executive")
                // Guest-only accounts (awaiting a role) get 403; see EipRoles.MEMBER.
                .anyRequest().hasAnyRole("member", "service_internal")
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(jwtAuthenticationManagerResolver)
            );
        return http.build();
    }
}
