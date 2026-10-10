package io.orphos.eip.decision.config;

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
 * RBAC enforcement for the Decision Log dashboard section: gates {@code /api/decisions/**}
 * on the {@code decision-log} resource. Grants are fetched from platform-core via
 * {@link HttpAccessGrantSource}; the filter fails closed (503) when the map is unavailable.
 */
@Configuration
public class AccessEnforcementConfig {

    @Value("${eip.security.tenant-discovery.url:${EIP_TENANT_DISCOVERY_URL:}}")
    private String platformCoreUrl;

    @Value("${eip.security.tenant-discovery.service-token:${eip.security.internal-service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}}")
    private String internalToken;

    // Opt-in, globally-configured resource keys that FAIL CLOSED when the access
    // map can't be resolved. Empty by default; other resources follow eip.access.fail-mode (closed unless set to open).
    @Value("${eip.access.fail-closed-resources:}")
    private String failClosedResources;

    private static LinkedHashMap<String, String> routes() {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        r.put("/api/decisions", "decision-log");
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
