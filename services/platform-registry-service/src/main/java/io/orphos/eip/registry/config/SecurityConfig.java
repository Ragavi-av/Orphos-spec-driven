package io.orphos.eip.registry.config;

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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> authManagerResolver,
            @Value("${eip.security.internal-service-token:}") String internalServiceToken) throws Exception {
        http
            // Service-to-service self-registration (agents / MCP servers) authenticates with the
            // shared X-Internal-Token, which this filter maps to ROLE_service_internal so the
            // @PreAuthorize("hasAuthority('ROLE_service_internal')") register endpoints accept it.
            .addFilterBefore(new InternalServiceTokenAuthenticationFilter(internalServiceToken),
                    BearerTokenAuthenticationFilter.class)
            // CORS source comes from eip-common's CorsAutoConfiguration — origins
            // pinned to the SPA (eip.cors.allowed-origins / EIP_CORS_ALLOWEDORIGINS).
            // Never define a local wildcard source here: allowedOriginPatterns("*")
            // together with allowCredentials(true) lets any website drive this API
            // with the visitor's admin session.
            .cors(Customizer.withDefaults())
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Guest-only accounts (awaiting a role) get 403; see EipRoles.MEMBER.
                .anyRequest().hasAnyRole("member", "service_internal")
            )
            .oauth2ResourceServer(o -> o.authenticationManagerResolver(authManagerResolver));
        return http.build();
    }
}
