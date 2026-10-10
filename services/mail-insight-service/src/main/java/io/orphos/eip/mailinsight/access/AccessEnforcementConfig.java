package io.orphos.eip.mailinsight.access;

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
 * RBAC enforcement for the Tasks for Action + Notifications dashboard sections
 * and the Communications admin tabs: gates {@code /api/mail-insight/tasks} on
 * {@code tasks-for-action}, {@code /api/mail-insight/notifications} on
 * {@code notifications}, the channel connectors admin
 * ({@code /api/mail-insight/admin/channels}) on {@code channels}, and the rest
 * of {@code /api/mail-insight/admin} on {@code mail-monitor}. Shared per-item
 * action endpoints (incl. {@code /api/mail-insight/comms/items/**}) are left
 * unmapped (authenticated only). Fails closed (503) when the map is unavailable.
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
        r.put("/api/mail-insight/tasks", "tasks-for-action");
        r.put("/api/mail-insight/notifications", "notifications");
        // Calendar feed behind the Upcoming Meetings tab. Mapped to the same key
        // as that tab so revoking the screen also closes the API, rather than
        // leaving the data reachable to anyone who kept the URL.
        r.put("/api/mail-insight/meetings", "upcoming-meetings");
        // Most-specific first (routes are matched in insertion order): the channel
        // connectors admin (Communication Channels tab) before the coarse admin
        // catch-all, which stays on mail-monitor.
        r.put("/api/mail-insight/admin/channels", "channels");
        r.put("/api/mail-insight/admin", "mail-monitor");
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
