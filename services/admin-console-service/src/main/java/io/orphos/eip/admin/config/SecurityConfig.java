package io.orphos.eip.admin.config;

import io.orphos.eip.admin.repository.BrandingRepository;
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
 * Admin Console Service security — one third of the former admin-service's
 * {@code SecurityConfig}, keeping only the URL patterns actually served here
 * (branding, email, query history).
 *
 *   - Public: actuator probes + public branding read (so login pages can
 *     fetch logos pre-auth)
 *   - {@code /api/admin/**} requires role-specific access (see below)
 *   - Everything else authenticated (covers the user-facing
 *     {@code /api/notifications/prefs/**} endpoints)
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
                    "/swagger-ui.html",
                    // Public branding read - so login pages can fetch logos pre-auth
                    "/api/branding/public/**",
                    // Service-to-service event ingestion (event-communication
                    // framework) and outbound agent email. Not JWT-gated — the
                    // controllers validate the shared X-Internal-Token and
                    // reject everything until the secret is configured
                    // (fail-closed).
                    "/api/internal/events",
                    "/api/internal/email/send"
                ).permitAll()
                // Query history is accessible to tenant admins, platform admins and
                // the scoped Data & AI admin (Data & AI group → Query History tab).
                .requestMatchers("/api/admin/query-history/**").hasAnyRole("admin", "platform_admin", "data_ai_admin")
                // Branding/Appearance is accessible to both roles — a platform admin
                // manages a specific tenant's branding via X-Tenant-Id impersonation
                // (BrandingRepository scopes every read/write to the resolved tenant).
                .requestMatchers("/api/admin/branding/**").hasAnyRole("admin", "platform_admin")
                // Event Alerts (Communications tab) — like branding, a platform
                // admin manages a tenant's notification rules via X-Tenant-Id
                // impersonation.
                .requestMatchers("/api/admin/events/**").hasAnyRole("admin", "platform_admin")
                // Catch-all for the remaining /api/admin/** surface owned by this
                // service (email).
                .requestMatchers("/api/admin/**").hasRole("admin")
                .anyRequest().authenticated()
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
