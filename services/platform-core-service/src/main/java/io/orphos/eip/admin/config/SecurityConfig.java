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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;

/**
 * Platform Core Service security — one third of the former admin-service's
 * {@code SecurityConfig}, keeping only the URL patterns actually served here
 * (tenant lifecycle, auth/authz config, secrets, migrations, onboarding, ops).
 *
 *   - Public: actuator probes + self-registration/login + SSO callbacks +
 *     public tenant lookup + service-to-service discovery endpoints
 *   - {@code /api/admin/**} requires role-specific access (see below)
 *   - Everything else authenticated (covers the onboarding-driven
 *     {@code /api/dashboard/widgets} + {@code /api/dashboard/metrics/**}
 *     endpoints, which any authenticated tenant user may call)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${admin.auth.bcrypt-strength:12}")
    private int bcryptStrength;

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
                    // Public tenant lookup - SPA needs this BEFORE Keycloak init
                    // to pick the right realm based on hostname.
                    "/api/public/tenants/**",
                    // Service-to-service tenant-issuer discovery. Permit-all
                    // but should be network-restricted in production — see
                    // InternalTenantIssuersController docstring.
                    "/api/internal/tenant-issuers",
                    "/api/internal/active-tenants",
                    "/api/internal/tenants/**",
                    // User directory for the event-communication framework
                    // (recipient resolution). Guarded in-controller by the
                    // shared X-Internal-Token; fail-closed when unset.
                    "/api/internal/users/**",
                    // RBAC grants for service-to-service access enforcement.
                    // Gated by the X-Service-Token filter + in-controller X-Internal-Token.
                    "/api/internal/access-grants",
                    // Self-managed auth public endpoints
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/auth/forgot",
                    // SSO callbacks
                    "/login/oauth2/code/**",
                    "/login/saml2/sso/**",
                    "/saml2/service-provider-metadata/**"
                ).permitAll()
                // Platform-superadmin only: tenant CRUD + container control + logs.
                // Listed BEFORE the generic /api/admin/** rule — first match wins.
                .requestMatchers("/api/admin/tenants/**").hasRole("platform_admin")
                .requestMatchers("/api/admin/services/**").hasRole("platform_admin")
                .requestMatchers("/api/admin/db-connections/**").hasRole("platform_admin")
                .requestMatchers("/api/admin/logs/**").hasRole("platform_admin")
                // Authentication & Authorization group (SSO config + auth methods +
                // SSO status) is accessible to tenant admins, platform admins and the
                // scoped Security & Auth admin. Listed before the generic /api/admin/** rule.
                .requestMatchers("/api/admin/auth/**").hasAnyRole("admin", "platform_admin", "secops_admin")
                .requestMatchers("/api/admin/sso/**").hasAnyRole("admin", "platform_admin", "secops_admin")
                // SCIM user-management is accessible to tenant admins, platform admins
                // and the scoped Security & Auth admin (User Management tab). Platform
                // admin can act on a specific tenant via X-Tenant-Id impersonation.
                .requestMatchers("/api/admin/scim/**").hasAnyRole("admin", "platform_admin", "secops_admin")
                // RBAC access map (Access Control tab). Readable by any admin-capable
                // role so each role's own tab filter can load the map; writes are
                // further restricted to the Auth & Authz owners via @PreAuthorize on
                // the controller. Listed before the generic /api/admin/** rule.
                .requestMatchers("/api/admin/access-control/**")
                    .hasAnyRole("admin", "platform_admin", "secops_admin", "ingestion_admin", "data_ai_admin")
                // Role management (Roles panel on the User Management tab). Readable by
                // any admin-capable role so the role table + dropdowns load; writes are
                // restricted to the Auth & Authz owners via @PreAuthorize on the
                // controller. Listed before the generic /api/admin/** rule.
                .requestMatchers("/api/admin/roles/**", "/api/admin/roles")
                    .hasAnyRole("admin", "platform_admin", "secops_admin", "ingestion_admin", "data_ai_admin")
                // Data Onboarding is part of the Data & AI admin surface — the
                // Admin UI shows the tab to the scoped data_ai_admin role, so the
                // API must accept it alongside tenant/platform admins. Listed
                // before the generic /api/admin/** rule — first match wins.
                .requestMatchers("/api/admin/onboarding/**").hasAnyRole("admin", "platform_admin", "data_ai_admin")
                // Data-flow / Ontology Sources aggregation — Data group tab, so the
                // scoped data_ai_admin may read it alongside tenant/platform admins.
                .requestMatchers("/api/admin/data-flow/**", "/api/admin/data-flow")
                    .hasAnyRole("admin", "platform_admin", "data_ai_admin")
                // Catch-all for the remaining /api/admin/** surface owned by this
                // service (onboarding).
                .requestMatchers("/api/admin/**").hasRole("admin")
                .anyRequest().authenticated()
            )
            // Multi-issuer JWT validation: the AuthenticationManagerResolver
            // bean (from eip-common's MultiIssuerJwtAutoConfiguration) picks
            // the right per-tenant AuthenticationManager based on the iss claim.
            // This service overrides AllowedIssuersProvider with a DB-backed
            // implementation so newly provisioned tenants are trusted live.
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(jwtAuthenticationManagerResolver)
            );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength);
    }
}
