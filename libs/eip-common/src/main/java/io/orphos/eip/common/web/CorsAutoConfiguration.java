package io.orphos.eip.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Registers a {@link CorsConfigurationSource} bean that {@code SecurityConfig}
 * picks up via {@code http.cors(Customizer.withDefaults())}. Each service
 * still has to opt in by calling {@code .cors(...)} in its filter chain;
 * this configuration only supplies the source.
 *
 * <p>For dev the default origin list permits only the SPA at port 3001.
 * Override per-environment with {@code eip.cors.allowed-origins} in
 * {@code application.yml} or the {@code EIP_CORS_ALLOWED_ORIGINS} env var.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CorsConfigurationSource.class)
    public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.getAllowedOrigins());
        cfg.setAllowedMethods(props.getAllowedMethods());
        cfg.setAllowedHeaders(props.getAllowedHeaders());
        cfg.setAllowCredentials(props.isAllowCredentials());
        cfg.setMaxAge(props.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
