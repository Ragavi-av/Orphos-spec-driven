package io.orphos.eip.admin.config;

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
 * Registers the shared {@link AccessEnforcementFilter} for admin-console's
 * {@code /api/admin/**} surface. Unlike platform-core, admin-console does not own
 * the RBAC store, so it fetches the tenant's grants from platform-core's internal
 * endpoint via {@link HttpAccessGrantSource} (same base URL + shared token this
 * service already uses to fetch trusted issuers).
 */
@Configuration
public class AccessEnforcementConfig {

    // platform-core base URL + shared internal token (also used for issuer discovery).
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
        r.put("/api/admin/query-history", "queries");
        r.put("/api/admin/branding", "branding");
        r.put("/api/admin/events", "event-alerts");
        r.put("/api/admin/email", "email");
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
