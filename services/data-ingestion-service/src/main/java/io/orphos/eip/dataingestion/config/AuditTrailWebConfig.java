package io.orphos.eip.dataingestion.config;

import io.orphos.eip.dataingestion.component.AuditTrailInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires {@link AuditTrailInterceptor} onto the admin API surface. Registered
 * for the whole {@code /api/data-ingestion/admin/**} tree — the interceptor
 * itself filters down to successful mutating requests.
 */
@Configuration
public class AuditTrailWebConfig implements WebMvcConfigurer {

    private final AuditTrailInterceptor auditTrail;

    public AuditTrailWebConfig(AuditTrailInterceptor auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditTrail).addPathPatterns("/api/data-ingestion/admin/**");
    }
}
