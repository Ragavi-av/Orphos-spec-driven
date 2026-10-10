package io.orphos.eip.common.access;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DATA-level access decisions, as opposed to the UI-level decisions in
 * {@link AccessPolicy}. Where {@code AccessPolicy} answers "may this role open
 * this tab", this answers "may this role read this record".
 *
 * <p>The two axes come from {@code ontology/eip-governance.owl} and are loaded
 * from the tenant's graph ({@code GovPersona}, {@code GovDataDomain},
 * {@code GovSensitivity}, {@code GovClassification}):</p>
 * <ol>
 *   <li><b>Domain</b> — the persona must hold the record's data domain, counting
 *       domains granted to any persona it inherits from and expanding down the
 *       sub-domain tree.</li>
 *   <li><b>Sensitivity</b> — the record's sensitivity rank must be at most the
 *       persona's ceiling. Independent of the first axis: holding a domain is not
 *       enough for its most sensitive members.</li>
 * </ol>
 *
 * <p>Both transitive closures are computed ONCE in {@link #buildSnapshot} and
 * cached, rather than being re-derived per request. They are computed here in
 * plain Java rather than by graph traversal so this class stays pure and
 * dependency-free — the same reason {@link AccessPolicy} is — which means the
 * decision logic is unit-testable with no database.</p>
 *
 * <h2>Rollout safety — read this before changing the defaults</h2>
 * Enforcement is deliberately a <b>no-op unless the tenant has been provisioned
 * with a persona vocabulary</b>:
 * <ul>
 *   <li><b>Empty snapshot</b> (no {@code GovPersona} rows for the tenant) →
 *       {@link #enforced(Snapshot)} is false and every {@code allows} call returns
 *       true. Without this, shipping the persona layer would instantly blind every
 *       tenant that has not been migrated.</li>
 *   <li><b>No roles on the caller</b> (background jobs, service-to-service work
 *       with no user principal) → unenforced, for the same reason. Callers that
 *       must be filtered have to present roles.</li>
 *   <li>But once a tenant IS provisioned and roles ARE present, an
 *       <b>unclassified type is denied</b>. Inside an enforced tenant, data that
 *       no rule can reason about is not readable — the failure mode is a missing
 *       section, not a silent leak.</li>
 * </ul>
 * This asymmetry is the whole safety design: fail open across tenants that have
 * not adopted the model, fail closed within one that has.
 */
public final class PersonaPolicy {

    private PersonaPolicy() {}

    /** A persona with its closures already expanded. */
    public record Persona(String roleKey, Set<String> domains, int maxRank) {}

    /** What a graph type is classified as. */
    public record Classification(String graphType, String domain, int rank) {}

    /**
     * An immutable, per-tenant policy snapshot: everything a decision needs, with
     * no further graph access.
     *
     * @param personaByRole       roleKey → persona (closures expanded)
     * @param classificationByType graph type → its domain and sensitivity rank
     */
    public record Snapshot(Map<String, Persona> personaByRole,
                           Map<String, Classification> classificationByType) {

        public static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());

        /** True if this tenant has a persona vocabulary at all. */
        public boolean provisioned() {
            return personaByRole != null && !personaByRole.isEmpty();
        }
    }

    /** True when decisions from this snapshot should actually be applied. */
    public static boolean enforced(Snapshot snapshot) {
        return snapshot != null && snapshot.provisioned();
    }

    /**
     * Builds a snapshot, expanding both transitive closures.
     *
     * <p>All arguments are the flat edge lists as stored, so the caller needs only
     * five trivial indexed queries and none of the closure logic:</p>
     *
     * @param personaCeilings roleKey → sensitivity rank ceiling
     * @param inheritsFrom    roleKey → roleKeys it directly inherits from
     * @param grants          roleKey → domains granted directly to it
     * @param subDomainOf     domain → its direct parent domains (a domain may have
     *                        several: IT Spend is a child of both IT and Finance)
     * @param classifications one entry per classified graph type
     */
    public static Snapshot buildSnapshot(Map<String, Integer> personaCeilings,
                                         Map<String, Set<String>> inheritsFrom,
                                         Map<String, Set<String>> grants,
                                         Map<String, Set<String>> subDomainOf,
                                         Collection<Classification> classifications) {

        // domain → every descendant domain, so a grant on a parent reaches children.
        Map<String, Set<String>> descendants = invertAndClose(subDomainOf);

        Map<String, Persona> personas = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : safe(personaCeilings).entrySet()) {
            String roleKey = e.getKey();

            // Walk the inheritance chain, then take every ancestor's direct grants.
            Set<String> chain = closeOver(roleKey, safe(inheritsFrom));
            Set<String> domains = new LinkedHashSet<>();
            for (String p : chain) {
                for (String d : safe(grants).getOrDefault(p, Set.of())) {
                    domains.add(d);
                    domains.addAll(descendants.getOrDefault(d, Set.of()));
                }
            }
            personas.put(roleKey, new Persona(roleKey, Set.copyOf(domains),
                    e.getValue() == null ? -1 : e.getValue()));
        }

        Map<String, Classification> byType = new LinkedHashMap<>();
        if (classifications != null) {
            for (Classification c : classifications) {
                if (c != null && c.graphType() != null) byType.put(c.graphType(), c);
            }
        }
        return new Snapshot(Map.copyOf(personas), Map.copyOf(byType));
    }

    /**
     * The decision, for ONE role. See the class docstring for the unenforced cases.
     *
     * @return true if the role may read records of {@code graphType}
     */
    public static boolean roleAllows(Snapshot snapshot, String role, String graphType) {
        if (!enforced(snapshot)) return true;
        Persona persona = snapshot.personaByRole().get(role);
        if (persona == null) return false;          // provisioned tenant, unknown role → deny
        Classification cls = snapshot.classificationByType().get(graphType);
        if (cls == null) return false;              // provisioned tenant, unclassified type → deny
        return persona.domains().contains(cls.domain()) && cls.rank() <= persona.maxRank();
    }

    /**
     * Allow-wins across the caller's roles, matching
     * {@link AccessPolicy#anyRoleAllows} — a user holding both {@code cfo} and
     * {@code cto} sees the union, which is what "acting as" a role in the SPA's
     * role picker implies.
     */
    public static boolean allows(Snapshot snapshot, Collection<String> roles, String graphType) {
        if (!enforced(snapshot)) return true;
        if (roles == null || roles.isEmpty()) return true;   // no principal → unenforced
        for (String r : roles) {
            if (roleAllows(snapshot, r, graphType)) return true;
        }
        return false;
    }

    /** Filters a candidate list down to the types the caller may read, preserving order. */
    public static List<String> readableTypes(Snapshot snapshot, Collection<String> roles,
                                             Collection<String> candidateTypes) {
        if (candidateTypes == null) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (String t : candidateTypes) {
            if (allows(snapshot, roles, t)) out.add(t);
        }
        return List.copyOf(out);
    }

    /**
     * True if a type is classified at a sensitivity NO persona may read (i.e. above
     * every persona's ceiling). Used as defence in depth by the RAG corpus builder:
     * data nobody can be shown should never be indexed in the first place, so a
     * retrieval-time filtering bug cannot surface it.
     */
    public static boolean readableByNobody(Snapshot snapshot, String graphType) {
        if (!enforced(snapshot)) return false;
        Classification cls = snapshot.classificationByType().get(graphType);
        if (cls == null) return false;
        for (Persona p : snapshot.personaByRole().values()) {
            if (p.domains().contains(cls.domain()) && cls.rank() <= p.maxRank()) return false;
        }
        return true;
    }

    // ── closure helpers ────────────────────────────────────────────────────────

    /**
     * {@code start} plus everything reachable from it, including itself. Exposed
     * because {@link PersonaGraph} needs the same persona-inheritance closure when
     * resolving watched widgets, and two implementations of it would be two things
     * to keep in step.
     */
    public static Set<String> reachable(String start, Map<String, Set<String>> edges) {
        return closeOver(start, safe(edges));
    }

    /** {@code roleKey} plus everything reachable from it, including itself. */
    private static Set<String> closeOver(String start, Map<String, Set<String>> edges) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (!seen.add(n)) continue;                      // also breaks cycles
            for (String next : edges.getOrDefault(n, Set.of())) {
                if (!seen.contains(next)) queue.add(next);
            }
        }
        return seen;
    }

    /**
     * Turns child→parents into parent→all-descendants. Done by inverting first and
     * then closing, so a domain with several parents (IT Spend under both IT and
     * Finance) is reached from each of them.
     */
    private static Map<String, Set<String>> invertAndClose(Map<String, Set<String>> childToParents) {
        Map<String, Set<String>> parentToChildren = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : safe(childToParents).entrySet()) {
            for (String parent : e.getValue()) {
                parentToChildren.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }
        Map<String, Set<String>> closed = new HashMap<>();
        for (String parent : parentToChildren.keySet()) {
            Set<String> all = closeOver(parent, parentToChildren);
            all.remove(parent);                              // descendants only
            closed.put(parent, Set.copyOf(all));
        }
        return closed;
    }

    private static <K, V> Map<K, V> safe(Map<K, V> m) {
        return m == null ? Map.of() : m;
    }

    /** Roles on the current principal, {@code ROLE_} prefix stripped. */
    public static Set<String> currentRoles() {
        Set<String> roles = new HashSet<>();
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null) {
            for (var a : auth.getAuthorities()) {
                String name = a.getAuthority();
                roles.add(name.startsWith("ROLE_") ? name.substring(5) : name);
            }
        }
        return roles;
    }
}
