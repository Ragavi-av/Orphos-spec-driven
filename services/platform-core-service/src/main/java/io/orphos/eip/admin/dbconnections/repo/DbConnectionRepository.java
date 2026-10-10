package io.orphos.eip.admin.dbconnections.repo;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Best-effort ArcadeDB mirror of DB-connection metadata, used for audit and to
 * enrich the admin views with the last connection-test outcome. The connections
 * registry <em>file</em> is the source of truth; every method here is called
 * inside a guard in {@link io.orphos.eip.admin.dbconnections.service.DbConnectionService}
 * so a mirror failure (e.g. ArcadeDB itself being re-pointed) never fails the
 * operation.
 *
 * <p>Hand-rolled parameterized SQL via {@link ArcadeDbTemplate}, mirroring the
 * {@code TenantRepository}/{@code AuthRepository} pattern — no JPA.</p>
 */
@Repository
public class DbConnectionRepository {

    private final ArcadeDbTemplate arcade;

    public DbConnectionRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    /** Upsert a connection audit row, preserving {@code createdAt} on update. */
    public void upsertConnection(String id, String name, String type, String host, Integer port,
                                 String user, boolean hasCredential) {
        String now = Instant.now().toString();
        var existing = arcade.query("SELECT createdAt FROM DbConnection WHERE id = :id LIMIT 1",
                Map.of("id", id));
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("name", name);
        p.put("type", type);
        p.put("host", host == null ? "" : host);
        p.put("port", port == null ? 0 : port);
        p.put("user", user == null ? "" : user);
        p.put("hasCredential", hasCredential);
        p.put("ua", now);
        if (existing.isEmpty()) {
            p.put("ca", now);
            arcade.commandNoResult(
                    "INSERT INTO DbConnection SET id = :id, name = :name, type = :type, host = :host, " +
                            "port = :port, user = :user, hasCredential = :hasCredential, " +
                            "createdAt = :ca, updatedAt = :ua", p);
        } else {
            arcade.commandNoResult(
                    "UPDATE DbConnection SET name = :name, type = :type, host = :host, port = :port, " +
                            "user = :user, hasCredential = :hasCredential, updatedAt = :ua WHERE id = :id", p);
        }
    }

    public void recordTest(String id, boolean ok, String message) {
        arcade.commandNoResult(
                "UPDATE DbConnection SET lastTestAt = :at, lastTestStatus = :st, lastTestError = :err WHERE id = :id",
                Map.of("id", id, "at", Instant.now().toString(),
                        "st", ok ? "OK" : "FAILED", "err", ok ? "" : (message == null ? "" : message)));
    }

    public Optional<Map<String, Object>> find(String id) {
        var rows = arcade.query("SELECT FROM DbConnection WHERE id = :id LIMIT 1", Map.of("id", id));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void deleteConnection(String id) {
        arcade.commandNoResult("DELETE FROM DbConnection WHERE id = :id", Map.of("id", id));
    }

    public void upsertAssignment(String serviceId, String connectionId, String database, String schemaMode) {
        String now = Instant.now().toString();
        var existing = arcade.query("SELECT FROM DbConnectionAssignment WHERE serviceId = :s LIMIT 1",
                Map.of("s", serviceId));
        Map<String, Object> p = new HashMap<>();
        p.put("s", serviceId);
        p.put("c", connectionId == null ? "" : connectionId);
        p.put("db", database == null ? "" : database);
        p.put("mode", schemaMode == null ? "DEFAULT" : schemaMode);
        p.put("ua", now);
        if (existing.isEmpty()) {
            arcade.commandNoResult(
                    "INSERT INTO DbConnectionAssignment SET serviceId = :s, connectionId = :c, " +
                            "database = :db, schemaMode = :mode, updatedAt = :ua", p);
        } else {
            arcade.commandNoResult(
                    "UPDATE DbConnectionAssignment SET connectionId = :c, database = :db, " +
                            "schemaMode = :mode, updatedAt = :ua WHERE serviceId = :s", p);
        }
    }

    static String str(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }
}
