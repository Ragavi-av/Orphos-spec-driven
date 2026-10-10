package io.orphos.eip.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the shared resilience plumbing in every service that depends on {@code eip-common}:
 * one factory bean each for circuit breakers, retries, and bulkheads, bound to their respective
 * {@code eip.resilience.*} defaults.
 *
 * <p>Each factory is gated on its own Resilience4j module being on the classpath
 * ({@code @ConditionalOnClass}), so a module that only pulls in {@code resilience4j-circuitbreaker}
 * (transitively, via this library) still boots even though {@code resilience4j-retry}/
 * {@code -bulkhead} are also on the classpath here — none of the three is required by the others.
 * A service overrides any one of them with its own bean for different thresholds, same as
 * {@code EipHttpAutoConfiguration}.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({EipCircuitBreakerProperties.class, EipRetryProperties.class, EipBulkheadProperties.class})
public class EipResilienceAutoConfiguration {

    @Bean
    @ConditionalOnClass(CircuitBreaker.class)
    @ConditionalOnMissingBean
    public EipCircuitBreakers eipCircuitBreakers(EipCircuitBreakerProperties props) {
        return new EipCircuitBreakers(props);
    }

    @Bean
    @ConditionalOnClass(Retry.class)
    @ConditionalOnMissingBean
    public EipRetries eipRetries(EipRetryProperties props) {
        return new EipRetries(props);
    }

    @Bean
    @ConditionalOnClass(Bulkhead.class)
    @ConditionalOnMissingBean
    public EipBulkheads eipBulkheads(EipBulkheadProperties props) {
        return new EipBulkheads(props);
    }
}
