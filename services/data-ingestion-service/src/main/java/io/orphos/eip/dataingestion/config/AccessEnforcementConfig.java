package io.orphos.eip.dataingestion.config;

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
 * RBAC enforcement for the Data group admin tabs served by this service:
 * Data Sources, Data Mapping, and Ingestion Jobs. Grants are fetched from
 * platform-core via {@link HttpAccessGrantSource}; the filter fails closed (503)
 * when the map is unavailable, unless eip.access.fail-mode=open.
 *
 * <p>Note: these tabs share some reads (Data Mapping lists data sources), so
 * they enforce most cleanly when granted together at the Data group level.</p>
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
        r.put("/api/data-ingestion/admin/datasources", "data-sources");
        r.put("/api/data-ingestion/admin/mappings", "data-mapping");
        r.put("/api/data-ingestion/admin/jobs", "ingestion-jobs");
        r.put("/api/data-ingestion/admin/runs", "ingestion-jobs");
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
