package io.orphos.eip.voice.config;

import io.orphos.eip.core.security.InternalServiceTokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT resource-server security — multi-issuer validation via eip-common's
 * {@code MultiIssuerJwtAutoConfiguration}, matching every other service here.
 *
 * <p>Public: {@code /actuator/health}, {@code /actuator/prometheus}.
 * Protected: everything under {@code /api/voice/**}. Note that a valid token is
 * only the FIRST gate — {@code VoiceFeatureGate} then checks that the caller's
 * tenant has actually had voice enabled by a platform admin, so an authenticated
 * user of a tenant that never opted in still cannot dictate.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
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
