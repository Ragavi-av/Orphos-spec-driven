package io.orphos.eip.admin.testsupport;

import io.orphos.eip.core.arcadedb.ArcadeDbProperties;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.arcade.SchemaBootstrap;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;

/**
 * Base class for admin-service integration tests. Boots a single ArcadeDB
 * container shared across every subclass in the same JVM, provisions the
 * SHARED database, and exposes a configured {@link ArcadeDbTemplate} pointing at
 * it.
 *
 * <p>The container is started in a static initialiser rather than the
 * {@code @Testcontainers}/{@code @Container} machinery so the same instance is
 * reused across N subclasses — otherwise each test class would pay the 30–60s
 * ArcadeDB warm-up tax. A shutdown hook stops it when the JVM exits.</p>
 *
 * <p>Helpers:
 * <ul>
 *   <li>{@link #withTenant(UUID, Runnable)} binds a TenantContext for the
 *       duration of the lambda — use it around any repository call so the
 *       admin-service's tenantId-stamping kicks in.</li>
 *   <li>{@link #createDatabase(String)} provisions an additional database
 *       at runtime — used by DEDICATED-mode isolation tests.</li>
 *   <li>{@link #dropDatabase(String)} cleans up between tests.</li>
 * </ul>
 */
public abstract class BaseIntegrationTest {

    public static final String SHARED_DB = "orphos";

    protected static final ArcadeDbContainer ARCADE;
    protected static final ArcadeDbTemplate ARCADE_CLIENT;
    protected static final SchemaBootstrap SCHEMA_BOOTSTRAP;

    static {
        ARCADE = new ArcadeDbContainer();
        ARCADE.start();
        // Shutdown hook handles JVM termination cleanly; Testcontainers' Ryuk
        // daemon would catch a runaway container too, but the explicit stop
        // keeps the parent test report uncluttered by reaper warnings.
        Runtime.getRuntime().addShutdownHook(new Thread(ARCADE::stop));

        createDatabase(SHARED_DB);

        ArcadeDbProperties props = new ArcadeDbProperties();
        props.setUrl(ARCADE.getHttpUrl());
        props.setUser("root");
        props.setPassword(ARCADE.getRootPassword());
        props.setDatabase(SHARED_DB);
        ARCADE_CLIENT = new ArcadeDbTemplate(props);

        SCHEMA_BOOTSTRAP = new SchemaBootstrap(ARCADE_CLIENT);
        org.springframework.test.util.ReflectionTestUtils.setField(SCHEMA_BOOTSTRAP, "defaultDatabase", SHARED_DB);
        SCHEMA_BOOTSTRAP.applyTo(SHARED_DB);
    }

    /** Run {@code work} with {@code tenantId} bound as the active tenant context. */
    protected static void withTenant(UUID tenantId, Runnable work) {
        TenantContextHolder.runWith(
                TenantContext.of(tenantId, tenantId + ".test.invalid"),
                work);
    }

    /** Provision an ArcadeDB database via the REST API. Idempotent. */
    public static void createDatabase(String name) {
        String creds = Base64.getEncoder()
                .encodeToString(("root:" + ARCADE.getRootPassword()).getBytes());
        try {
            // ArcadeDB accepts CREATE DATABASE via the generic /api/v1/server endpoint
            // as a server-level command. Using HttpClient directly so we don't depend
            // on Spring beans before the test container has finished provisioning.
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(ARCADE.getHttpUrl() + "/api/v1/server"))
                            .header("Authorization", "Basic " + creds)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"command\":\"create database " + name + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            // ArcadeDB returns 400 with "already exists" when the DB exists; that's
            // exactly the idempotent behaviour we want.
            if (r.statusCode() >= 400 && !r.body().toLowerCase().contains("already")) {
                throw new IllegalStateException("create database failed: HTTP " + r.statusCode()
                        + " — " + r.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not create database " + name, e);
        }
    }

    /** Drop an ArcadeDB database via the REST API. Used for test cleanup. */
    public static void dropDatabase(String name) {
        String creds = Base64.getEncoder()
                .encodeToString(("root:" + ARCADE.getRootPassword()).getBytes());
        try {
            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(ARCADE.getHttpUrl() + "/api/v1/server"))
                            .header("Authorization", "Basic " + creds)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"command\":\"drop database " + name + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    /** Compute a per-test dedicated DB name that satisfies ArcadeDB's naming rules. */
    protected static String newDedicatedDbName() {
        return "eip_tenant_" + UUID.randomUUID().toString().replace("-", "");
    }
}
