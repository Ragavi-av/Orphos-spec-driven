package io.orphos.eip.common.tenant;

import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the calling thread's {@link TenantContext} to {@code @Async}
 * executors, {@code ThreadPoolTaskExecutor}s, and any other Spring-managed
 * executor that accepts a {@link TaskDecorator}.
 *
 * <p>Without this decorator, {@link TenantContextHolder}'s {@link ThreadLocal}
 * is invisible inside the executor and tenant-scoped code throws
 * {@link MissingTenantContextException}.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * @Bean
 * public TaskExecutor taskExecutor(TenantContextTaskDecorator decorator) {
 *     ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
 *     exec.setTaskDecorator(decorator);
 *     exec.initialize();
 *     return exec;
 * }
 * }</pre>
 *
 * <p>Note: virtual threads spawned via {@code Thread.startVirtualThread} or
 * {@code Executors.newVirtualThreadPerTaskExecutor()} do NOT inherit
 * {@code ThreadLocal} unless launched as {@code InheritableThreadLocal}.
 * Spring Boot's virtual-thread executor (when {@code spring.threads.virtual.enabled=true})
 * accepts task decorators — wire this bean there as well.</p>
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TenantContext captured = TenantContextHolder.currentOrNull();
        if (captured == null) {
            return runnable;
        }
        return () -> TenantContextHolder.runWith(captured, runnable);
    }
}
