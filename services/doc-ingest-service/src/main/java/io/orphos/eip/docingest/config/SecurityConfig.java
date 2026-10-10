package io.orphos.eip.docingest.config;

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
 * Stateless JWT security following the standard Orphos pattern.
 *
 * Public: actuator probes + OpenAPI spec.
 * ROLE_admin or ROLE_platform_admin: document management (ingest/delete/list).
 * Any authenticated user: read-only document status.
 *
 * Multi-issuer JWT validation via eip-common's MultiIssuerJwtAutoConfiguration —
 * the AuthenticationManagerResolver bean selects the right per-tenant
 * AuthenticationManager from the iss claim, identical to all other services.
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
            // Service-to-service callers (e.g. eip-mcp-ingest's ingest.list_jobs / run_job tools)
            // authenticate with the shared X-Internal-Token, mapped to ROLE_service_internal so the
            // /admin/** rules below accept it alongside human admins. Additive — no token ⇒ no-op.
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
                    // Source inventory for the data-flow aggregator; the controller
                    // validates the shared X-Internal-Token.
                    "/api/internal/sources",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                // Document management requires at minimum ROLE_admin. The scoped
                // ROLE_ingestion_admin also manages documents + ingestion/search settings.
                // ROLE_service_internal lets a trusted internal caller (MCP ingest server) in too.
                .requestMatchers("/api/doc-ingest/admin/**")
                    .hasAnyAuthority("ROLE_admin", "ROLE_platform_admin", "ROLE_ingestion_admin", "ROLE_service_internal")
                // Guest-only accounts (awaiting a role) get 403; see EipRoles.MEMBER.
                .anyRequest().hasAnyRole("member", "service_internal")
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(jwtAuthenticationManagerResolver)
            );
        return http.build();
    }
}
