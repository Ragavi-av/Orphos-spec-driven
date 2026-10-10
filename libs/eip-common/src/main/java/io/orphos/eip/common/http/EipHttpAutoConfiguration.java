package io.orphos.eip.common.http;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * Registers the shared outbound-HTTP plumbing in every service that depends on
 * {@code eip-common}: the {@link EipRestTemplates} factory and one ready-made
 * {@link RestTemplate} carrying the {@code eip.http.*} timeouts.
 *
 * <p>The {@code RestTemplate} bean backs off if a service declares its own, so a
 * service with special requirements stays in control and never sees an ambiguous
 * by-type injection.</p>
 */
@AutoConfiguration
@ConditionalOnClass(RestTemplate.class)
@EnableConfigurationProperties(EipHttpProperties.class)
public class EipHttpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EipRestTemplates eipRestTemplates(EipHttpProperties props) {
        return new EipRestTemplates(props);
    }

    /**
     * The default client for callers that just want a timeout-bounded RestTemplate.
     * Throws on 4xx/5xx like any RestTemplate; callers that need to inspect error
     * statuses build a lenient one from {@link EipRestTemplates}.
     */
    @Bean
    @ConditionalOnMissingBean(RestTemplate.class)
    public RestTemplate eipRestTemplate(EipRestTemplates factory) {
        return factory.create();
    }
}
