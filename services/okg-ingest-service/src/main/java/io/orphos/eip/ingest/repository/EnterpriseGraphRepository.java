package io.orphos.eip.ingest.repository;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.ingest.component.EnterpriseSchemaBootstrap;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Upserts Enterprise-ontology vertices ({@code Ent*}) and edges into ArcadeDB —
 * the write side of the schema created by {@code EnterpriseSchemaBootstrap} and
 * described by {@code eip-enterprise.owl}.
 *
 * <p>Stateless, exactly like {@link OkgGraphRepository}: the routed
 * {@link ArcadeDbTemplate} and {@code targetDb} are passed in per call (the
 * caller resolves them once per tenant), so this repo can write to any tenant's
 * ArcadeDB server without holding a single injected template.</p>
 *
 * <p>Identity is {@code (tenantId, externalId)} — the natural upsert key backed
 * by the composite UNIQUE index the bootstrap declares. Every write is a manual
 * SELECT-then-UPDATE-or-INSERT (ArcadeDB has no native MERGE), matching the
 * convention used across this codebase.</p>
 */
@Repository
public class EnterpriseGraphRepository {

    /**
     * Upserts one {@code Ent*} vertex by {@code (tenantId, externalId)}. Only the
     * non-null entries of {@code props} are written, so a partial update never
     * clobbers a field the caller did not supply. {@code props} keys are the
     * ArcadeDB property names — always supplied by our own code (never by request
     * data), so there is no injection surface in the dynamically built SET clause.
     *
     * @return {@code true} if a new vertex was inserted, {@code false} if an existing one was updated.
     */
    public boolean upsertVertex(ArcadeDbTemplate arcade, String targetDb, UUID tenantId, String type,
                                String source, String externalId, Map<String, Object> props) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException(type + " requires a non-blank externalId");
        }
        String tenant = tenantId.toString();
        String now = Instant.now().toString();

        // Keep only non-null values; callers pass a fixed, code-controlled key set.
        Map<String, Object> params = new LinkedHashMap<>();
        props.forEach((k, v) -> {
            if (v != null) params.put(k, v);
        });

        boolean exists = !arcade.queryOn(targetDb,
                "SELECT FROM " + type + " WHERE tenantId = :tenantId AND externalId = :externalId LIMIT 1",
                Map.of("tenantId", tenant, "externalId", externalId)).isEmpty();

        params.put("tenantId", tenant);
        params.put("externalId", externalId);

        if (exists) {
            params.put("updatedAt", now);
            String setClause = buildSetClause(params, List.of("tenantId", "externalId"));
            arcade.commandNoResultOn(targetDb,
                    "UPDATE " + type + " SET " + setClause
                            + " WHERE tenantId = :tenantId AND externalId = :externalId", params);
            return false;
        } else {
            params.put("id", idPrefix(type) + "-" + UUID.randomUUID());
            params.put("source", source);
            params.put("createdAt", now);
            params.put("updatedAt", now);
            String setClause = buildSetClause(params, List.of());
            arcade.commandNoResultOn(targetDb, "INSERT INTO " + type + " SET " + setClause, params);
            return true;
        }
    }

    /**
     * Creates an edge of {@code edgeType} between two already-upserted vertices,
     * idempotently: if an edge of that type already connects the same ordered
     * pair (scoped to the tenant) it is left untouched. Missing endpoints are a
     * no-op (the caller upserts all vertices before any edges, so this only trips
     * on a dangling reference in the request, which we skip rather than fail on).
     *
     * @return {@code true} if a new edge was created.
     */
    public boolean linkOnce(ArcadeDbTemplate arcade, String targetDb, UUID tenantId, String edgeType,
                            String fromType, String fromExternalId, String toType, String toExternalId) {
        if (fromExternalId == null || toExternalId == null) return false;
        String tenant = tenantId.toString();
        Map<String, Object> p = Map.of(
                "tenantId", tenant, "from", fromExternalId, "to", toExternalId);

        // Already linked? out.*/in.* reach the endpoint vertices' properties.
        boolean linked = !arcade.queryOn(targetDb,
                "SELECT FROM " + edgeType + " WHERE out.tenantId = :tenantId AND out.externalId = :from "
                        + "AND in.tenantId = :tenantId AND in.externalId = :to LIMIT 1", p).isEmpty();
        if (linked) return false;

        // Both endpoints must exist, or CREATE EDGE would fail on an empty subquery.
        boolean endpointsExist =
                !arcade.queryOn(targetDb, "SELECT FROM " + fromType
                        + " WHERE tenantId = :tenantId AND externalId = :from LIMIT 1", p).isEmpty()
                && !arcade.queryOn(targetDb, "SELECT FROM " + toType
                        + " WHERE tenantId = :tenantId AND externalId = :to LIMIT 1", p).isEmpty();
        if (!endpointsExist) return false;

        arcade.commandNoResultOn(targetDb,
                "CREATE EDGE " + edgeType
                        + " FROM (SELECT FROM " + fromType + " WHERE tenantId = :tenantId AND externalId = :from) "
                        + "TO (SELECT FROM " + toType + " WHERE tenantId = :tenantId AND externalId = :to)", p);
        return true;
    }

    // ── Read side (verification / GET) ───────────────────────────────────────

    /** All EntCompany rows for the tenant (usually one). */
    public List<Map<String, Object>> findCompanies(ArcadeDbTemplate arcade, String targetDb, UUID tenantId) {
        return arcade.queryOn(targetDb,
                "SELECT FROM EntCompany WHERE tenantId = :tenantId",
                Map.of("tenantId", tenantId.toString()));
    }

    /** Row count of an {@code Ent*} type for the tenant — cheap way to confirm a load populated. */
    public long countOfType(ArcadeDbTemplate arcade, String targetDb, UUID tenantId, String type) {
        List<Map<String, Object>> rows = arcade.queryOn(targetDb,
                "SELECT count(*) AS c FROM " + type + " WHERE tenantId = :tenantId",
                Map.of("tenantId", tenantId.toString()));
        if (rows.isEmpty()) return 0L;
        Object c = rows.get(0).get("c");
        return c instanceof Number n ? n.longValue() : 0L;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String buildSetClause(Map<String, Object> params, List<String> exclude) {
        List<String> parts = new ArrayList<>();
        for (String key : params.keySet()) {
            if (exclude.contains(key)) continue;
            parts.add(key + " = :" + key);
        }
        return String.join(", ", parts);
    }

    /** Short, human-readable id prefix per vertex type (e.g. EntCompany → "ent-company"). */
    private static String idPrefix(String type) {
        String base = type.startsWith("Ent") ? type.substring(3) : type;
        return "ent-" + base.toLowerCase();
    }
}
