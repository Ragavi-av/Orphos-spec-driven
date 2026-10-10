package io.orphos.eip.common.access;

import io.orphos.eip.common.access.PersonaPolicy.Classification;
import io.orphos.eip.common.access.PersonaPolicy.Snapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the persona vocabulary out of a tenant's {@code Gov*} vertices — the SQL
 * and the row-mapping, in one place, shared by every service that enforces
 * personas.
 *
 * <h2>Why the queries live in eip-common but the database client does not</h2>
 * {@code eip-common} deliberately does not depend on {@code eip-core-arcadedb};
 * pulling a database client into the shared policy library would invert the layering
 * and drag the dependency into every consumer. Instead callers pass a
 * {@link GraphQuery} — a one-method lambda over their own {@code ArcadeDbTemplate}.
 * That keeps ONE copy of these queries (nl-query and widgets-service previously
 * needed the same five) while leaving each service in charge of its own tenant →
 * database routing, which differs between them: nl-query resolves the tenant's data
 * database explicitly, widgets-service uses the configured default.
 *
 * <p>Caching is also the caller's job, for the same reason: TTLs and invalidation
 * points are service concerns. See {@code PersonaPolicyStore} (nl-query) and
 * {@code PersonaWidgetPolicy} (widgets) for the two adapters.</p>
 */
public final class PersonaGraph {

    private PersonaGraph() {}

    /** A tenant-scoped read against the graph. Params always include {@code tenantId}. */
    @FunctionalInterface
    public interface GraphQuery {
        List<Map<String, Object>> run(String sql, Map<String, Object> params);
    }

    /**
     * Persona → widget bindings, split along the same seam as the ontology.
     *
     * @param watchedWidgetsByRole roleKey → widget keys the persona (or anything it
     *        inherits from) watches. This is RELEVANCE — {@code gov:watchesMetric} —
     *        and drives which widgets are on by default.
     * @param widgetClassification widget key → the data domain and sensitivity of
     *        the metric it renders. This is PERMISSION, and is applied to every
     *        layout regardless of which tier produced it.
     */
    public record WidgetBindings(Map<String, Set<String>> watchedWidgetsByRole,
                                 Map<String, Classification> widgetClassification) {

        public static final WidgetBindings EMPTY = new WidgetBindings(Map.of(), Map.of());

        public boolean isEmpty() {
            return watchedWidgetsByRole.isEmpty() && widgetClassification.isEmpty();
        }
    }

    // ── Snapshot (domain + sensitivity decisions) ─────────────────────────────

    /**
     * Loads the decision snapshot with five flat, indexed reads, closing over the
     * transitive relations in {@link PersonaPolicy#buildSnapshot} rather than with
     * graph traversal — see that class for why.
     *
     * @return {@link Snapshot#EMPTY} if the tenant has no persona vocabulary, which
     *         {@code PersonaPolicy} treats as unenforced
     */
    public static Snapshot loadSnapshot(GraphQuery query, String tenantId) {
        Map<String, Object> p = Map.of("tenantId", tenantId);

        Map<String, Integer> ceilings = new LinkedHashMap<>();
        for (Map<String, Object> r : query.run(
                "SELECT roleKey, out('MAX_SENSITIVITY').sensitivityRank AS rank "
                        + "FROM GovPersona WHERE tenantId = :tenantId", p)) {
            String role = str(r.get("roleKey"));
            if (role == null) continue;
            Integer rank = firstInt(r.get("rank"));
            // No ceiling means Public only. Leaving it null/-1 would deny even
            // Public data, which is a harsher reading than the vocabulary intends.
            ceilings.put(role, rank == null ? 0 : rank);
        }
        if (ceilings.isEmpty()) return Snapshot.EMPTY;

        Map<String, Set<String>> inherits = multi(query.run(
                "SELECT roleKey, out('INHERITS_FROM').roleKey AS parents "
                        + "FROM GovPersona WHERE tenantId = :tenantId", p), "roleKey", "parents");

        Map<String, Set<String>> grants = multi(query.run(
                "SELECT roleKey, out('CAN_ACCESS_DOMAIN').externalId AS domains "
                        + "FROM GovPersona WHERE tenantId = :tenantId", p), "roleKey", "domains");

        Map<String, Set<String>> subDomains = multi(query.run(
                "SELECT externalId, out('SUB_DOMAIN_OF').externalId AS parents "
                        + "FROM GovDataDomain WHERE tenantId = :tenantId", p), "externalId", "parents");

        List<Classification> classifications = new ArrayList<>();
        for (Map<String, Object> r : query.run(
                "SELECT graphType, out('IN_DATA_DOMAIN').externalId AS dom, "
                        + "out('HAS_SENSITIVITY').sensitivityRank AS rank "
                        + "FROM GovClassification WHERE tenantId = :tenantId", p)) {
            String type = str(r.get("graphType"));
            String dom = firstStr(r.get("dom"));
            Integer rank = firstInt(r.get("rank"));
            // A half-classified row cannot be decided on. Dropping it leaves the
            // type unclassified, which an enforced tenant DENIES — the safe way round.
            if (type != null && dom != null && rank != null) {
                classifications.add(new Classification(type, dom, rank));
            }
        }
        return PersonaPolicy.buildSnapshot(ceilings, inherits, grants, subDomains, classifications);
    }

    // ── Widget bindings (relevance + permission) ──────────────────────────────

