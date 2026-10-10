package io.orphos.eip.common.web.ratelimit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import io.github.bucket4j.Bucket;

/**
 * Wires {@link RateLimitFilter} into the servlet container when both:
 *   1. Bucket4j is on the classpath ({@code Bucket4j} is in {@code eip-common}
 *      as an optional dep, so services explicitly opt in by depending on it
 *      themselves, or transitively via {@code eip-common}); and
 *   2. {@code eip.rate-limit.enabled=true}.
 */
@AutoConfiguration
@ConditionalOnClass(Bucket.class)
@ConditionalOnProperty(prefix = "eip.rate-limit", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitProperties props) {
        RateLimitFilter filter = new RateLimitFilter(props.getRules());
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(filter.getOrder());
        reg.addUrlPatterns("/*");
        return reg;
    }
}
