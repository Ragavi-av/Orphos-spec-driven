package io.orphos.eip.admin.migration;

import io.orphos.eip.core.arcadedb.ArcadeConnectionResolver;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.common.Ids;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Applies pending {@link TenantMigration}s against a given database and records
 * the outcome in that database's {@code SchemaMigration} table.
 *
 * <p>Semantics:
 * <ul>
 *   <li><b>Pending</b> = migration version not present (with status {@code APPLIED})
 *       in the target DB's SchemaMigration table.</li>
 *   <li><b>Failed</b> rows from a previous run are retried — the runner deletes
 *       the prior {@code FAILED} row before attempting the migration again,
 *       so the history shows the latest outcome only.</li>
 *   <li><b>Order</b> = by {@link TenantMigration#version()} ascending. A failure
 *       aborts the run; subsequent migrations are NOT attempted.</li>
 * </ul>
 */
@Component
public class MigrationRunner {

    private static final Logger log = LogManager.getLogger(MigrationRunner.class);

    /** Migration row status values. Strings rather than enum for JSON ergonomics. */
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_FAILED  = "FAILED";

    private final ArcadeDbTemplate arcade;
    private final MigrationRegistry registry;
    private final ArcadeConnectionResolver resolver;

    public MigrationRunner(ArcadeDbTemplate arcade, MigrationRegistry registry,
                           ArcadeConnectionResolver resolver) {
        this.arcade = arcade;
        this.registry = registry;
        this.resolver = resolver;
    }

    /**
     * Template for a tenant's ArcadeDB connection. {@code connectionId} null →
     * the default/platform template (no resolver round-trip); a real id routes
     * to that connection's server via the resolver.
     */
    private ArcadeDbTemplate templateFor(String connectionId) {
        if (connectionId == null || connectionId.isBlank() || resolver == null) {
            return arcade;
        }
        return resolver.templateFor(connectionId);
    }

    /**
     * Returns the migration history of {@code databaseName} — every row in the
     * target DB's SchemaMigration table, plus a synthetic "pending" entry for
     * each registered migration whose version has not yet been recorded.
     */
    public List<MigrationStatus> statusOf(String databaseName) {
        return statusOf(null, databaseName);
    }

    /** {@link #statusOf(String)} against the tenant's chosen ArcadeDB connection. */
    public List<MigrationStatus> statusOf(String connectionId, String databaseName) {
        ArcadeDbTemplate template = templateFor(connectionId);
        Map<Integer, Map<String, Object>> applied = loadAppliedByVersion(template, databaseName);
        List<MigrationStatus> out = new ArrayList<>();
        for (TenantMigration m : registry.all()) {
            Map<String, Object> row = applied.get(m.version());
            if (row == null) {
                out.add(MigrationStatus.pending(m.version(), m.description()));
            } else {
                out.add(MigrationStatus.from(row, m.description()));
            }
        }
        // Also surface any SchemaMigration rows whose version is not in the
        // current registry — these are migrations that have been deleted from
        // code (or applied by a newer service version). They are shown but
        // never re-run.
        Set<Integer> known = new HashSet<>();
        registry.all().forEach(m -> known.add(m.version()));
        applied.forEach((v, row) -> {
            if (!known.contains(v)) {
                out.add(MigrationStatus.unknown(row));
            }
        });
        out.sort((a, b) -> Integer.compare(a.version(), b.version()));
        return out;
    }

    /**
     * Apply every migration not yet recorded (or with status {@code FAILED})
     * in {@code databaseName}. Stops at the first failure.
     *
     * @return summary covering one entry per migration that the runner
     *         attempted in this invocation
     */
    public MigrationRunResult runPending(String databaseName) {
        return runPending(null, databaseName);
    }

    /** {@link #runPending(String)} against the tenant's chosen ArcadeDB connection. */
    public MigrationRunResult runPending(String connectionId, String databaseName) {
        ArcadeDbTemplate template = templateFor(connectionId);
        Map<Integer, Map<String, Object>> applied = loadAppliedByVersion(template, databaseName);
        List<MigrationStatus> attempts = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;

        for (TenantMigration m : registry.all()) {
            Map<String, Object> prior = applied.get(m.version());
            if (prior != null && STATUS_APPLIED.equals(prior.get("status"))) {
                continue;
            }
            if (prior != null) {
                // Clean up a previous FAILED row so the new attempt overwrites
                // history rather than appending a parallel record.
                template.commandNoResultOn(databaseName,
                        "DELETE FROM SchemaMigration WHERE version = :v",
                        Map.of("v", m.version()));
            }
            try {
                m.up(template, databaseName);
                insertRow(template, databaseName, m, STATUS_APPLIED, null);
                attempts.add(MigrationStatus.applied(m.version(), m.description(), Instant.now()));
                succeeded++;
                log.info("Migration v{} applied to '{}': {}", m.version(), databaseName, m.description());
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                try {
                    insertRow(template, databaseName, m, STATUS_FAILED, msg);
                } catch (Exception logFail) {
                    log.warn("Could not record FAILED migration row for v{} on '{}': {}",
                            m.version(), databaseName, logFail.getMessage());
                }
                attempts.add(MigrationStatus.failed(m.version(), m.description(), msg));
                failed++;
                log.error("Migration v{} FAILED on '{}': {}", m.version(), databaseName, msg, e);
                // Halt on first failure — running a later migration on top of
                // a broken earlier one would produce a hard-to-diagnose mess.
                break;
            }
        }
        return new MigrationRunResult(databaseName, attempts.size(), succeeded, failed, attempts);
    }

    private void insertRow(ArcadeDbTemplate template, String databaseName, TenantMigration m,
                           String status, String errorMessage) {
        template.commandNoResultOn(databaseName,
                "INSERT INTO SchemaMigration SET id = :id, version = :v, name = :n, "
                        + "appliedAt = :t, status = :s, errorMessage = :e",
                Map.of(
                        "id", Ids.newId(),
                        "v",  m.version(),
                        "n",  m.description(),
                        "t",  Instant.now().toString(),
                        "s",  status,
                        "e",  errorMessage == null ? "" : errorMessage));
    }

    private Map<Integer, Map<String, Object>> loadAppliedByVersion(ArcadeDbTemplate template, String databaseName) {
        Map<Integer, Map<String, Object>> byVersion = new TreeMap<>();
        try {
            List<Map<String, Object>> rows = template.queryOn(databaseName,
                    "SELECT FROM SchemaMigration ORDER BY appliedAt DESC", Map.of());
            for (Map<String, Object> row : rows) {
                int v = toInt(row.get("version"));
                // First (most recent) row wins — the runner deletes prior FAILED
                // rows before reattempting so dupes shouldn't happen in normal
                // operation, but defend against operator-inserted dupes anyway.
                byVersion.putIfAbsent(v, row);
            }
        } catch (Exception e) {
            // First-ever invocation on a DB where SchemaMigration doctype may
            // not exist yet (very old DB) — treat as empty history.
            log.debug("Could not load SchemaMigration from '{}': {}", databaseName, e.getMessage());
        }
        return byVersion;
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return 0;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    // ── Public records ────────────────────────────────────────────────────

    /** One row in the migration history (real or synthetic-pending). */
    public record MigrationStatus(
            int version,
            String name,
            String status,
            Instant appliedAt,
            String errorMessage
    ) {
        static MigrationStatus pending(int v, String n) {
            return new MigrationStatus(v, n, "PENDING", null, null);
        }
        static MigrationStatus applied(int v, String n, Instant at) {
            return new MigrationStatus(v, n, STATUS_APPLIED, at, null);
        }
        static MigrationStatus failed(int v, String n, String err) {
            return new MigrationStatus(v, n, STATUS_FAILED, Instant.now(), err);
        }
        static MigrationStatus from(Map<String, Object> row, String currentDescription) {
            Instant at = null;
            try {
                String ts = Objects.toString(row.get("appliedAt"), null);
                if (ts != null) at = Instant.parse(ts);
            } catch (Exception ignored) {}
            String name = Objects.toString(row.get("name"),
                    currentDescription == null ? "" : currentDescription);
            return new MigrationStatus(
                    toInt(row.get("version")),
                    name,
                    Objects.toString(row.get("status"), STATUS_APPLIED),
                    at,
                    Objects.toString(row.get("errorMessage"), null));
        }
        /** A row whose version is unknown to the running code (older migration since deleted). */
        static MigrationStatus unknown(Map<String, Object> row) {
            return from(row, "(no longer registered)");
        }
    }

    /** Summary returned by {@link #runPending}. */
    public record MigrationRunResult(
            String databaseName,
            int attempted,
            int succeeded,
            int failed,
            List<MigrationStatus> attempts
    ) {}
}
