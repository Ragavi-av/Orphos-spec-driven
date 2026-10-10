package io.orphos.eip.mobilebff.config;

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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

/**
 * Custom security chain (backs off eip-core-security's default). Mobile endpoints require
 * a tenant JWT; the internal-token filter is installed additively for service callers.
 * The multi-issuer JWT AuthenticationManagerResolver is provided by eip-common.
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
                                "/actuator/prometheus", "/api/mobile/health").permitAll()
                        .requestMatchers("/api/mobile/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.authenticationManagerResolver(jwtAuthenticationManagerResolver));
        return http.build();
    }
}
