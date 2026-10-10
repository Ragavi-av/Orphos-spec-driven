package io.orphos.eip.setup.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the wizard's API to be called cross-origin. The wizard is a local,
 * single-operator setup tool, so an open CORS policy is acceptable and lets the
 * page work even when opened directly as a file (Origin: null) rather than via
 * the served {@code http://localhost:8099} origin.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}
