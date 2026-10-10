package io.orphos.eip.admin.component;

import io.orphos.eip.admin.repository.DashboardLayoutRepository;
import io.orphos.eip.common.access.PersonaGraph;
import io.orphos.eip.common.access.PersonaGraph.WidgetBindings;
import io.orphos.eip.common.access.PersonaPolicy;
import io.orphos.eip.common.access.PersonaPolicy.Snapshot;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persona-aware widget policy for the dashboard: which widgets a persona watches
 * by DEFAULT, and which widgets it is PERMITTED to see at all.
 *
 * <p>Those two questions are kept separate because the ontology keeps them
 * separate ({@code gov:watchesMetric} vs {@code gov:canAccessDomain}) and because
 * they are applied at different points:</p>
 * <ul>
 *   <li><b>Relevance</b> seeds a persona's default layout — it is a starting point a
 *       user may freely override.</li>
 *   <li><b>Permission</b> filters the final layout whichever tier produced it. A user
 *       override or a tenant default that names {@code payroll} must not show it to a
 *       CTO, so permission is applied last and cannot be overridden.</li>
 * </ul>
 * Conflating them is why role-based dashboards usually end up either identical or
 * empty.
 *
 * <p>Queries and mapping come from {@link PersonaGraph} (shared with
 * nl-query-service); this class adds the cache. Reads go through
 * {@code ArcadeDbTemplate.query} — the configured default database — matching
 * {@code DashboardLayoutRepository}, so layouts and policy are always read from the
 * same place.</p>
 *
 * <p>Failure or an unprovisioned tenant yields empty policy, which
 * {@link PersonaPolicy} treats as unenforced: dashboards keep working exactly as
 * they did before the persona layer existed. See {@code PersonaPolicy} for the
 * rollout-safety rules.</p>
 */
@Component
public class PersonaWidgetPolicy {

    private static final Logger log = LogManager.getLogger(PersonaWidgetPolicy.class);

    private final ArcadeDbTemplate arcade;
    private final long ttlMillis;

    private record Entry(Snapshot snapshot, WidgetBindings bindings, long expiresAt) {}
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public PersonaWidgetPolicy(ArcadeDbTemplate arcade,
                               @Value("${persona.policy-cache-ttl-ms:60000}") long ttlMillis) {
        this.arcade = arcade;
        this.ttlMillis = Math.max(0, ttlMillis);
    }

    /** True when the current tenant has a persona vocabulary to enforce. */
    public boolean enforced() {
        return PersonaPolicy.enforced(current().snapshot());
    }

    /**
     * The widgets this caller's persona watches by default, or an empty list when
     * unenforced or when the persona watches nothing (Admin, Viewer, Guest).
     * Already permission-filtered — a persona should never default to a widget it
     * may not see, and if it does the vocabulary is inconsistent.
     */
    public List<String> defaultWidgetsFor(Set<String> roles) {
        Entry e = current();
        if (!PersonaPolicy.enforced(e.snapshot()) || roles == null || roles.isEmpty()) return List.of();
        Set<String> watched = new LinkedHashSet<>();
        for (String role : roles) {
            watched.addAll(e.bindings().watchedWidgetsByRole().getOrDefault(role, Set.of()));
        }
        return permitted(new ArrayList<>(watched), roles);
    }

    /**
     * Drops widgets the caller may not see, preserving order. Applied to every
     * layout — user override, tenant default, persona default and system default
     * alike.
     */
    public List<String> permitted(List<String> widgetKeys, Set<String> roles) {
        if (widgetKeys == null || widgetKeys.isEmpty()) return List.of();
        Entry e = current();
        if (!PersonaPolicy.enforced(e.snapshot())) return widgetKeys;
        Snapshot widgetView = PersonaGraph.asWidgetSnapshot(e.snapshot(), e.bindings());
        if (!PersonaPolicy.enforced(widgetView)) return widgetKeys;

        List<String> out = new ArrayList<>();
        for (String key : widgetKeys) {
            // A widget with no classification is one the vocabulary does not know
            // about — a dynamically onboarded widget, for instance. Those are left
            // alone rather than denied: they are not part of the persona model, and
            // hiding a tenant's own onboarded widget would be a regression.
            if (!e.bindings().widgetClassification().containsKey(key)
                    || PersonaPolicy.allows(widgetView, roles, key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** Roles on the current principal — same source the data-level filter uses. */
    public Set<String> currentRoles() {
        return PersonaPolicy.currentRoles();
    }

    public void invalidate() {
        cache.clear();
    }

    // ── loading ───────────────────────────────────────────────────────────────

    private Entry current() {
        String tenantId;
        try {
            tenantId = TenantContextHolder.requireTenantId().toString();
        } catch (Exception e) {
            return new Entry(Snapshot.EMPTY, WidgetBindings.EMPTY, 0L);
        }
        long now = System.currentTimeMillis();
        if (ttlMillis > 0) {
            Entry cached = cache.get(tenantId);
            if (cached != null && cached.expiresAt() > now) return cached;
        }
        Entry loaded = load(tenantId);
        // Only cache a real vocabulary; caching "unavailable" would keep the tenant
        // unenforced for a full TTL after the graph becomes readable again.
        if (ttlMillis > 0 && PersonaPolicy.enforced(loaded.snapshot())) {
            cache.put(tenantId, new Entry(loaded.snapshot(), loaded.bindings(), now + ttlMillis));
        }
        return loaded;
    }

    private Entry load(String tenantId) {
        try {
            PersonaGraph.GraphQuery q = arcade::query;
            Snapshot snapshot = PersonaGraph.loadSnapshot(q, tenantId);
            if (!snapshot.provisioned()) return new Entry(Snapshot.EMPTY, WidgetBindings.EMPTY, 0L);
            WidgetBindings bindings = PersonaGraph.loadWidgetBindings(q, tenantId);
            log.debug("Persona widget policy loaded (tenant={}): {} personas, {} classified widgets",
                    tenantId, snapshot.personaByRole().size(), bindings.widgetClassification().size());
            return new Entry(snapshot, bindings, 0L);
        } catch (Exception e) {
            // Expected for tenants with no Gov* types — hence debug, not warn.
            log.debug("Persona widget policy unavailable (tenant={}): {} — dashboards unfiltered",
                    tenantId, e.getMessage());
            return new Entry(Snapshot.EMPTY, WidgetBindings.EMPTY, 0L);
        }
    }
}
