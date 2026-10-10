package io.orphos.eip.nlquery.vo;

import io.orphos.eip.nlquery.service.AiUsageRecorder;
import java.time.Instant;

/**
 * One audit row written per chat call. Metric-only — no prompt or response
 * text is captured, only sizes, so the table never holds user PII.
 *
 * <p>The tenant UUID is captured at the call site (BEFORE the @Async dispatch
 * in AiUsageRecorder.record) so we don't depend on the executor preserving
 * TenantContextHolder across thread boundaries.</p>
 */
public record AiUsageEvent(
        String  tenantId,
        String  userSub,
        String  providerId,
        String  modelName,
        int     promptChars,
        int     responseChars,
        boolean success,
        long    latencyMs,
        String  errorClass,
        Instant timestamp
) {
    public static AiUsageEvent success(String tenantId, String userSub, String providerId, String modelName,
                                       int promptChars, int responseChars, long latencyMs) {
        return new AiUsageEvent(tenantId, userSub, providerId, modelName,
                promptChars, responseChars, true, latencyMs, null, Instant.now());
    }

    public static AiUsageEvent failure(String tenantId, String userSub, String providerId, String modelName,
                                       int promptChars, long latencyMs, String errorClass) {
        return new AiUsageEvent(tenantId, userSub, providerId, modelName,
                promptChars, 0, false, latencyMs, errorClass, Instant.now());
    }
}
