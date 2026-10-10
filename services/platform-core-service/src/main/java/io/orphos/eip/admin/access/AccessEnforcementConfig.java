package io.orphos.eip.admin.access;

import io.orphos.eip.common.access.AccessEnforcementFilter;
import io.orphos.eip.common.access.AccessGrantSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;

/**
 * Registers the shared {@link AccessEnforcementFilter} for platform-core's
 * {@code /api/admin/**} surface, backed by the direct (ArcadeDB) grant source.
 * The route table maps each admin path prefix to its access-control resource key.
 */
@Configuration
public class AccessEnforcementConfig {

    // Opt-in, globally-configured set of resource keys that FAIL CLOSED (503) when
    // the access map can't be resolved. Empty by default; other resources follow eip.access.fail-mode (closed unless set to open).
    @Value("${eip.access.fail-closed-resources:}")
    private String failClosedResources;

    private static LinkedHashMap<String, String> routes() {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        // Most-specific first. /api/admin/auth also covers /api/admin/auth/keycloak.
        r.put("/api/admin/access-control", "access-control");
        r.put("/api/admin/auth", "auth");
        r.put("/api/admin/sso", "auth");
        r.put("/api/admin/scim", "users");
        r.put("/api/admin/onboarding", "onboarding");
        r.put("/api/admin/data-flow", "ontology");
        r.put("/api/admin/tenants", "tenants");
        r.put("/api/admin/services", "services");
        r.put("/api/admin/db-connections", "db-connections");
        r.put("/api/admin/logs", "logs");
        // User-dashboard widget DATA served by platform-core (layout lives in
        // widgets-service). Gated on the widget-board section.
        r.put("/api/dashboard/widgets", "widget-board");
        r.put("/api/dashboard/metrics", "widget-board");
        return r;
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
