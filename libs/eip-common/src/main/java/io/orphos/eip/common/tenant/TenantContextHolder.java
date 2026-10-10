package io.orphos.eip.common.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Static, ThreadLocal-backed holder for the current {@link TenantContext}.
 *
 * <p>The holder is populated by {@link TenantResolutionFilter} on every inbound
 * HTTP request and cleared in a {@code finally} block once the chain returns.
 * Code outside the servlet path (Kafka consumers, scheduled jobs, integration
 * tests) must use {@link #runWith(TenantContext, Runnable)} or
 * {@link #callWith(TenantContext, Supplier)} so the context is properly scoped
 * and cleared.</p>
 *
 * <p><b>Async warning:</b> the underlying {@link ThreadLocal} does NOT propagate
 * across {@code @Async} executors, {@code CompletableFuture.supplyAsync},
 * Project Reactor schedulers, or any custom executor. Wire
 * {@link TenantContextTaskDecorator} into every executor that may run
 * tenant-scoped work, and use {@link #current()} to capture before fork +
 * {@link #runWith(TenantContext, Runnable)} to restore after.</p>
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {}

    /** Returns the current context, or {@code null} if no tenant has been resolved yet. */
    public static TenantContext currentOrNull() {
        return CONTEXT.get();
    }

    public static Optional<TenantContext> current() {
        return Optional.ofNullable(CONTEXT.get());
    }

    /**
     * Returns the current tenant UUID or throws {@link MissingTenantContextException}
     * if no tenant is bound. Use this in code paths that require a tenant
     * (repository queries, secret lookups); use {@link #current()} for
     * defensive checks where absence is acceptable.
     */
    public static UUID requireTenantId() {
        TenantContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new MissingTenantContextException(
                    "No tenant context bound to current thread — request reached tenant-scoped "
                  + "code without passing through TenantResolutionFilter, or async work was "
                  + "dispatched without a TaskDecorator.");
        }
        return ctx.tenantId();
    }

    public static void set(TenantContext ctx) {
        CONTEXT.set(ctx);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Binds {@code ctx} for the duration of {@code work}, then restores whatever
     * was previously bound (typically {@code null}). Safe for nested calls.
     */
    public static void runWith(TenantContext ctx, Runnable work) {
        TenantContext prev = CONTEXT.get();
        CONTEXT.set(ctx);
        try {
            work.run();
        } finally {
            if (prev == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(prev);
            }
        }
    }

    public static <T> T callWith(TenantContext ctx, Supplier<T> work) {
        TenantContext prev = CONTEXT.get();
        CONTEXT.set(ctx);
        try {
            return work.get();
        } finally {
            if (prev == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(prev);
            }
        }
    }
}
