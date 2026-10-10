package io.orphos.eip.webbff.config;

import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.tenant.TenantContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The two beans the fan-out needs: the executor that runs downstream calls in parallel
 * and the lenient HTTP client they use.
 */
@Configuration
public class FanOutConfig {

    /**
     * Pool for parallel downstream calls.
     *
     * <p>Decorated with {@link TenantContextTaskDecorator} (auto-registered by eip-common)
     * because {@code TenantContextHolder} is ThreadLocal-backed and does not cross a
     * {@code CompletableFuture.supplyAsync} boundary on its own — without it, tenant-scoped
     * downstream work would throw {@code MissingTenantContextException} inside the pool.</p>
     *
     * <p>The rejection policy is {@code CallerRunsPolicy}: if the pool and its queue are both
     * full, the aggregation runs that call on the request thread rather than dropping a tile.
     * Slower under load, but never a silently missing panel.</p>
     */
    @Bean(name = "webBffFanOutExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor webBffFanOutExecutor(WebBffProperties props,
                                                       TenantContextTaskDecorator decorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getFanOut().getPoolSize());
        executor.setMaxPoolSize(props.getFanOut().getPoolSize());
        executor.setQueueCapacity(props.getFanOut().getQueueCapacity());
        executor.setThreadNamePrefix("web-bff-fanout-");
        executor.setTaskDecorator(decorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Lenient client: returns 4xx/5xx instead of throwing, so the gateway can map a status
     * onto {@link io.orphos.eip.webbff.model.SourceStatus} rather than reading it back out
     * of an exception. Timeouts come from {@code eip.http.*} via the shared factory.
     */
    @Bean(name = "webBffRestTemplate")
    public RestTemplate webBffRestTemplate(EipRestTemplates factory) {
        return factory.createLenient();
    }
}
