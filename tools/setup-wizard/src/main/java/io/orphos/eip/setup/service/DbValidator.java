package io.orphos.eip.setup.service;

import io.orphos.eip.setup.dto.SetupDtos.ValidationResult;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;

/**
 * Live pre-install validation of an externally-hosted database, using the SAME
 * credential path a running service would use. This is deliberate: a green check
 * here means provisioning won't fail later with a surprise 401 — the exact
 * confusion ("test OK / provision fails") the wizard is meant to remove.
 */
@Service
public class DbValidator {

    private static final int TIMEOUT_SECONDS = 15;

    /**
     * ArcadeDB: an authenticated GET of the server-info endpoint — the same shape
     * the platform's tenant provisioner uses. A 2xx means the credentials can
     * reach the server-admin API (create-database will work).
     */
    public ValidationResult validateArcade(String url, String user, String password) {
        long start = System.nanoTime();
        if (url == null || url.isBlank()) {
            return new ValidationResult(false, "ArcadeDB URL is required", 0);
        }
        String base = url.trim().replaceAll("/+$", "");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(TIMEOUT_SECONDS, 10)))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + "/api/v1/server"))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET();
            if (user != null && !user.isBlank()) {
                String creds = user + ":" + (password == null ? "" : password);
                rb.header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(creds.getBytes()));
            }
            HttpResponse<Void> resp = client.send(rb.build(), HttpResponse.BodyHandlers.discarding());
            int s = resp.statusCode();
            long ms = elapsedMs(start);
            if (s >= 200 && s < 300) {
                return new ValidationResult(true, "Connected (HTTP " + s + ")", ms);
            }
            String hint = (s == 401 || s == 403) ? " — check user/password" : "";
            return new ValidationResult(false, "ArcadeDB returned HTTP " + s + hint, ms);
        } catch (Exception e) {
            return new ValidationResult(false, safe(e), elapsedMs(start));
        }
    }

    /** Postgres (or any JDBC URL): open one connection and read product metadata. */
    public ValidationResult validatePostgres(String jdbcUrl, String user, String password) {
        long start = System.nanoTime();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return new ValidationResult(false, "JDBC URL is required", 0);
        }
        try {
            Properties props = new Properties();
            if (user != null && !user.isBlank()) props.setProperty("user", user);
            if (password != null && !password.isEmpty()) props.setProperty("password", password);
            DriverManager.setLoginTimeout(TIMEOUT_SECONDS);
            try (Connection conn = DriverManager.getConnection(jdbcUrl.trim(), props)) {
                DatabaseMetaData md = conn.getMetaData();
                return new ValidationResult(true,
                        "Connected — " + md.getDatabaseProductName() + " "
                                + md.getDatabaseProductVersion(),
                        elapsedMs(start));
            }
        } catch (Exception e) {
            return new ValidationResult(false, safe(e), elapsedMs(start));
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Message safe to show — never echoes back the supplied password. */
    private static String safe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) return t.getClass().getSimpleName();
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }
}
