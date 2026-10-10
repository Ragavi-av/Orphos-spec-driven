package io.orphos.eip.core.ai;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link NlQueryInternalClient} for any service that depends on
 * {@code eip-core-ai-client}. Disabled behavior (missing/blank internal token)
 * is handled by {@link NlQueryInternalClient#isEnabled()} rather than by
 * withholding the bean, so callers can always inject it and just check
 * {@code isEnabled()} before use.
 */
@AutoConfiguration
@EnableConfigurationProperties(NlQueryProperties.class)
public class NlQueryClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NlQueryInternalClient nlQueryInternalClient(NlQueryProperties props) {
        return new NlQueryInternalClient(props);
    }
}
