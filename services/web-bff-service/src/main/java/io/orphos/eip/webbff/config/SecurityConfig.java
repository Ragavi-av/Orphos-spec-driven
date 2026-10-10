package io.orphos.eip.webbff.config;

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
 * Custom security chain, backing off eip-core-security's default.
 *
 * <p>Every {@code /api/web/**} aggregation requires a tenant JWT. That is the BFF's own
 * gate only — the caller's token is forwarded verbatim to each downstream, which applies
 * its normal authorization on top, so this service can never widen what a user may read.
 * The multi-issuer resolver (realm-per-tenant) comes from eip-common.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> jwtAuthenticationManagerResolver,
            @Value("${eip.security.internal-service-token:}") String internalServiceToken) throws Exception {
        http
                .addFilterBefore(new InternalServiceTokenAuthenticationFilter(internalServiceToken),
                        BearerTokenAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus", "/api/web/health").permitAll()
                        .requestMatchers("/api/web/**").hasAnyRole("member", "service_internal")
                        // Guest-only accounts (awaiting a role) get 403; see EipRoles.MEMBER.
                        .anyRequest().hasAnyRole("member", "service_internal"))
                .oauth2ResourceServer(o -> o.authenticationManagerResolver(jwtAuthenticationManagerResolver));
        return http.build();
    }
}
