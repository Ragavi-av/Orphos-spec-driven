package io.orphos.eip.common.tenant;

import io.orphos.eip.common.resilience.EipCircuitBreakers;
import io.orphos.eip.common.security.SecurityProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.SimpleAsyncTaskExecutorCustomizer;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Wires the tenant-resolution filter and async-propagation decorator into any
 * service that depends on {@code eip-common}.
 *
 * <p>The {@link TenantLookupService} bean is intentionally NOT created here —
 * its canonical implementation lives in {@code platform-core-service} (which owns the
 * {@code Tenant} ArcadeDB doc). Services that don't talk to platform-core-service
 * directly can either depend on it via an HTTP client or register a cached
 * read-through implementation. When no bean is present, the filter still
 * resolves the tenant from the JWT claim and the {@code X-Tenant-Id} header —
 * only the {@code Host}-header lookup path is skipped.</p>
 */
@AutoConfiguration
@ConditionalOnClass(JwtAuthenticationToken.class)
@ConditionalOnProperty(prefix = "eip.tenant", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenantProperties.class)
public class TenantAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TenantContextTaskDecorator tenantContextTaskDecorator() {
        return new TenantContextTaskDecorator();
    }

    /**
     * Attaches {@link TenantContextTaskDecorator} to the auto-configured
     * {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor}
     * — the one Spring Boot creates for {@code @Async} when virtual threads
     * are NOT enabled. Without this customizer, a tenant-scoped request that
     * fans out via {@code @Async} loses its {@link TenantContextHolder}
     * binding on the worker thread.
     *
     * <p>Defensive belt-and-braces: most call sites capture the tenantId
     * before dispatch (the pattern used by {@code AiUsageRecorder}), but
     * this customizer means a developer who forgets that captures still
     * gets the right tenant on the worker thread.</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "tenantContextThreadPoolCustomizer")
    public ThreadPoolTaskExecutorCustomizer tenantContextThreadPoolCustomizer(
            TenantContextTaskDecorator decorator) {
        return executor -> executor.setTaskDecorator(decorator);
    }

    /**
     * Same as above for the auto-configured
     * {@link org.springframework.core.task.SimpleAsyncTaskExecutor} — the
     * executor Spring Boot uses for {@code @Async} when
     * {@code spring.threads.virtual.enabled=true} (which is the Orphos
     * default in most services per the surveys).
     */
    @Bean
    @ConditionalOnMissingBean(name = "tenantContextSimpleAsyncCustomizer")
    public SimpleAsyncTaskExecutorCustomizer tenantContextSimpleAsyncCustomizer(
            TenantContextTaskDecorator decorator) {
        return executor -> executor.setTaskDecorator(decorator);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantResolutionFilter tenantResolutionFilter(
            TenantProperties props,
            org.springframework.beans.factory.ObjectProvider<TenantLookupService> lookupProvider) {
        return new TenantResolutionFilter(props, lookupProvider.getIfAvailable());
    }

    @Bean
    public FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilterRegistration(
            TenantResolutionFilter filter) {
        FilterRegistrationBean<TenantResolutionFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(filter.getOrder());
        reg.addUrlPatterns("/*");
        return reg;
    }

    /**
     * Default {@link ActiveTenantsProvider} for services that poll platform-core-service.
     * Reuses the tenant-discovery URL from {@link SecurityProperties} so a service
     * configures discovery in one place. platform-core-service overrides this with a
     * DB-backed bean.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "eip.security.tenant-discovery", name = "url")
    public ActiveTenantsProvider activeTenantsProvider(SecurityProperties props, EipCircuitBreakers circuitBreakers) {
        SecurityProperties.TenantDiscovery td = props.getTenantDiscovery();
        return new AdminPollingActiveTenantsProvider(
                td.getUrl(), td.getCacheTtl(), td.getConnectTimeout(), td.getReadTimeout(),
                td.getServiceToken(), circuitBreakers);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "eip.security.tenant-discovery", name = "url")
    public TenantTaskExecutor tenantTaskExecutor(ActiveTenantsProvider provider) {
        return new TenantTaskExecutor(provider);
    }
}
