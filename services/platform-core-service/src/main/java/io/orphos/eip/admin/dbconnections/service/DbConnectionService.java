package io.orphos.eip.admin.dbconnections.service;

import io.orphos.eip.admin.dbconnections.dto.DbConnectionDtos.*;
import io.orphos.eip.admin.dbconnections.file.ConnectionsFileStore;
import io.orphos.eip.admin.dbconnections.repo.DbConnectionRepository;
import io.orphos.eip.core.dbregistry.ConnectionRegistry;
import io.orphos.eip.core.dbregistry.ConnectionUrls;
import io.orphos.eip.secrets.SecretsClient;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates DB-connection CRUD, validation, and service assignments.
 *
 * <p>The connections registry <em>file</em> is authoritative; the ArcadeDB
 * mirror is written best-effort (guarded) for audit + last-test enrichment.
 * Raw passwords are written straight to Infisical under a deterministic path
 * ({@code eip/db-connections/<id>#password}) so the bootstrap resolver's
 * rewritten {@code secrets.bindings} entry can resolve them — they never touch
 * the file or ArcadeDB.</p>
 */
@Service
public class DbConnectionService {

    private static final Logger log = LogManager.getLogger(DbConnectionService.class);

    private static final String SECRET_ROOT = "eip/db-connections";
    private static final String SECRET_FIELD = "password";

    /**
     * The ArcadeDB database every service defaults to when no explicit database is
     * assigned — the {@code ${ARCADEDB_DB:orphos}} default baked into each
     * service's {@code application.yml}. Surfaced in the assignment matrix so
     * operators see the schema a service actually uses instead of a blank field.
     * (POSTGRES/NEO4J have no bootstrap consumer yet, so the ArcadeDB default is
     * the only effective one.)
     */
    static final String DEFAULT_ARCADEDB_DATABASE = "orphos";

    private final ConnectionsFileStore fileStore;
    private final DbConnectionValidator validator;
    private final DbConnectionRepository mirror;
    private final SecretsClient secrets;

    public DbConnectionService(ConnectionsFileStore fileStore, DbConnectionValidator validator,
                               DbConnectionRepository mirror, SecretsClient secrets) {
        this.fileStore = fileStore;
        this.validator = validator;
        this.mirror = mirror;
        this.secrets = secrets;
    }

    /**
     * Add-only backfill of the bundled connections on startup. Fresh installs get
     * them from {@code connections-seed}, but an install whose {@code connections.json}
     * predates a newly-added default (e.g. the bundled Postgres) would otherwise never
     * see it — the seed container only writes when the file is absent. This adds any
     * missing bundled connection without touching operator-created ones or assignments.
     *
     * <p>Deliberately re-adds a bundled connection an operator deleted: these are the
     * platform's default safety net, so they reappear on the next restart.</p>
     */
    @PostConstruct
    void ensureBundledConnections() {
        ConnectionRegistry.File file = fileStore.read();
        List<ConnectionRegistry.Connection> next = new ArrayList<>(file.connections());
        List<ConnectionRegistry.Connection> added = new ArrayList<>();
        for (ConnectionRegistry.Connection bundled : BundledConnections.all()) {
            if (file.connection(bundled.id()) == null) {
                next.add(bundled);
                added.add(bundled);
            }
        }
        if (added.isEmpty()) {
            return;
        }
        try {
            fileStore.write(new ConnectionRegistry.File(next, file.assignments()));
        } catch (Exception e) {
            log.warn("DB-connections: could not backfill bundled connections: {}", e.getMessage());
            return;
        }
        added.forEach(c -> {
            log.info("DB-connections: backfilled missing bundled connection '{}'", c.id());
            mirrorConnection(c);
        });
    }

    // ── connections CRUD ────────────────────────────────────────────────────

    public List<ConnectionView> list() {
        return fileStore.read().connections().stream().map(this::toView).toList();
    }

    public ConnectionView create(SaveConnectionRequest req) {
        validateType(req.type());
        ConnectionRegistry.File file = fileStore.read();
        String id = uniqueSlug(req.name(), file);

        String credentialRef = storeSecretIfPresent(id, req.secret(), null);
        ConnectionRegistry.Connection conn = new ConnectionRegistry.Connection(
                id, req.name().trim(), normaliseType(req.type()),
                trimToNull(req.host()), req.port(), trimToNull(req.user()),
                trimToNull(req.url()), safeOptions(req.options()), credentialRef);

        List<ConnectionRegistry.Connection> next = new ArrayList<>(file.connections());
        next.add(conn);
        fileStore.write(new ConnectionRegistry.File(next, file.assignments()));
        mirrorConnection(conn);
        return toView(conn);
    }

    public ConnectionView update(String id, SaveConnectionRequest req) {
        validateType(req.type());
        ConnectionRegistry.File file = fileStore.read();
        ConnectionRegistry.Connection existing = file.connection(id);
        if (existing == null) {
            throw new IllegalArgumentException("No connection with id: " + id);
        }
        String credentialRef = storeSecretIfPresent(id, req.secret(), existing.credentialRef());
        ConnectionRegistry.Connection conn = new ConnectionRegistry.Connection(
                id, req.name().trim(), normaliseType(req.type()),
                trimToNull(req.host()), req.port(), trimToNull(req.user()),
                trimToNull(req.url()), safeOptions(req.options()), credentialRef);

        List<ConnectionRegistry.Connection> next = new ArrayList<>();
        for (ConnectionRegistry.Connection c : file.connections()) {
            next.add(c.id().equals(id) ? conn : c);
        }
        fileStore.write(new ConnectionRegistry.File(next, file.assignments()));
        mirrorConnection(conn);
        return toView(conn);
    }

    public void delete(String id) {
        ConnectionRegistry.File file = fileStore.read();
        ConnectionRegistry.Connection existing = file.connection(id);
        if (existing == null) {
            return;
        }
        // Refuse to orphan an assignment — force the operator to reassign first.
        List<String> usedBy = file.assignments().entrySet().stream()
                .filter(e -> id.equals(e.getValue().connectionId()))
                .map(Map.Entry::getKey)
                .toList();
        if (!usedBy.isEmpty()) {
            throw new IllegalStateException(
                    "Connection '" + id + "' is still assigned to: " + String.join(", ", usedBy)
                            + ". Reassign those services before deleting it.");
        }
        List<ConnectionRegistry.Connection> next = file.connections().stream()
                .filter(c -> !c.id().equals(id)).toList();
        fileStore.write(new ConnectionRegistry.File(next, file.assignments()));
        deleteSecret(existing.credentialRef());
        guard(() -> mirror.deleteConnection(id), "delete mirror connection " + id);
    }

    // ── validation ──────────────────────────────────────────────────────────

    /** Validate a persisted connection, resolving its stored credential. */
    public TestResult validate(String id) {
        ConnectionRegistry.Connection conn = fileStore.read().connection(id);
        if (conn == null) {
            throw new IllegalArgumentException("No connection with id: " + id);
        }
        String secret = resolveSecret(conn.credentialRef());
        TestResult r = validator.test(conn, secret);
        // Ensure the mirror row exists before recording the result — file-seeded
        // connections (e.g. the bundled default) never went through create()/update(),
        // so recordTest()'s UPDATE would otherwise match nothing and the last-test
        // outcome would never persist.
        mirrorConnection(conn);
        guard(() -> mirror.recordTest(id, r.ok(), r.message()), "record test result " + id);
        return r;
    }

    /** Ad-hoc pre-save test from raw request fields (nothing persisted). */
    public TestResult validateAdHoc(TestConnectionRequest req) {
        validateType(req.type());
        ConnectionRegistry.Connection conn = new ConnectionRegistry.Connection(
                "adhoc", "adhoc", normaliseType(req.type()),
                trimToNull(req.host()), req.port(), trimToNull(req.user()),
                trimToNull(req.url()), safeOptions(req.options()), null);
        return validator.test(conn, req.secret());
    }

    // ── provisioning support ────────────────────────────────────────────────

    /** Resolved ArcadeDB server coordinates for a registered connection. */
    public record ResolvedArcade(String url, String user, String password) {}

    /**
     * Resolves an ARCADEDB connection's server coordinates (URL + user +
     * resolved password) so the tenant-provisioning path can create a database
     * and apply schema on that server. Throws if the connection is unknown, is
     * not ARCADEDB, or has no derivable URL.
     */
    public ResolvedArcade resolveArcade(String connectionId) {
        ConnectionRegistry.Connection conn = fileStore.read().connection(connectionId);
        if (conn == null || conn.typeEnum() != ConnectionRegistry.Type.ARCADEDB) {
            throw new IllegalArgumentException(
                    "No ARCADEDB connection '" + connectionId + "' in the connections registry");
        }
        String url = ConnectionUrls.urlFor(conn);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "ArcadeDB connection '" + connectionId + "' has no derivable URL");
        }
        return new ResolvedArcade(url, conn.user(), resolveSecret(conn.credentialRef()));
    }

    /** True if a registered ARCADEDB connection with this id exists (for pre-flight validation). */
    public boolean isKnownArcadeConnection(String connectionId) {
        ConnectionRegistry.Connection conn = fileStore.read().connection(connectionId);
        return conn != null && conn.typeEnum() == ConnectionRegistry.Type.ARCADEDB;
    }

    // ── assignments ───────────────────────────────────────────────────────────

    public AssignmentMatrixView matrix() {
        ConnectionRegistry.File file = fileStore.read();
        List<ConnectionView> conns = file.connections().stream().map(this::toView).toList();

        // The connection an unassigned service falls back to (its env default) —
        // the bundled ArcadeDB. Surfaced so the matrix names it directly instead
        // of the opaque "(bundled default)".
        String defaultConnName = bundledDefaultName(file);

        List<AssignmentRow> rows = new ArrayList<>();
        for (Map.Entry<String, String> svc : KnownArcadeServices.all().entrySet()) {
            ConnectionRegistry.Assignment a = file.assignments().get(svc.getKey());
            String explicitDb = a == null ? null : a.database();
            String effectiveDb = (explicitDb != null && !explicitDb.isBlank())
                    ? explicitDb.trim() : DEFAULT_ARCADEDB_DATABASE;

            String connName = defaultConnName;
            if (a != null && a.connectionId() != null) {
                ConnectionRegistry.Connection c = file.connection(a.connectionId());
                connName = c != null ? c.name() : a.connectionId();
            }

            rows.add(new AssignmentRow(
                    svc.getKey(), svc.getValue(),
                    a == null ? null : a.connectionId(),
                    explicitDb,
                    a == null ? null : a.schemaMode(),
                    effectiveDb,
                    connName));
        }
        return new AssignmentMatrixView(conns, rows);
    }

    /** Display name of the bundled-default connection (env fallback for unassigned services). */
    private static String bundledDefaultName(ConnectionRegistry.File file) {
        ConnectionRegistry.Connection bundled = file.connection(BundledConnections.ARCADEDB_ID);
        if (bundled != null) return bundled.name();
        return file.connections().stream()
                .filter(c -> c.typeEnum() == ConnectionRegistry.Type.ARCADEDB)
                .map(ConnectionRegistry.Connection::name)
                .findFirst()
                .orElse("Bundled default");
    }

    /**
     * Persist the assignment matrix; returns the services whose binding changed.
     *
     * <p>Guards against silent data loss: a change that re-points an
     * already-configured service at a <em>different</em> database in
     * {@code DEFAULT} mode would have the target service create its schema on
     * boot (possibly over an existing, populated database). Such changes require
     * an explicit {@code confirm=true} — the operator's assertion that schema
     * creation is safe / migration is complete. First-time setup (no prior
     * assignment), unassigning back to the default, and {@code EXISTING} mode
     * (operator manages the schema) never require confirmation. When
     * confirmation is required but absent, nothing is written and the risky
     * changes are returned in {@link ApplyResult#confirmations()}.</p>
     */
    public ApplyResult saveAssignments(SaveAssignmentsRequest req) {
        ConnectionRegistry.File file = fileStore.read();
        Map<String, ConnectionRegistry.Assignment> before = file.assignments();
        Map<String, ConnectionRegistry.Assignment> after = new LinkedHashMap<>(before);

        List<AffectedService> affected = new ArrayList<>();
        List<PendingConfirmation> pending = new ArrayList<>();
        for (AssignmentRow row : req.assignments() == null ? List.<AssignmentRow>of() : req.assignments()) {
            String serviceId = row.serviceId();
            if (!KnownArcadeServices.isKnown(serviceId)) {
                continue; // ignore unknown services
            }
            ConnectionRegistry.Assignment prev = before.get(serviceId);
            ConnectionRegistry.Assignment next;
            if (row.connectionId() == null || row.connectionId().isBlank()) {
                next = null; // unassign → fall back to env default
            } else {
                if (file.connection(row.connectionId()) == null) {
                    throw new IllegalArgumentException(
                            "Service '" + serviceId + "' assigned to unknown connection '" + row.connectionId() + "'");
                }
                next = new ConnectionRegistry.Assignment(
                        row.connectionId(), trimToNull(row.database()),
                        row.schemaMode() == null ? "DEFAULT" : row.schemaMode());
            }

            if (Objects.equals(prev, next)) {
                continue; // no change for this service
            }
            if (next == null) {
                after.remove(serviceId);
            } else {
                after.put(serviceId, next);
            }
            affected.add(new AffectedService(serviceId, KnownArcadeServices.container(serviceId)));

            String reason = confirmationReason(file, prev, next);
            if (reason != null) {
                pending.add(new PendingConfirmation(
                        serviceId, KnownArcadeServices.container(serviceId), reason));
            }
        }

        // Risky changes present and not confirmed → refuse, write nothing.
        if (!pending.isEmpty() && !req.confirm()) {
            return new ApplyResult(false, List.of(),
                    "Confirmation required: the change(s) below re-point an already-configured service to a "
                            + "different database in Default mode, where the platform creates its schema on boot. "
                            + "Confirm that schema creation is safe and no data will be lost, then re-submit.",
                    true, pending);
        }

        fileStore.write(new ConnectionRegistry.File(file.connections(), after));
        for (AffectedService a : affected) {
            final String s = a.serviceId();
            ConnectionRegistry.Assignment na = after.get(s);
            guard(() -> mirror.upsertAssignment(s,
                    na == null ? null : na.connectionId(),
                    na == null ? null : na.database(),
                    na == null ? null : na.schemaMode()), "mirror assignment " + s);
        }
        String msg = affected.isEmpty()
                ? "No assignment changes."
                : "Saved. Restart the affected service(s) for the change to take effect.";
        return new ApplyResult(true, affected, msg);
    }

    /**
     * Reason a change needs operator confirmation, or {@code null} when it is
     * safe to apply silently. Only re-pointing an already-bound service at a
     * different database in {@code DEFAULT} mode (or switching a database from
     * {@code EXISTING} to {@code DEFAULT}) — where the platform would run schema
     * DDL on boot — is treated as risky.
     */
    private String confirmationReason(ConnectionRegistry.File file,
                                      ConnectionRegistry.Assignment prev,
                                      ConnectionRegistry.Assignment next) {
        if (prev == null || next == null) {
            return null; // initial setup, or unassign back to the default — safe
        }
        if (next.schemaModeEnum() != ConnectionRegistry.SchemaMode.DEFAULT) {
            return null; // EXISTING → operator owns the schema; the platform runs no DDL
        }
        String prevDb = effectiveDb(prev.database());
        String nextDb = effectiveDb(next.database());
        boolean targetChanged = !Objects.equals(prev.connectionId(), next.connectionId())
                || !prevDb.equals(nextDb);
        boolean turningOnPlatformSchema = prev.schemaModeEnum() == ConnectionRegistry.SchemaMode.EXISTING;
        if (!targetChanged && !turningOnPlatformSchema) {
            return null;
        }
        String toConn = connName(file, next.connectionId());
        if (turningOnPlatformSchema && !targetChanged) {
            return "Switches database '" + nextDb + "' on '" + toConn + "' from Pre-existing to Default mode; "
                    + "the platform will create/modify its schema on a database it previously left untouched.";
        }
        String fromConn = connName(file, prev.connectionId());
        return "Re-points from database '" + prevDb + "' on '" + fromConn + "' to '" + nextDb + "' on '" + toConn
                + "' in Default mode; the platform will create its schema on the target database.";
    }

    /** The database a service effectively uses — its explicit value, else the code default. */
    private static String effectiveDb(String db) {
        return (db == null || db.isBlank()) ? DEFAULT_ARCADEDB_DATABASE : db.trim();
    }

    private static String connName(ConnectionRegistry.File file, String connectionId) {
        if (connectionId == null) return "(default)";
        ConnectionRegistry.Connection c = file.connection(connectionId);
        return c != null ? c.name() : connectionId;
    }

    /** Lists every currently-assigned known service (informational — restart to apply). */
    public ApplyResult apply() {
        ConnectionRegistry.File file = fileStore.read();
        List<AffectedService> assigned = file.assignments().keySet().stream()
                .filter(KnownArcadeServices::isKnown)
                .map(s -> new AffectedService(s, KnownArcadeServices.container(s)))
                .toList();
        return new ApplyResult(true, assigned,
                "Registry file is current. Restart these services to run against their assigned connections.");
    }

    // ── secrets ───────────────────────────────────────────────────────────────

    /** Store a raw secret (if provided) and return the bare {@code path#field} ref. */
    private String storeSecretIfPresent(String id, String rawSecret, String existingRef) {
        if (rawSecret == null || rawSecret.isEmpty()) {
            return existingRef; // keep whatever was there
        }
        if (!secrets.isConfigured()) {
            throw new IllegalStateException(
                    "Secrets backend (Infisical) is not configured; cannot store the connection credential");
        }
        String path = SECRET_ROOT + "/" + id;
        secrets.writeField(path, SECRET_FIELD, rawSecret);
        return path + "#" + SECRET_FIELD;
    }

    private String resolveSecret(String ref) {
        if (ref == null || ref.isBlank()) return null;
        int hash = ref.indexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) return null;
        return secrets.readField(ref.substring(0, hash), ref.substring(hash + 1)).orElse(null);
    }

    private void deleteSecret(String ref) {
        if (ref == null || ref.isBlank() || !secrets.isConfigured()) return;
        int hash = ref.indexOf('#');
        String path = hash > 0 ? ref.substring(0, hash) : ref;
        // Only delete secrets we own under the connections root.
        if (path.startsWith(SECRET_ROOT + "/")) {
            guard(() -> secrets.delete(path), "delete secret " + path);
        }
    }

    // ── mapping / helpers ──────────────────────────────────────────────────────

    private ConnectionView toView(ConnectionRegistry.Connection c) {
        String lastAt = null, lastStatus = null, lastError = null, createdAt = null, updatedAt = null;
        Optional<Map<String, Object>> row = safeFind(c.id());
        if (row.isPresent()) {
            Map<String, Object> m = row.get();
            lastAt = str(m, "lastTestAt");
            lastStatus = str(m, "lastTestStatus");
            lastError = str(m, "lastTestError");
            createdAt = str(m, "createdAt");
            updatedAt = str(m, "updatedAt");
        }
        return new ConnectionView(
                c.id(), c.name(), c.type(), c.host(), c.port(), c.user(),
                ConnectionUrls.urlFor(c), c.options(),
                c.credentialRef() != null && !c.credentialRef().isBlank(),
                lastAt, lastStatus, lastError, createdAt, updatedAt);
    }

    private void mirrorConnection(ConnectionRegistry.Connection c) {
        guard(() -> mirror.upsertConnection(c.id(), c.name(), c.type(), c.host(), c.port(), c.user(),
                c.credentialRef() != null && !c.credentialRef().isBlank()), "mirror connection " + c.id());
    }

    private Optional<Map<String, Object>> safeFind(String id) {
        try {
            return mirror.find(id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void validateType(String type) {
        if (normaliseTypeOrNull(type) == null) {
            throw new IllegalArgumentException("Unsupported connection type: " + type
                    + " (expected ARCADEDB, POSTGRES or NEO4J)");
        }
    }

    private static String normaliseType(String type) {
        return normaliseTypeOrNull(type);
    }

    private static String normaliseTypeOrNull(String type) {
        if (type == null) return null;
        try {
            return ConnectionRegistry.Type.valueOf(type.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, String> safeOptions(Map<String, String> o) {
        return o == null ? Map.of() : o;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        String s = v == null ? null : v.toString();
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Slug from the name, uniqued against existing ids in the file. */
    private static String uniqueSlug(String name, ConnectionRegistry.File file) {
        String base = slugify(name);
        String candidate = base;
        int n = 2;
        while (file.connection(candidate) != null) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    private static String slugify(String name) {
        if (name == null || name.isBlank()) return "conn";
        StringBuilder b = new StringBuilder(name.length());
        for (char c : name.trim().toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
            else if (c == ' ' || c == '_' || c == '-') b.append('-');
        }
        String s = b.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "conn" : s;
    }

    /** Run a best-effort mirror/secret side effect; log and swallow failures. */
    private void guard(Runnable r, String what) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("DB-connections: best-effort step failed ({}): {}", what, e.getMessage());
        }
    }
}
