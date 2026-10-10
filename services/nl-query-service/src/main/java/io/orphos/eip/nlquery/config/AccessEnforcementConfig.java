package io.orphos.eip.nlquery.config;

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
 * RBAC enforcement for the Ask Orphos copilot + AI History dashboard sections:
 * gates {@code /api/nl-query/ask} on {@code ask-orphos} and the copilot session
 * store {@code /api/nl-query/me/copilot/sessions} on {@code ai-history}. Other
 * nl-query paths (provider/key management, usage) are left unmapped. Fails closed (503)
 * when the map is unavailable.
 *
 * <p>Runs as a servlet filter independent of this service's method security
 * ({@code @PreAuthorize}); a 403 here short-circuits before the controller.</p>
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
        // Admin AI tabs. The prompts store is ONE endpoint shared by every prompt
        // tab — LLM Prompts + Email AI + Communications AI + doc prompt kinds (all
        // AI-group), distinguished only by a body "kind", so it can't be URL-gated
        // per tab; it maps to 'prompts' and inherits AI-group grants. Deny 'prompts'
        // or the 'ai' group to block prompt editing (incl. comms-ai/email-ai).
        r.put("/api/nl-query/admin/tenant/ai-usage", "ai");
        r.put("/api/nl-query/admin/tenant/providers", "ai");
        r.put("/api/nl-query/admin/prompts", "prompts");
        // Most-specific first: the copilot session store is AI History.
        r.put("/api/nl-query/me/copilot/sessions", "ai-history");
        r.put("/api/nl-query/ask", "ask-orphos");
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
