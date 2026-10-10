package io.orphos.eip.admin.config;

import io.orphos.eip.admin.service.PersonaMetricService;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.access.AccessEnforcementFilter;
import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.HttpAccessGrantSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;

/**
 * RBAC enforcement for the Dashboard Defaults admin tab: gates the tenant-wide
 * default layout endpoint {@code /api/admin/dashboard/layout} on the
 * {@code dashboard} resource. The per-user layout ({@code /api/dashboard/layout})
 * is intentionally NOT mapped. Grants are fetched from platform-core via
 * {@link HttpAccessGrantSource}; fails closed (503) when the map is unavailable,
 * unless eip.access.fail-mode=open.
 */
@Configuration
public class AccessEnforcementConfig {

    @Value("${eip.security.tenant-discovery.url:${EIP_TENANT_DISCOVERY_URL:}}")
    private String platformCoreUrl;

    @Value("${eip.security.tenant-discovery.service-token:${eip.security.internal-service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}}")
    private String internalToken;

    @Value("${eip.access.fail-closed-resources:}")
    private String failClosedResources;

    private static LinkedHashMap<String, String> routes() {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        r.put("/api/admin/dashboard/layout", "dashboard");
        // Ontology-metric values belong to the user dashboard's widget board, so a
        // role denied that section cannot fetch them. This is the SCREEN-level gate
        // (same resource key platform-core uses for /api/dashboard/metrics); the
        // per-widget persona check in PersonaMetricService is the DATA-level gate.
        // Both apply — the first decides whether the board exists for you, the
        // second decides which numbers on it you may see.
        r.put("/api/dashboard/persona-metrics", "widget-board");
        return r;
    }

    @Bean
    public AccessGrantSource accessGrantSource(EipRestTemplates restTemplates) {
        return new HttpAccessGrantSource(restTemplates, platformCoreUrl, internalToken);
    }

    @Bean
    public AccessEnforcementFilter accessEnforcementFilter(AccessGrantSource grantSource) {
        return new AccessEnforcementFilter(grantSource, routes(),
                AccessEnforcementFilter.parseResourceSet(failClosedResources));
    }

    @Bean
    public FilterRegistrationBean<AccessEnforcementFilter> accessEnforcementFilterRegistration(
            AccessEnforcementFilter filter) {
        FilterRegistrationBean<AccessEnforcementFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(filter.getOrder());
        return reg;
    }
}
