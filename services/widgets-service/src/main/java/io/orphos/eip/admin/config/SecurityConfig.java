package io.orphos.eip.admin.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Widgets Service security — one third of the former admin-service's
 * {@code SecurityConfig}, keeping only the URL patterns actually served here
 * (dashboard widget layout).
 *
 *   - Public: actuator probes only
 *   - {@code /api/admin/dashboard/layout/**} (tenant-default admin view) requires ROLE_admin
 *   - Everything else authenticated (covers the per-user
 *     {@code /api/dashboard/layout/**} endpoints, which any authenticated
 *     tenant user may call for their own layout)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> jwtAuthenticationManagerResolver) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/prometheus",
                    "/actuator/info",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                // Tenant-default admin view of dashboard layout.
                .requestMatchers("/api/admin/dashboard/layout/**").hasRole("admin")
                // Guest-only accounts (awaiting a role) get 403; see EipRoles.MEMBER.
                .anyRequest().hasAnyRole("member", "service_internal")
            )
            // Multi-issuer JWT validation: the AuthenticationManagerResolver
            // bean (from eip-common's MultiIssuerJwtAutoConfiguration) picks
            // the right per-tenant AuthenticationManager based on the iss claim.
            // The trusted-issuer set is fetched live from platform-core-service.
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(jwtAuthenticationManagerResolver)
            );
        return http.build();
    }
}