    /**
     * Loads persona → widget relevance and widget → classification.
     *
     * <p>The relevance side needs the persona inheritance closure, so it is walked
     * here with the same helper the snapshot uses: a CEO watching nothing directly
     * still inherits Executive's widgets.</p>
     */
    public static WidgetBindings loadWidgetBindings(GraphQuery query, String tenantId) {
        Map<String, Object> p = Map.of("tenantId", tenantId);

        // widget key → the metric's domain and sensitivity. Taken from GovMetric,
        // not GovWidget: a widget carries a domain but no sensitivity, and it is the
        // METRIC's sensitivity that decides whether the number may be shown.
        Map<String, Classification> widgetCls = new LinkedHashMap<>();
        for (Map<String, Object> r : query.run(
                "SELECT out('RENDERED_BY').widgetKey AS widgetKey, "
                        + "out('IN_DATA_DOMAIN').externalId AS dom, "
                        + "out('HAS_SENSITIVITY').sensitivityRank AS rank "
                        + "FROM GovMetric WHERE tenantId = :tenantId", p)) {
            String dom = firstStr(r.get("dom"));
            Integer rank = firstInt(r.get("rank"));
            if (dom == null || rank == null) continue;
            for (String widgetKey : allStr(r.get("widgetKey"))) {
                // If two metrics render into one widget, keep the STRICTER of the
                // two: the widget is only safe at the sensitivity of its most
                // sensitive input.
                Classification existing = widgetCls.get(widgetKey);
                if (existing == null || rank > existing.rank()) {
                    widgetCls.put(widgetKey, new Classification(widgetKey, dom, rank));
                }
            }
        }
        if (widgetCls.isEmpty()) return WidgetBindings.EMPTY;

        // roleKey → widget keys watched directly.
        Map<String, Set<String>> directly = new LinkedHashMap<>();
        for (Map<String, Object> r : query.run(
                "SELECT roleKey, out('WATCHES_METRIC').out('RENDERED_BY').widgetKey AS widgetKeys "
                        + "FROM GovPersona WHERE tenantId = :tenantId", p)) {
            String role = str(r.get("roleKey"));
            if (role == null) continue;
            directly.computeIfAbsent(role, k -> new LinkedHashSet<>()).addAll(allStr(r.get("widgetKeys")));
        }

        Map<String, Set<String>> inherits = multi(query.run(
                "SELECT roleKey, out('INHERITS_FROM').roleKey AS parents "
                        + "FROM GovPersona WHERE tenantId = :tenantId", p), "roleKey", "parents");

        Map<String, Set<String>> watched = new LinkedHashMap<>();
        for (String role : directly.keySet()) {
            Set<String> keys = new LinkedHashSet<>();
            for (String ancestor : PersonaPolicy.reachable(role, inherits)) {
                keys.addAll(directly.getOrDefault(ancestor, Set.of()));
            }
            watched.put(role, Set.copyOf(keys));
        }
        // Personas that watch nothing directly but inherit something still need an entry.
        for (String role : inherits.keySet()) {
            if (watched.containsKey(role)) continue;
            Set<String> keys = new LinkedHashSet<>();
            for (String ancestor : PersonaPolicy.reachable(role, inherits)) {
                keys.addAll(directly.getOrDefault(ancestor, Set.of()));
            }
            watched.put(role, Set.copyOf(keys));
        }

        return new WidgetBindings(Map.copyOf(watched), Map.copyOf(widgetCls));
    }

    /**
     * Reuses the whole {@link PersonaPolicy} decision for widgets by presenting the
     * widget classifications as if they were graph types: the personas are the same,
     * only the thing being classified differs. So {@code PersonaPolicy.allows(…,
     * widgetKey)} needs no widget-specific logic and cannot drift from the
     * record-level rule.
     */
    public static Snapshot asWidgetSnapshot(Snapshot snapshot, WidgetBindings bindings) {
        if (snapshot == null || !snapshot.provisioned() || bindings == null) return Snapshot.EMPTY;
        return new Snapshot(snapshot.personaByRole(), bindings.widgetClassification());
    }

    // ── row helpers (ArcadeDB returns traversal projections as lists) ──────────

    private static Map<String, Set<String>> multi(List<Map<String, Object>> rows,
                                                  String keyField, String valueField) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String key = str(r.get(keyField));
            if (key == null) continue;
            out.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(allStr(r.get(valueField)));
        }
        return out;
    }

    /**
     * ArcadeDB 26.7.2 returns an edge-traversal projection as a LIST even for a
     * single match, so every such value is unwrapped rather than cast.
     */
    private static Collection<String> allStr(Object v) {
        if (v == null) return List.of();
        if (v instanceof Collection<?> c) {
            List<String> out = new ArrayList<>();
            for (Object o : c) {
                if (o instanceof Collection<?> nested) {      // out(..).out(..) nests
                    for (Object n : nested) {
                        String s = str(n);
                        if (s != null) out.add(s);
                    }
                } else {
                    String s = str(o);
                    if (s != null) out.add(s);
                }
            }
            return out;
        }
        String s = str(v);
        return s == null ? List.of() : List.of(s);
    }

    private static String firstStr(Object v) {
        Collection<String> all = allStr(v);
        return all.isEmpty() ? null : all.iterator().next();
    }

    private static Integer firstInt(Object v) {
        String s = firstStr(v);
        if (s == null) return null;
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}
