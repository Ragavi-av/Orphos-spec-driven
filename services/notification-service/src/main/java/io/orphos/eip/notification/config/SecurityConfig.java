package io.orphos.eip.notification.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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
 */
@Configuration
@EnableWebSecurity
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
                    "/actuator/prometheus"
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
