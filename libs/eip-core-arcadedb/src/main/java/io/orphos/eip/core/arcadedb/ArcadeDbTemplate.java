package io.orphos.eip.core.arcadedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.tenant.ArcadeFailureTranslator;
import io.orphos.eip.common.tenant.TenantDataUnavailableException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Shared HTTP transport for ArcadeDB's REST API — the plumbing that used to
 * be hand-rolled independently in platform-core-service's {@code ArcadeClient},
 * doc-ingest-service's {@code DocArcadeClient}, and mail-insight-service's
 * {@code MailArcadeClient}. Domain-specific query methods (e.g.
 * priority-engine-service's {@code ArcadeDbFetcher.fetchIncidents()}) stay in
 * their owning service and call through this template instead of hand-rolling
 * the same RestTemplate + HttpHeaders + Jackson row-extraction dance.
 *
 * <p>Uses parameter binding ({@code :name}) for all query/command execution to
 * keep callers from string-concatenating user input into SQL.</p>
 *
 * <h2>Resilience</h2>
 * <p>Every instance builds its own circuit breaker + retry pair, keyed by the target server's
 * URL — this class is constructed both as a Spring bean ({@link ArcadeDbAutoConfiguration}, one
 * per service JVM) and by hand outside Spring ({@link ArcadeConnectionResolver}, one per
 * DEDICATED-tenant connection), so state can't be shared via a registry the way
 * {@code EipCircuitBreakers} normally would; a fresh breaker/retry per instance still gives the
 * isolation that matters — a tenant's own dedicated ArcadeDB failing can never open the breaker
 * that guards the platform's bundled ArcadeDB, or vice versa, and no state crosses service JVMs
 * regardless. Tuned tighter than the generic {@code eip.resilience.circuit-breaker.*} defaults
 * since ArcadeDB is the highest-traffic, most-universal dependency in the platform and recovers
 * fast relative to an external SaaS: 15s open (not 30s), 3 half-open trial calls (not 5).</p>
 *
 * <p><b>Retry is read/write-asymmetric</b> — the reason this isn't simply "wrap the same retry
 * around every call": a read-timeout or 5xx after a {@code command()} (INSERT/UPDATE/DELETE/CREATE)
 * is ambiguous — the write may already have committed server-side with only the response lost, so
 * blind-retrying risks a duplicate write. {@code query()}/{@code queryOn()} retry on any I/O or
 * 5xx failure (a read has no such risk); {@code command()}/{@code commandNoResult()}/
 * {@code commandNoResultOn()} retry ONLY when the failure is a definite pre-request connection
 * failure ({@link ConnectException}/{@link UnknownHostException}/{@link NoRouteToHostException} —
 * proof the request never reached the server) and never on a timeout or 5xx, where whether the
 * server received/applied the command is unknown.</p>
 */
public class ArcadeDbTemplate {

    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArcadeDbProperties props;
    private final CircuitBreaker breaker;
    private final Retry readRetry;
    private final Retry writeRetry;

    public ArcadeDbTemplate(ArcadeDbProperties props) {
        this(props, defaultRestTemplate(props));
    }

    public ArcadeDbTemplate(ArcadeDbProperties props, RestTemplate rest) {
        this.props = props;
        this.rest = rest;
        String name = "arcadedb:" + props.getUrl();
        this.breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(3)
                // A per-command error where ArcadeDB RESPONDED (an HTTP 500 for a rejected or
                // idempotent statement, or a 4xx) means the store is UP — it must not count
                // toward opening the breaker. Only genuine unavailability (I/O errors, 429,
                // 502/503/504) is flagged tripsBreaker=true and counts. Without this, a startup
                // burst of tolerated schema-bootstrap DDL errors trips the breaker and the
                // downstream migration/tenant runners fail, crash-looping the whole service.
                .ignoreException(ArcadeDbTemplate::isNonTrippingFailure)
                .build());
        this.readRetry = Retry.of(name + "#read", retryConfig(ArcadeDbTemplate::isRetryableRead));
        this.writeRetry = Retry.of(name + "#write", retryConfig(ArcadeDbTemplate::isPreRequestFailure));
    }

    /** A store-responded, per-command failure that must NOT count toward the breaker's failure rate. */
    private static boolean isNonTrippingFailure(Throwable t) {
        return t instanceof TenantDataUnavailableException tdue && !tdue.tripsBreaker();
    }

    private static RetryConfig retryConfig(Predicate<Throwable> retryOn) {
        return RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2.0))
                .retryOnException(retryOn)
                .build();
    }

    /**
     * Safe for a read: a genuine store outage (no double-apply risk on a query). A per-command
     * error where ArcadeDB responded (tripsBreaker=false, e.g. a rejected/idempotent statement)
     * will never succeed on retry, so it is not retried.
     */
    private static boolean isRetryableRead(Throwable t) {
        return t instanceof TenantDataUnavailableException tdue && tdue.tripsBreaker();
    }

    /**
     * Safe for a write: ONLY a failure proven to have happened before the request reached the
     * server. A 5xx means the server received and processed the request (commit status unknown,
     * but retrying could double-apply); a timeout is ambiguous for the same reason. Deliberately
     * conservative — a connect-phase {@code SocketTimeoutException} is NOT treated as pre-request
     * here because it can't be reliably distinguished from a read-phase timeout without fragile
     * message-string matching, so it's treated as ambiguous (not retried) rather than risk a
     * duplicate write.
     */
    private static boolean isPreRequestFailure(Throwable t) {
        if (!(t instanceof TenantDataUnavailableException tdue)) {
            return false;
        }
        Throwable cause = tdue.getCause();
        if (!(cause instanceof ResourceAccessException)) {
            return false;
        }
        Throwable root = cause.getCause();
        return root instanceof ConnectException
                || root instanceof UnknownHostException
                || root instanceof NoRouteToHostException;
    }

    /**
     * RestTemplate with bounded connect/read timeouts. Without these a stalled
     * ArcadeDB would block the calling request thread indefinitely (bare
     * {@code new RestTemplate()} has no timeout) — the failure that took down
     * request threads across the thin services. Timeouts come from
     * {@link ArcadeDbProperties} so operators can tune them per environment.
     */
    private static RestTemplate defaultRestTemplate(ArcadeDbProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMillis());
        factory.setReadTimeout(props.getReadTimeoutMillis());
        return new RestTemplate(factory);
    }

    /** Execute a read-only SQL query against the configured default database. */
    public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
        return exec("/api/v1/query/", props.getDatabase(), sql, params, readRetry);
    }

    /**
     * Execute a read-only SQL query against a database OTHER than the default —
     * e.g. for reaching into a DEDICATED-mode tenant's own ArcadeDB without
     * re-pointing the whole client.
     */
    public List<Map<String, Object>> queryOn(String databaseName, String sql, Map<String, Object> params) {
        return exec("/api/v1/query/", databaseName, sql, params, readRetry);
    }

    /** Execute a mutating SQL command (INSERT/UPDATE/DELETE/CREATE) against the default database. */
    public List<Map<String, Object>> command(String sql, Map<String, Object> params) {
        return exec("/api/v1/command/", props.getDatabase(), sql, params, writeRetry);
    }

    public void commandNoResult(String sql, Map<String, Object> params) {
        exec("/api/v1/command/", props.getDatabase(), sql, params, writeRetry);
    }

    /** Execute a mutating SQL command against a database OTHER than the configured default. */
    public void commandNoResultOn(String databaseName, String sql, Map<String, Object> params) {
        exec("/api/v1/command/", databaseName, sql, params, writeRetry);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exec(String endpoint, String dbName, String sql, Map<String, Object> params,
                                            Retry retry) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(props.getUser(), props.getPassword());

        Map<String, Object> body = new HashMap<>();
        body.put("language", "sql");
        body.put("command", sql);
        if (params != null && !params.isEmpty()) {
            body.put("params", params);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        // Wrap the HTTP exchange so ArcadeDB connectivity / 5xx failures surface
        // as TenantDataUnavailableException, which the SPA translates to a
        // tenant-aware degraded-data banner. JSON parse errors (caught below)
        // are NOT treated as outages — they indicate a contract bug instead.
        // Circuit breaker + retry wrap the translated call (see class javadoc for why
        // read/write retry differ); a breaker-open rejection is itself translated to
        // TenantDataUnavailableException so callers see one consistent "store unavailable"
        // shape regardless of whether ArcadeDB itself failed or the breaker just tripped.
        ResponseEntity<String> response;
        try {
            response = EipResilienceDecorators.call(
                    () -> ArcadeFailureTranslator.translate("arcade",
                            () -> rest.exchange(props.getUrl() + endpoint + dbName, HttpMethod.POST, entity, String.class)),
                    null, breaker, retry);
        } catch (CallNotPermittedException e) {
            throw ArcadeFailureTranslator.unavailable("arcade",
                    "ArcadeDB circuit breaker open: " + e.getMessage(), e);
        }

        try {
            JsonNode root = mapper.readTree(response.getBody());
            JsonNode result = root.get("result");
            List<Map<String, Object>> out = new ArrayList<>();
            if (result != null && result.isArray()) {
                for (JsonNode node : result) {
                    out.add(mapper.convertValue(node, Map.class));
                }
            }
            return out;
        } catch (Exception e) {
            throw new ArcadeDbException("Failed to parse ArcadeDB response: " + e.getMessage(), e);
        }
    }

    public static class ArcadeDbException extends RuntimeException {
        public ArcadeDbException(String msg, Throwable cause) { super(msg, cause); }
        public ArcadeDbException(String msg) { super(msg); }
    }
}
