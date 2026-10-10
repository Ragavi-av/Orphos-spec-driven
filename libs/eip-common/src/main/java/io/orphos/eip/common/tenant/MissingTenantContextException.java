package io.orphos.eip.common.tenant;

/**
 * Thrown when tenant-scoped code is reached without a bound {@link TenantContext}.
 * <p>This is a programming error — request handlers, repositories, and secret
 * lookups MUST run downstream of {@link TenantResolutionFilter}, or be wrapped
 * in {@link TenantContextHolder#runWith}. Callers should not catch this
 * exception; let the {@code GlobalExceptionHandler} surface it as a 500 so the
 * gap is visible during development.</p>
 */
public class MissingTenantContextException extends RuntimeException {
    public MissingTenantContextException(String message) {
        super(message);
    }
}
