package io.orphos.eip.common.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.util.function.Supplier;

/**
 * Translates ArcadeDB connectivity failures into {@link TenantDataUnavailableException}
 * so the SPA can render a tenant-aware degraded-data banner.
 *
 * <p>Wrap the single choke point in each service's ArcadeDB client / fetcher.
 * I/O errors and any HTTP error response are translated; JSON-parse errors pass
 * through unchanged. Only a genuine outage (I/O failure, or 429/502/503/504)
 * counts toward the circuit breaker — a per-command HTTP 500 (or 4xx) means the
 * store responded and is up, so it is reported but flagged {@code tripsBreaker=false}.</p>
 *
 * <p>The tenant UUID is read from {@link TenantContextHolder} so callers don't
 * have to plumb it through. If no tenant is bound to the current thread (rare
 * — usually a startup or admin-broadcast code path), the exception's tenantId
 * is {@code null} and the SPA banner falls back to generic copy.</p>
 *
 * <p>Usage:
 * <pre>
 *   return ArcadeFailureTranslator.translate("arcade",
 *           () -&gt; restTemplate.exchange(...));
 * </pre>
 */
public final class ArcadeFailureTranslator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArcadeFailureTranslator() {}

    public static <T> T translate(String store, Supplier<T> work) {
        try {
            return work.get();
        } catch (ResourceAccessException e) {
            // Could not reach ArcadeDB (connection refused, timeout): a genuine outage — trips the breaker.
            throw new TenantDataUnavailableException(currentTenantId(), store,
                    "ArcadeDB I/O error: " + e.getMessage(), e);
        } catch (HttpStatusCodeException e) {
            // ArcadeDB RESPONDED with an HTTP error, so the store itself is UP. Two cases:
            //   - 429 / 502 / 503 / 504  → truly unavailable/overloaded: trips the breaker.
            //   - anything else (esp. 500, which ArcadeDB returns for a rejected/idempotent
            //     command, or a 4xx) → a per-command failure, NOT an outage: surface it to the
            //     caller (degraded/failed request) but do NOT count it toward the breaker,
            //     otherwise a startup burst of tolerated DDL errors would trip it and crash the
            //     service. Include ArcadeDB's own error text so the real cause is visible.
            int sc = e.getStatusCode().value();
            boolean unavailable = sc == 429 || sc == 502 || sc == 503 || sc == 504;
            throw new TenantDataUnavailableException(currentTenantId(), store,
                    "ArcadeDB HTTP " + sc + ": " + arcadeReason(e), e, unavailable);
        }
    }

    /**
     * Best-effort extraction of ArcadeDB's own error detail from an error response body.
     * ArcadeDB returns {@code {"error":"...","detail":"...","exception":"..."}}; we
     * prefer the human-readable field and fall back to the raw body, then to the HTTP
     * reason phrase if the body is empty. Truncated so a stack-trace body can't flood logs.
     */
    private static String arcadeReason(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return e.getStatusText();
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            for (String field : new String[]{"detail", "error", "message"}) {
                JsonNode v = node.get(field);
                if (v != null && !v.asText().isBlank()) {
                    return truncate(v.asText());
                }
            }
        } catch (Exception ignore) {
            // Body was not JSON — fall through to the raw text.
        }
        return truncate(body.strip());
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    public static void run(String store, Runnable work) {
        translate(store, () -> { work.run(); return null; });
    }

    /**
     * Builds a {@link TenantDataUnavailableException} for a failure that {@link #translate}
     * itself never sees — e.g. a circuit breaker rejecting the call before it reaches the store.
     * Exposed so a caller composing its own resilience wrapping around {@code translate} (see
     * {@code ArcadeDbTemplate}) can report the same tenant-aware "unavailable" shape for that
     * case too, instead of leaking a raw Resilience4j exception to the SPA.
     */
    public static TenantDataUnavailableException unavailable(String store, String message, Throwable cause) {
        return new TenantDataUnavailableException(currentTenantId(), store, message, cause);
    }

    private static String currentTenantId() {
        return TenantContextHolder.current()
                .map(c -> c.tenantId() == null ? null : c.tenantId().toString())
                .orElse(null);
    }
}
