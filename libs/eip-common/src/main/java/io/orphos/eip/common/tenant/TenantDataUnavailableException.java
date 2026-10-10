package io.orphos.eip.common.tenant;

/**
 * Thrown when a tenant-scoped data store (ArcadeDB, Fuseki, …) is unreachable
 * or in a known-degraded state and the service cannot serve the request.
 *
 * <p>{@link io.orphos.eip.common.web.GlobalExceptionHandler} translates this
 * into a {@code 503 Service Unavailable} response with the
 * {@code X-Tenant-DB-Status: unavailable} header so the SPA can render a
 * tenant-aware degraded-data banner instead of a generic error toast.</p>
 *
 * <p>Use case: in DEDICATED isolation mode, the tenant's dedicated ArcadeDB
 * may be temporarily down without affecting any other tenant. The SPA must
 * distinguish this from a global outage.</p>
 */
public class TenantDataUnavailableException extends RuntimeException {

    private final String tenantId;
    private final String store;
    /**
     * Whether this failure means the store is genuinely UNAVAILABLE (unreachable / overloaded)
     * — the only kind that should count toward the circuit breaker's failure rate. A store that
     * RESPONDED with a per-command error (e.g. an ArcadeDB HTTP 500 for a bad or idempotent
     * statement) is still up: it must surface as a degraded/failed request to the caller but must
     * NOT open the breaker, or a burst of such errors (e.g. schema bootstrap re-runs at startup)
     * would wrongly trip the breaker and take the whole service down. See {@code EipCircuitBreakers}.
     */
    private final boolean tripsBreaker;

    public TenantDataUnavailableException(String tenantId, String store, String message, Throwable cause) {
        this(tenantId, store, message, cause, true);
    }

    public TenantDataUnavailableException(String tenantId, String store, String message) {
        this(tenantId, store, message, null, true);
    }

    public TenantDataUnavailableException(String tenantId, String store, String message,
                                          Throwable cause, boolean tripsBreaker) {
        super(message, cause);
        this.tenantId = tenantId;
        this.store = store;
        this.tripsBreaker = tripsBreaker;
    }

    /** True when this failure should count toward opening the circuit breaker (a real outage). */
    public boolean tripsBreaker() {
        return tripsBreaker;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getStore() {
        return store;
    }
}
