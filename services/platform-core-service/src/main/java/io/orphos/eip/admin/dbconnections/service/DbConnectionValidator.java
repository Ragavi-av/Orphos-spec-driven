package io.orphos.eip.admin.dbconnections.service;

import io.orphos.eip.admin.dbconnections.dto.DbConnectionDtos.TestResult;
import io.orphos.eip.core.dbregistry.ConnectionRegistry;
import io.orphos.eip.core.dbregistry.ConnectionUrls;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test-connects a DB connection definition. Each test is bounded by
 * {@code db-connections.test-timeout-seconds} (default 30s) with a hard
 * {@link Future} cap, mirroring data-ingestion's {@code ConnectionTester} so a
 * misbehaving driver cannot hang the request thread.
 *
 * <p>Validation runs from inside <em>this</em> (platform-core) container's
 * network, which may differ from what a target service can reach — the result
 * is labelled {@code testedFrom = "platform-core"} so operators read it in that
 * light.</p>
 */
@Component
public class DbConnectionValidator {

    private static final Logger log = LogManager.getLogger(DbConnectionValidator.class);
    private static final String TESTED_FROM = "platform-core";

    private final int timeoutSeconds;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DbConnectionValidator(@Value("${db-connections.test-timeout-seconds:30}") int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * @param conn   connection definition (URL derived via {@link ConnectionUrls})
     * @param secret resolved raw password (may be null/blank for unauthenticated tests)
     */
    public TestResult test(ConnectionRegistry.Connection conn, String secret) {
        long start = System.nanoTime();
        ConnectionRegistry.Type type = conn.typeEnum();
        if (type == null) {
            return new TestResult(false, "Unknown connection type: " + conn.type(), "", 0, TESTED_FROM);
        }
        String url = ConnectionUrls.urlFor(conn);
        if (url == null || url.isBlank()) {
            return new TestResult(false, "Connection has no host/url to test", "", 0, TESTED_FROM);
        }
        String user = conn.user();

        Future<TestResult> future = executor.submit(() -> switch (type) {
            case ARCADEDB -> testArcade(url, user, secret);
            case POSTGRES -> testJdbc(url, user, secret);
            case NEO4J    -> testNeo4j(url, user, secret);
        });
        try {
            TestResult r = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return new TestResult(r.ok(), r.message(), r.detail(), elapsedMs(start), TESTED_FROM);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new TestResult(false, "Connection test timed out after " + timeoutSeconds + " seconds",
                    "", elapsedMs(start), TESTED_FROM);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new TestResult(false, safeMessage(cause), "", elapsedMs(start), TESTED_FROM);
        }
    }

    /** ArcadeDB: authenticated GET of the server info endpoint (matches the compose healthcheck auth). */
    private TestResult testArcade(String baseUrl, String user, String password) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/server"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET();
            if (user != null && !user.isBlank()) {
                rb.header(HttpHeaders.AUTHORIZATION, "Basic " + java.util.Base64.getEncoder()
                        .encodeToString(((user + ":" + (password == null ? "" : password))).getBytes()));
            }
            HttpResponse<Void> resp = client.send(rb.build(), HttpResponse.BodyHandlers.discarding());
            int s = resp.statusCode();
            if (s >= 200 && s < 300) {
                return new TestResult(true, "Connected", "ArcadeDB HTTP " + s, 0, TESTED_FROM);
            }
            String hint = (s == 401 || s == 403) ? " (check user/password)" : "";
            return new TestResult(false, "ArcadeDB returned HTTP " + s + hint, "HTTP " + s, 0, TESTED_FROM);
        } catch (Exception e) {
            return new TestResult(false, safeMessage(e), "", 0, TESTED_FROM);
        }
    }

    /** Postgres (or any JDBC): open one raw connection and read product metadata. */
    private TestResult testJdbc(String jdbcUrl, String user, String password) {
        try {
            Properties props = new Properties();
            if (user != null && !user.isBlank()) props.setProperty("user", user);
            if (password != null && !password.isEmpty()) props.setProperty("password", password);
            DriverManager.setLoginTimeout(timeoutSeconds);
            try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
                DatabaseMetaData md = conn.getMetaData();
                return new TestResult(true, "Connected",
                        md.getDatabaseProductName() + " " + md.getDatabaseProductVersion(), 0, TESTED_FROM);
            }
        } catch (Exception e) {
            return new TestResult(false, safeMessage(e), "", 0, TESTED_FROM);
        }
    }

    /** Neo4J: verify connectivity via the bolt driver. */
    private TestResult testNeo4j(String boltUrl, String user, String password) {
        Config config = Config.builder()
                .withConnectionTimeout(Math.min(timeoutSeconds, 10), TimeUnit.SECONDS)
                .withMaxConnectionPoolSize(1)
                .build();
        try (Driver driver = GraphDatabase.driver(boltUrl,
                AuthTokens.basic(user == null ? "" : user, password == null ? "" : password), config)) {
            driver.verifyConnectivity();
            return new TestResult(true, "Connected", "Neo4J bolt", 0, TESTED_FROM);
        } catch (Exception e) {
            return new TestResult(false, safeMessage(e), "", 0, TESTED_FROM);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Error text safe to return to the admin UI — never includes credentials. */
    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) return t.getClass().getSimpleName();
        return msg.length() > 500 ? msg.substring(0, 500) + "…" : msg;
    }
}
