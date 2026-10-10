package io.orphos.eip.admin.tenant.arcade;

import io.orphos.eip.admin.dbconnections.service.DbConnectionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Provisions a fresh ArcadeDB database for a DEDICATED-mode tenant.
 *
 * <p>ArcadeDB exposes a server-admin REST API at
 * {@code POST /api/v1/server} that accepts SQL-like commands such as
 * {@code CREATE DATABASE name}. This client wraps that single endpoint with
 * the credentials already configured for {@code admin-service}'s
 * {@link io.orphos.eip.admin.arcade.ArcadeClient} (basic auth as the root
 * user). Idempotent — a re-create against an existing database is detected
 * by the upstream {@code 5xx Database already exists} response and absorbed
 * with a log message.</p>
 *
 * <p>Mirrors the shape of
 * {@link io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient}:
 * one method per logical operation, idempotent semantics, never throws on
 * a benign "already exists" path.</p>
 */
@Component
public class ArcadeProvisioningClient {

    private static final Logger log = LogManager.getLogger(ArcadeProvisioningClient.class);

    @Autowired
    private RestTemplate rest;

    @Value("${arcadedb.url}")
    private String arcadeUrl;

    @Value("${arcadedb.user}")
    private String arcadeUser;

    @Value("${arcadedb.password}")
    private String arcadePassword;

    /**
     * Resolves a chosen connection's ArcadeDB server coordinates. Injected as a
     * field (not constructor) so the {@code @Value} defaults keep working and
     * test subclasses that override {@code ensureDatabase}/{@code dropDatabase}
     * need no constructor. Only consulted for a non-default connection id.
     */
    @Autowired(required = false)
    private DbConnectionService dbConnections;

    /** ArcadeDB server coordinates for a create/drop against a chosen connection. */
    private record Coords(String url, String user, String password) {}

    /**
     * Coordinates for {@code connectionId}. Null/blank/{@code arcadedb-bundled}
     * → the platform ArcadeDB ({@code arcadedb.*}); any other id is resolved
     * from the connections registry (throws if unknown / not ARCADEDB).
     */
    private Coords coords(String connectionId) {
        if (connectionId == null || connectionId.isBlank()
                || connectionId.equals("arcadedb-bundled") || dbConnections == null) {
            return new Coords(arcadeUrl, arcadeUser, arcadePassword);
        }
        DbConnectionService.ResolvedArcade r = dbConnections.resolveArcade(connectionId);
        return new Coords(r.url(), r.user(), r.password());
    }

    /**
     * Idempotently creates an ArcadeDB database on the server of the given
     * connection ({@code connectionId} null → the platform ArcadeDB). Returns
     * {@code true} if a new database was created, {@code false} if it existed.
     */
    public boolean ensureDatabase(String connectionId, String databaseName) {
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("databaseName must not be blank");
        }
        Coords c = coords(connectionId);
        if (databaseExists(c, databaseName)) {
            log.info("ArcadeDB database '{}' already exists on {} — skipping creation", databaseName, c.url());
            return false;
        }
        sendServerCommand(c, "CREATE DATABASE " + databaseName);
        log.info("Provisioned ArcadeDB database '{}' on {}", databaseName, c.url());
        return true;
    }

    /**
     * Drops an ArcadeDB database on the connection's server. Used for tenant
     * hard-delete only. Best-effort: a missing database logs and returns.
     */
    public void dropDatabase(String connectionId, String databaseName) {
        if (databaseName == null || databaseName.isBlank()) return;
        Coords c = coords(connectionId);
        if (!databaseExists(c, databaseName)) {
            log.warn("ArcadeDB database '{}' did not exist at drop time on {} — nothing to do",
                    databaseName, c.url());
            return;
        }
        sendServerCommand(c, "DROP DATABASE " + databaseName);
        log.info("Dropped ArcadeDB database '{}' on {}", databaseName, c.url());
    }

    private boolean databaseExists(Coords c, String databaseName) {
        // ArcadeDB exposes /api/v1/exists/{db} returning {"result":[true|false]}.
        String url = c.url() + "/api/v1/exists/" + databaseName;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(c.user(), c.password());
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            var resp = rest.exchange(url, HttpMethod.GET, entity, Map.class);
            Object body = resp.getBody();
            if (body instanceof Map<?, ?> m) {
                Object result = m.get("result");
                if (result instanceof Boolean b) return b;
                if (result instanceof java.util.List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Boolean b) {
                    return b;
                }
            }
            return false;
        } catch (HttpStatusCodeException e) {
            // 404 → does not exist
            if (e.getStatusCode().value() == 404) return false;
            log.warn("Could not probe ArcadeDB database '{}' existence: {}", databaseName, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Could not probe ArcadeDB database '{}' existence: {}", databaseName, e.getMessage());
            return false;
        }
    }

    private void sendServerCommand(Coords c, String command) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(c.user(), c.password());
        Map<String, Object> body = Map.of("command", command);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        rest.exchange(c.url() + "/api/v1/server", HttpMethod.POST, entity, Map.class);
    }
}
