package io.orphos.eip.dataingestion.dao;

import io.orphos.eip.dataingestion.vo.DataSourceDtos;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.RestAuthType;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SourceKind;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.TestResult;
import io.orphos.eip.dataingestion.vo.ErpConfig;
import io.orphos.eip.dataingestion.component.ErpTokenProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Validates connectivity to an enterprise data source. Every test is bounded
 * by {@code ingestion.connection.test-timeout-seconds} (default 30 s, per the
 * requirement "return connection status within 30 seconds") — enforced with a
 * hard {@link Future} cap so a misbehaving driver cannot hang the request.
 *
 * <p>Tests open a single raw connection (no pool) — pool wiring is
 * {@link DataSourcePools}' job once the source is saved.</p>
 */
@Component
public class ConnectionTester {

    private static final Logger log = LogManager.getLogger(ConnectionTester.class);

    private final int timeoutSeconds;
    private final ErpTokenProvider tokenProvider;
    /** Virtual threads: tests are IO-bound and possibly abandoned on timeout. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ConnectionTester(@Value("${ingestion.connection.test-timeout-seconds:30}") int timeoutSeconds,
                            ErpTokenProvider tokenProvider) {
        this.timeoutSeconds = timeoutSeconds;
        this.tokenProvider = tokenProvider;
    }

    /**
     * Test any source kind. {@code secret} is the raw credential (may be null for
     * NONE-auth REST); for {@code OAUTH2} it is the JSON {@code {clientSecret,
     * refreshToken}} bundle. {@code configJson} carries the ERP/OpenAPI config
     * (base/token URLs, data center) for the HTTP kinds — null for JDBC.
     */
    public TestResult test(SourceKind kind, String jdbcUrl, String driverClass, String username,
                           String baseUrl, RestAuthType authType, String authHeaderName,
                           String testPath, String secret, String configJson) {
        long start = System.nanoTime();
        Future<TestResult> future = executor.submit(() ->
                DataSourceDtos.isHttpKind(kind)
                        ? testHttp(baseUrl, testPath, authType, authHeaderName, username, secret, configJson)
                        : testJdbc(jdbcUrl, driverClass, username, secret));
        try {
            TestResult r = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return new TestResult(r.ok(), r.message(), r.detail(), elapsedMs(start));
        } catch (TimeoutException e) {
            future.cancel(true);
            return new TestResult(false,
                    "Connection test timed out after " + timeoutSeconds + " seconds", "", elapsedMs(start));
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new TestResult(false, safeMessage(cause), "", elapsedMs(start));
        }
    }

    private TestResult testJdbc(String jdbcUrl, String driverClass, String username, String password) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return new TestResult(false, "jdbcUrl is required for JDBC sources", "", 0);
        }
        try {
            if (driverClass != null && !driverClass.isBlank()) {
                // GENERIC_JDBC: driver jar supplied via /app/drivers (loader.path).
                Class.forName(driverClass);
            }
            Properties props = new Properties();
            if (username != null && !username.isBlank()) props.setProperty("user", username);
            if (password != null && !password.isEmpty()) props.setProperty("password", password);
            DriverManager.setLoginTimeout(timeoutSeconds);
            try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
                DatabaseMetaData md = conn.getMetaData();
                String detail = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
                return new TestResult(true, "Connected", detail, 0);
            }
        } catch (ClassNotFoundException e) {
            return new TestResult(false,
                    "JDBC driver class not found: " + driverClass +
                    " (mount the driver jar into /app/drivers)", "", 0);
        } catch (Exception e) {
            return new TestResult(false, safeMessage(e), "", 0);
        }
    }

    private TestResult testHttp(String baseUrl, String testPath, RestAuthType authType,
                                String authHeaderName, String username, String secret, String configJson) {
        // OAUTH2: successfully minting an access token already proves the
        // credentials (client + refresh token) are valid. Resolve it up front and
        // fall through as a Bearer request against the probe endpoint (if any).
        String effectiveSecret = secret;
        RestAuthType effectiveAuth = authType;
        boolean tokenAcquired = false;
        if (authType == RestAuthType.OAUTH2) {
            try {
                effectiveSecret = tokenProvider.token(null, ErpConfig.parse(configJson), secret);
                effectiveAuth = RestAuthType.BEARER;
                tokenAcquired = true;
            } catch (Exception e) {
                return new TestResult(false, safeMessage(e), "", 0);
            }
        }
        boolean noProbePath = testPath == null || testPath.isBlank();
        if (baseUrl == null || baseUrl.isBlank() || (tokenAcquired && noProbePath)) {
            // Nothing concrete to GET — a minted token already proves the OAuth
            // credentials, so treat that as success (many ERP roots aren't GET-able).
            if (tokenAcquired) {
                return new TestResult(true, "Authenticated (access token acquired)", "OAUTH2", 0);
            }
            return new TestResult(false, "baseUrl is required for REST sources", "", 0);
        }
        try {
            String url = joinUrl(baseUrl, testPath);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET();
            applyAuth(rb, effectiveAuth, authHeaderName, username, effectiveSecret);
            HttpResponse<Void> resp = client.send(rb.build(), HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            if (status >= 200 && status < 400) {
                return new TestResult(true, "Connected", "HTTP " + status, 0);
            }
            String hint = (status == 401 || status == 403) ? " (check credentials)" : "";
            return new TestResult(false, "Endpoint returned HTTP " + status + hint, "HTTP " + status, 0);
        } catch (IllegalArgumentException e) {
            return new TestResult(false, "Invalid URL: " + safeMessage(e), "", 0);
        } catch (Exception e) {
            return new TestResult(false, safeMessage(e), "", 0);
        }
    }

    /**
     * Apply request auth. For {@code OAUTH2} the caller must resolve the bearer
     * access token first and pass it as {@code secret} — this method treats
     * OAUTH2 exactly like BEARER, so it stays reusable by the extraction path.
     */
    public static void applyAuth(HttpRequest.Builder rb, RestAuthType authType,
                          String authHeaderName, String username, String secret) {
        if (authType == null || authType == RestAuthType.NONE) return;
        switch (authType) {
            case BASIC -> {
                String pair = (username == null ? "" : username) + ":" + (secret == null ? "" : secret);
                rb.header("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(pair.getBytes()));
            }
            case BEARER, OAUTH2 -> rb.header("Authorization", "Bearer " + (secret == null ? "" : secret));
            case API_KEY -> rb.header(
                    (authHeaderName == null || authHeaderName.isBlank()) ? "X-API-Key" : authHeaderName,
                    secret == null ? "" : secret);
            default -> { }
        }
    }

    private static String joinUrl(String base, String path) {
        if (path == null || path.isBlank()) return base;
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Error text safe to return to the admin UI — never includes credentials. */
    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) return t.getClass().getSimpleName();
        // Trim absurdly long driver stack messages to keep API responses sane.
        return msg.length() > 500 ? msg.substring(0, 500) + "…" : msg;
    }
}
