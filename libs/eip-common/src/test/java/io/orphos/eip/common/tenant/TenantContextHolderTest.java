package io.orphos.eip.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the ThreadLocal semantics of {@link TenantContextHolder}. Bugs in
 * this class would cause cross-request tenant leakage — every test cleans up
 * in {@code @AfterEach} to keep the JUnit worker thread tidy.
 */
class TenantContextHolderTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @AfterEach
    void cleanUp() {
        TenantContextHolder.clear();
    }

    @Test
    void currentReturnsEmptyByDefault() {
        assertThat(TenantContextHolder.current()).isEmpty();
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    @Test
    void setAndGetRoundTrip() {
        TenantContextHolder.set(TenantContext.of(TENANT_A, "acme.example"));
        assertThat(TenantContextHolder.currentOrNull())
                .isNotNull()
                .satisfies(ctx -> {
                    assertThat(ctx.tenantId()).isEqualTo(TENANT_A);
                    assertThat(ctx.domain()).isEqualTo("acme.example");
                    assertThat(ctx.platformAdmin()).isFalse();
                });
    }

    @Test
    void clearReturnsToEmpty() {
        TenantContextHolder.set(TenantContext.of(TENANT_A, null));
        TenantContextHolder.clear();
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    @Test
    void requireTenantIdThrowsWhenUnbound() {
        assertThatThrownBy(() -> TenantContextHolder.requireTenantId())
                .isInstanceOf(MissingTenantContextException.class)
                .hasMessageContaining("No tenant context");
    }

    @Test
    void requireTenantIdReturnsBoundValue() {
        TenantContextHolder.set(TenantContext.of(TENANT_A, null));
        assertThat(TenantContextHolder.requireTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void runWithBindsAndClearsWhenNothingWasBoundBefore() {
        AtomicReference<UUID> seen = new AtomicReference<>();
        TenantContextHolder.runWith(
                TenantContext.of(TENANT_A, null),
                () -> seen.set(TenantContextHolder.requireTenantId()));

        assertThat(seen.get()).isEqualTo(TENANT_A);
        // After the runWith returns, the holder should be empty again.
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    @Test
    void runWithRestoresPriorBinding() {
        TenantContextHolder.set(TenantContext.of(TENANT_A, "outer"));

        AtomicReference<UUID> seenInside = new AtomicReference<>();
        TenantContextHolder.runWith(
                TenantContext.of(TENANT_B, "inner"),
                () -> seenInside.set(TenantContextHolder.requireTenantId()));

        // Inside the closure: B is the active tenant.
        assertThat(seenInside.get()).isEqualTo(TENANT_B);
        // After the closure: A is restored, not lost or replaced by B.
        assertThat(TenantContextHolder.requireTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void runWithRestoresEvenOnException() {
        TenantContextHolder.set(TenantContext.of(TENANT_A, null));

        assertThatThrownBy(() -> TenantContextHolder.runWith(
                TenantContext.of(TENANT_B, null),
                () -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        // Critical: a throwing task MUST NOT leave Tenant B bound on the thread.
        assertThat(TenantContextHolder.requireTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void callWithReturnsValueAndRestoresBinding() {
        TenantContextHolder.set(TenantContext.of(TENANT_A, null));

        UUID seenInside = TenantContextHolder.callWith(
                TenantContext.of(TENANT_B, null),
                TenantContextHolder::requireTenantId);

        assertThat(seenInside).isEqualTo(TENANT_B);
        assertThat(TenantContextHolder.requireTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void platformAdminFactoryFlagsCorrectly() {
        TenantContext ctx = TenantContext.platformAdmin(TENANT_A, "acme.example");
        assertThat(ctx.platformAdmin()).isTrue();
        assertThat(ctx.tenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void tenantContextRejectsNullTenantId() {
        assertThatThrownBy(() -> new TenantContext(null, "acme.example", false))
                .isInstanceOf(NullPointerException.class);
    }
}
