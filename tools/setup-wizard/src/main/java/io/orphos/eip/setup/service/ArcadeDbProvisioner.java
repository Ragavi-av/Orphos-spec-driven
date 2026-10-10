package io.orphos.eip.setup.service;

import io.orphos.eip.setup.dto.SetupDtos.CreateDbResult;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Creates an ArcadeDB database (empty, or with the default knowledge-graph
 * structure) on a target server — the wizard's "create database" action.
 *
 * <p>Platform document types (Tenant, AuthMethod, …) are intentionally NOT
 * created here: platform-core's {@code SchemaBootstrap} applies those to its
 * configured database on first boot (and now self-creates that database too).
 * This provisioner is for (a) pre-creating the platform DB on an EXTERNAL server
 * so it exists before boot, and (b) creating extra empty databases by name. The
 * optional schema mirrors {@code scripts/init-arcadedb-schema.bat} (the KG
 * vertex/edge types + id indexes).</p>
 */
@Service
public class ArcadeDbProvisioner {

    private static final List<String> VERTEX_TYPES = List.of(
            "Incident", "ITSystem", "CostCentre", "SLAMetric", "BudgetItem", "Alert", "Decision", "Project");
    private static final List<String> EDGE_TYPES = List.of(
            "IMPACTS", "FUNDS", "HAS_INCIDENT", "HAS_SLA_METRIC", "DEPENDS_ON", "TRIGGERS");
    private static final List<String> INDEX_DDL = List.of(
            "CREATE PROPERTY Incident.id IF NOT EXISTS STRING",
            "CREATE INDEX IF NOT EXISTS ON Incident(id) UNIQUE",
            "CREATE PROPERTY Alert.id IF NOT EXISTS STRING",
            "CREATE INDEX IF NOT EXISTS ON Alert(id) UNIQUE");

    /**
     * Built on first use, never in the constructor. {@code HttpClient.newBuilder()}
     * opens a selector backed by a local socket pair; some Windows hosts refuse
     * that ({@code SocketException: Invalid argument: connect}). As an eager field
     * this took the entire wizard down during bean creation — and because the
     * packaged launcher is a GUI app with no console, the only symptom an operator
     * saw was a bare "Failed to launch JVM" dialog. Lazily, a host that cannot
     * create a client fails just the database actions that need one.
     */
    private volatile HttpClient http;

    private HttpClient http() {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                }
                c = http;
            }
        }
        return c;
    }

    public CreateDbResult create(String url, String user, String password, String database, boolean withSchema) {
        if (url == null || url.isBlank()) {
            return new CreateDbResult(false, false, false, "ArcadeDB URL is required");
        }
        if (database == null || database.isBlank()) {
            return new CreateDbResult(false, false, false, "Database name is required");
        }
        String base = url.trim().replaceAll("/+$", "");
        String db = database.trim();
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                ((user == null ? "root" : user) + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));

        // 1) create the database (idempotent — "already exists" is treated as OK).
        boolean created;
        try {
            HttpResponse<String> r = serverCommand(base, auth, "create database " + db);
            int s = r.statusCode();
            if (s >= 200 && s < 300) {
                created = true;
            } else if (alreadyExists(r.body())) {
                created = false;
            } else if (s == 401 || s == 403) {
                return new CreateDbResult(false, false, false, "Unauthorized (HTTP " + s + ") — check user/password");
            } else {
                return new CreateDbResult(false, false, false, "Create failed: HTTP " + s + " " + trunc(r.body()));
            }
        } catch (Exception e) {
            return new CreateDbResult(false, false, false, "Could not reach ArcadeDB: " + e.getMessage());
        }

        // 2) optionally apply the default KG structure.
        boolean schemaApplied = false;
        if (withSchema) {
            try {
                for (String v : VERTEX_TYPES) dbCommand(base, auth, db, "CREATE VERTEX TYPE " + v + " IF NOT EXISTS");
                for (String e : EDGE_TYPES) dbCommand(base, auth, db, "CREATE EDGE TYPE " + e + " IF NOT EXISTS");
                for (String ddl : INDEX_DDL) dbCommand(base, auth, db, ddl);
                schemaApplied = true;
            } catch (Exception e) {
                return new CreateDbResult(true, created, false,
                        "Database ready, but applying structure failed: " + e.getMessage());
            }
        }

        String msg = (created ? "Created '" + db + "'" : "Database '" + db + "' already existed")
                + (schemaApplied ? " with default structure." : ".")
                + " platform-core adds its own tables on first boot.";
        return new CreateDbResult(true, created, schemaApplied, msg);
    }

    private HttpResponse<String> serverCommand(String base, String auth, String command) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/server"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"" + command + "\"}", StandardCharsets.UTF_8))
                .build();
        return http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void dbCommand(String base, String auth, String db, String sql) throws Exception {
        String body = "{\"language\":\"sql\",\"command\":\"" + sql.replace("\"", "\\\"") + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/command/" + db))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + r.statusCode() + " on '" + sql + "': " + trunc(r.body()));
        }
    }

    private static boolean alreadyExists(String body) {
        return body != null && body.toLowerCase().contains("already exist");
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
