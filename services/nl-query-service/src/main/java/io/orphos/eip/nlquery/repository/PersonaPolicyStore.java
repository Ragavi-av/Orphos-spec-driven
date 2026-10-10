package io.orphos.eip.nlquery.repository;

import io.orphos.eip.common.access.PersonaGraph;
import io.orphos.eip.common.access.PersonaPolicy;
import io.orphos.eip.common.access.PersonaPolicy.Snapshot;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Loads the per-tenant {@link Snapshot} that {@link PersonaPolicy} decides
 * against, from the {@code Gov*} vertices written by
 * {@code scripts/ontology/LoadPersonaDomainProfile} — the graph mirror of
 * {@code ontology/eip-governance.owl}.
 *
 * <p>The queries and row-mapping live in {@link PersonaGraph} so this service and
 * widgets-service share one implementation; this class supplies the database
 * routing and the cache, which differ per service. The transitive closures are
 * computed in Java by {@link PersonaPolicy#buildSnapshot} rather than with ArcadeDB
 * {@code TRAVERSE} — one round trip per edge type instead of one traversal per
 * persona per request, and the closure logic stays unit-testable.</p>
 *
 * <h2>Failure behaviour</h2>
 * Any failure — missing {@code Gov*} types (a tenant that has not adopted the
 * persona model), unreachable database, malformed rows — yields
 * {@link Snapshot#EMPTY}, which {@code PersonaPolicy} treats as <b>unenforced</b>.
 * Unlike {@code AccessEnforcementFilter}, which fails closed when the access map is
 * unavailable, this layer only narrows data within screens the user may already see,
 * so a control-plane blip must not blank out the data plane. The trade-off is
 * explicit — an outage degrades to "no persona filtering" rather than "no data" —
 * and it is why the empty result is <b>not cached</b>, so
 * enforcement resumes on the next request once the graph is reachable again.
 */
@Repository
public class PersonaPolicyStore {

    private static final Logger log = LogManager.getLogger(PersonaPolicyStore.class);

    private final ArcadeDbTemplate arcade;
    private final long ttlMillis;

    private record Entry(Snapshot snapshot, long expiresAt) {}
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public PersonaPolicyStore(ArcadeDbTemplate arcade,
                              @Value("${persona.policy-cache-ttl-ms:60000}") long ttlMillis) {
        this.arcade = arcade;
        this.ttlMillis = Math.max(0, ttlMillis);
    }

    /**
     * The snapshot for one tenant, cached for the configured TTL.
     *
     * @param db       the tenant's resolved data database — passed in rather than
     *                 re-resolved here, so there is exactly one implementation of
     *                 tenant→database routing in this service
     * @param tenantId the tenant whose vocabulary to load
     */
    public Snapshot snapshot(String db, String tenantId) {
        if (db == null || tenantId == null) return Snapshot.EMPTY;
        String key = db + "::" + tenantId;
        long now = System.currentTimeMillis();
        if (ttlMillis > 0) {
            Entry cached = cache.get(key);
            if (cached != null && cached.expiresAt() > now) return cached.snapshot();
        }
        Snapshot loaded = load(db, tenantId);
        // Only cache a real vocabulary. An empty snapshot means "unavailable or not
        // adopted", and caching that would keep a tenant unenforced for a full TTL
        // after its data becomes readable again.
        if (ttlMillis > 0 && loaded.provisioned()) {
            cache.put(key, new Entry(loaded, now + ttlMillis));
        }
        return loaded;
    }

    /** Drops cached policy — call after a vocabulary edit if promptness matters. */
    public void invalidate() {
        cache.clear();
    }

    private Snapshot load(String db, String tenantId) {
        try {
            Snapshot snapshot = PersonaGraph.loadSnapshot(
                    (sql, params) -> arcade.queryOn(db, sql, params), tenantId);
            log.debug("Persona policy loaded (tenant={}, db={}): {} personas, {} classified types",
                    tenantId, db, snapshot.personaByRole().size(),
                    snapshot.classificationByType().size());
            return snapshot;
        } catch (Exception e) {
            // Includes "type GovPersona not found" for tenants that have not adopted
            // the model — an expected state, hence debug rather than warn.
            log.debug("Persona policy unavailable (tenant={}, db={}): {} — enforcement disabled",
                    tenantId, db, e.getMessage());
            return Snapshot.EMPTY;
        }
    }

}
