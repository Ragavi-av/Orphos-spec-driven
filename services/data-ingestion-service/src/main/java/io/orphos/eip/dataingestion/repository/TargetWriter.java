package io.orphos.eip.dataingestion.repository;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeConnectionResolver;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;

/**
 * Target-side (ArcadeDB) writes for extraction runs, isolated behind one
 * component so the execution engine can be unit-tested against a mock.
 *
 * <p>Writes land in the tenant's own database when the tenant is DEDICATED
 * (non-blank {@code Tenant.arcadeDatabase} in the default DB — the same
 * resolution the rest of the platform uses, see platform-core's
 * WidgetMetricsService) and in the default database otherwise.</p>
 *
 * <p>ArcadeDB identifiers (type and property names) cannot be parameter-bound —
 * they are string-concatenated into DDL/DML — so everything that becomes an
 * identifier is validated against {@link #IDENT} first to prevent SQL
 * injection through mapping configuration.</p>
 */
@Repository
public class TargetWriter {

    private static final Logger log = LogManager.getLogger(TargetWriter.class);
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final ArcadeDbTemplate arcade;
    private final ArcadeConnectionResolver resolver;

    /** tenantId → its target route (db name + hosting connection). Short TTL so migrations are picked up. */
    private final Cache<String, Route> routeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1_000)
            .build();

    /** A tenant's target DB name ("" = default DB) + the connection whose server hosts it (null = platform). */
    private record Route(String db, String connId) {}

    public TargetWriter(ArcadeDbTemplate arcade, ArcadeConnectionResolver resolver) {
        this.arcade = arcade;
        this.resolver = resolver;
    }

    /**
     * Idempotently create the target document type before a run writes to it.
     * Follows the SchemaBootstrap pattern: CREATE DOCUMENT TYPE IF NOT EXISTS,
     * tolerate CREATE PROPERTY failures (no IF NOT EXISTS support), then
     * CREATE INDEX IF NOT EXISTS — UPSERT (req 6.4) needs the index on the
     * WHERE columns.
     */
    public void ensureType(String targetClass, String keyTarget) {
        requireIdent(targetClass);
        Route db = resolveRoute();
        command(db, "CREATE DOCUMENT TYPE " + targetClass + " IF NOT EXISTS", Map.of());
        tolerate(db, "CREATE PROPERTY " + targetClass + ".tenantId STRING");
        command(db, "CREATE INDEX IF NOT EXISTS ON " + targetClass + " (tenantId) NOTUNIQUE", Map.of());
        if (keyTarget != null && !keyTarget.isBlank()) {
            requireIdent(keyTarget);
            tolerate(db, "CREATE PROPERTY " + targetClass + "." + keyTarget + " STRING");
            // ArcadeDB's UPSERT only matches a WHERE backed by a UNIQUE index
            // (verified against the pinned 26.7 server: NOTUNIQUE and composite
            // indexes are rejected with "Upsert must involve an index"). A key
            // column alone can't be UNIQUE in the shared multi-tenant database,
            // so upserts key on a synthetic tenant-scoped column instead:
            // __ingestKey = "<tenantId>|<keyValue>".
            tolerate(db, "CREATE PROPERTY " + targetClass + ".__ingestKey STRING");
            command(db, "CREATE INDEX IF NOT EXISTS ON " + targetClass + " (__ingestKey) UNIQUE", Map.of());
        }
    }

    /** Requirement 6.3 — FULL load mode wipes this tenant's rows before reloading. */
    public void deleteAll(String targetClass) {
        requireIdent(targetClass);
        command(resolveRoute(), "DELETE FROM " + targetClass + " WHERE tenantId = :t",
                Map.of("t", tenantId()));
    }

    /**
     * Requirement 6.4 — UPSERT merge strategy: update the record matching
     * (keyTarget, tenantId) or insert a new one. Same working syntax as
     * platform-core's ArcadeDataLoader.
     */
    public void upsertRow(String targetClass, String keyTarget, Object keyValue,
                          Map<String, Object> values) {
        requireIdent(targetClass);
        requireIdent(keyTarget);
        StringBuilder sql = new StringBuilder("UPDATE ").append(targetClass).append(" SET ");
        Map<String, Object> params = new LinkedHashMap<>();
        appendAssignments(sql, params, values);
        // Tenant-scoped synthetic key — see ensureType for why the UPSERT
        // cannot match on (tenantId, key) directly.
        sql.append(", __ingestKey = :__key UPSERT WHERE __ingestKey = :__key");
        params.put("__key", tenantId() + "|" + keyValue);
        command(resolveRoute(), sql.toString(), params);
    }

    /** Requirement 6.4 — INSERT merge strategy: append-only load. */
    public void insertRow(String targetClass, Map<String, Object> values) {
        requireIdent(targetClass);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(targetClass).append(" SET ");
        Map<String, Object> params = new LinkedHashMap<>();
        appendAssignments(sql, params, values);
        command(resolveRoute(), sql.toString(), params);
    }

    private void appendAssignments(StringBuilder sql, Map<String, Object> params,
                                   Map<String, Object> values) {
        int i = 0;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            requireIdent(e.getKey());
            if (i++ > 0) sql.append(", ");
            sql.append(e.getKey()).append(" = :").append(e.getKey());
            params.put(e.getKey(), e.getValue());
        }
    }

    /**
     * The current tenant's dedicated database name, or {@code ""} for the
     * default database. Looked up from the {@code Tenant} record in the
     * default DB and cached briefly — this runs per row, the lookup must not.
     *
     * <p>Deliberately does NOT catch a lookup failure and fall back to the default
     * database: a {@code Tenant} row absent (or blank {@code arcadeDatabase}) is the
     * legitimate "not DEDICATED" case and never throws (see the empty-{@code rows}
     * branch below), so anything the query itself throws — ArcadeDB unavailable, the
     * circuit breaker open, a malformed response — means we genuinely don't know this
     * tenant's route. Defaulting anyway would risk silently writing a DEDICATED
     * tenant's rows into the shared default database, a tenant-isolation violation
     * that's worse than the write simply failing. Left to propagate, it reaches the
     * same handling every other {@code TargetWriter} failure already gets in
     * {@code JobExecutionEngine} (the per-row catch marks that row failed and keeps
     * going for {@code insertRow}/{@code upsertRow}; the run-level catch fails the
     * whole run for {@code ensureType}/{@code deleteAll}) — and Caffeine's
     * {@code Cache.get(key, mappingFunction)} does not cache when the mapping
     * function throws, so a transient failure isn't pinned as a wrong route for the
     * cache's 10-minute TTL.</p>
     */
    private Route resolveRoute() {
        String tid = tenantId();
        return routeCache.get(tid, id -> {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT arcadeDatabase, arcadeConnectionId FROM Tenant WHERE id = :id", Map.of("id", id));
            if (rows.isEmpty()) {
                return new Route("", null);   // no Tenant row → legitimately not DEDICATED
            }
            Object db = rows.get(0).get("arcadeDatabase");
            Object connId = rows.get(0).get("arcadeConnectionId");
            String dbName = (db != null && !db.toString().isBlank()) ? db.toString() : "";
            String cid = (connId != null && !connId.toString().isBlank()) ? connId.toString() : null;
            return new Route(dbName, cid);
        });
    }

    private void command(Route r, String sql, Map<String, Object> params) {
        // Route to the tenant's connection server (default template when connId null).
        ArcadeDbTemplate t = resolver.templateFor(r.connId());
        if (r.db().isEmpty()) {
            t.commandNoResult(sql, params);
        } else {
            t.commandNoResultOn(r.db(), sql, params);
        }
    }

    private void tolerate(Route db, String ddl) {
        try {
            command(db, ddl, Map.of());
        } catch (Exception e) {
            // CREATE PROPERTY has no IF NOT EXISTS — duplicate-property errors are expected.
            log.debug("DDL skipped ({}): {}", e.getMessage(), ddl);
        }
    }

    private static void requireIdent(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe ArcadeDB identifier: '" + name + "'");
        }
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }
}
