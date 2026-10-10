package io.orphos.eip.common.events;

import io.orphos.eip.common.http.EipHttpAutoConfiguration;
import io.orphos.eip.common.http.EipRestTemplates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers an {@link EipEventPublisher} in every service that pulls in
 * eip-common. The bean is always present so producers can inject it without
 * guards; it no-ops until the ingest URL and shared internal token are
 * configured (see {@code eip.events.*}).
 */
@AutoConfiguration
@AutoConfigureAfter(EipHttpAutoConfiguration.class)
public class EipEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EipEventPublisher eipEventPublisher(
            EipRestTemplates restTemplates,
            @Value("${eip.events.ingest-url:http://eip-admin-console:8094}") String ingestUrl,
            @Value("${eip.events.token:${EIP_SECURITY_INTERNAL_SERVICE_TOKEN:}}") String token,
            @Value("${spring.application.name:unknown}") String source,
            @Value("${eip.events.enabled:true}") boolean enabled) {
        return new EipEventPublisher(restTemplates, ingestUrl, token, source, enabled);
    }
}
