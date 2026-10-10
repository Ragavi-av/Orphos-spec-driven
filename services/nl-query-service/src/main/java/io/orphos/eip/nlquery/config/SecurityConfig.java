package io.orphos.eip.nlquery.config;

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
 * JWT resource-server security — multi-issuer validation via eip-common's
 * {@code MultiIssuerJwtAutoConfiguration}. The trusted-issuer set is fetched
 * live from admin-service by {@code AdminPollingAllowedIssuersProvider}
 * (activated via {@code eip.security.tenant-discovery.url}), so a new tenant
 * is accepted without a config push to this service.
 *
 * <p>Public: {@code /actuator/health}, {@code /actuator/prometheus}.
 * Protected: everything else requires a valid Bearer JWT from a trusted realm.</p>
 *
 * <p>{@code @EnableMethodSecurity} enables {@code @PreAuthorize} on chat /
 * conversation endpoints that need finer role gating than URL-pattern rules.</p>
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
            // Service-to-service callers (e.g. eip-mcp-rag's rag.answer tool → /api/nl-query/ask)
            // authenticate with the shared X-Internal-Token, mapped to ROLE_service_internal so
            // the member/service_internal rule accepts them. The /internal/** endpoints keep their own
            // token check. Additive — no token configured ⇒ no-op.
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
                    // Container healthcheck (docker-compose.app.yml) hits this
                    // unauthenticated; the JWT filter would otherwise 401 it
                    // and mark the container unhealthy.
                    "/api/nl-query/health",
                    // Service-to-service completion endpoint — not JWT-gated;
                    // guarded instead by a shared X-Internal-Token validated in
                    // InternalCompletionController. Binds tenant from X-Tenant-Id.
                    "/api/nl-query/internal/**"
                ).permitAll()
                // Guest-only accounts (awaiting a role) get 403; see EipRoles.MEMBER.
                .anyRequest().hasAnyRole("member", "service_internal")
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(jwtAuthenticationManagerResolver)
            );
        return http.build();
    }
}
