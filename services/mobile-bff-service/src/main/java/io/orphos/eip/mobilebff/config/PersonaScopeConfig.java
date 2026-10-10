package io.orphos.eip.mobilebff.config;

import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.HttpAccessGrantSource;
import io.orphos.eip.common.http.EipRestTemplates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The tenant access map, fetched from platform-core, so approvals and travel apply the
 * same per-tenant {@code data-domain:*} grants as the other services when filtering to
 * the caller's persona ({@code PersonaDomainPolicy}). No route enforcement here — the
 * lists are filtered row by row in {@code MobileController}.
 */
@Configuration
public class PersonaScopeConfig {

    @Value("${eip.security.tenant-discovery.url:${EIP_TENANT_DISCOVERY_URL:}}")
    private String platformCoreUrl;

    @Value("${eip.security.tenant-discovery.service-token:${eip.security.internal-service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}}")
    private String internalToken;

    @Bean
    public AccessGrantSource accessGrantSource(EipRestTemplates restTemplates) {
        return new HttpAccessGrantSource(restTemplates, platformCoreUrl, internalToken);
    }
}
