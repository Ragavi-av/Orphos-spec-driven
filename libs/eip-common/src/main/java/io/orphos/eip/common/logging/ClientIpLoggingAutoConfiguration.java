package io.orphos.eip.common.logging;

import jakarta.servlet.Filter;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link ClientIpLoggingFilter} in every service that depends on {@code eip-common},
 * so the {@code clientIp} MDC field appears in the JSON logs without any per-service code.
 *
 * <p>Conditions, in order: a servlet web app (the filter is meaningless in a plain job),
 * Log4j2's {@code ThreadContext} on the classpath (the MDC this writes to), and
 * {@code eip.logging.client-ip.enabled} not set to false — on by default, because every
 * module previously registered this filter unconditionally.</p>
 *
 * <p>Registered through a {@link FilterRegistrationBean} rather than {@code @Component}: the
 * filter lives in a library package that a service's component scan does not cover. The order
 * is taken from the filter itself so the two cannot drift apart.</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Filter.class, ThreadContext.class})
@ConditionalOnProperty(prefix = "eip.logging.client-ip", name = "enabled", matchIfMissing = true)
public class ClientIpLoggingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ClientIpLoggingFilter.class)
    public FilterRegistrationBean<ClientIpLoggingFilter> clientIpLoggingFilterRegistration() {
        ClientIpLoggingFilter filter = new ClientIpLoggingFilter();
        FilterRegistrationBean<ClientIpLoggingFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(filter.getOrder());
        reg.addUrlPatterns("/*");
        return reg;
    }
}
