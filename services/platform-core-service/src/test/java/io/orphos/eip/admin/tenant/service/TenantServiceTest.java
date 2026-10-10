package io.orphos.eip.admin.tenant.service;

import io.orphos.eip.core.arcadedb.ArcadeDbProperties;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.arcade.SchemaBootstrap;
import io.orphos.eip.admin.migration.MigrationRegistry;
import io.orphos.eip.admin.migration.MigrationRunner;
import io.orphos.eip.admin.tenant.arcade.ArcadeProvisioningClient;
import io.orphos.eip.admin.tenant.dto.TenantDtos;
import io.orphos.eip.admin.tenant.fuseki.FusekiProvisioningClient;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.IsolationMode;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.model.TenantStatus;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the orchestration + rollback paths in {@link TenantService}. The
 * 7 collaborators are all hand-rolled recording stubs — no Mockito, in line
 * with the rest of the admin-service test suite.
 *
 * <p>Two categories of tests:
 * <ul>
 *   <li>{@code create()} — domain validation, default-value selection, full
 *       provisioning sequence for both isolation modes, and the rollback path
 *       when any external provisioning step fails.</li>
 *   <li>{@code hardDelete()} — resource release ordering, cascade flag for
 *       SHARED, demo-tenant guardrail, and partial-failure resilience.</li>
 * </ul>
 */
class TenantServiceTest {

    private static final String DEMO_DOMAIN = "demo.orphos.io";

    private RecordingRepository tenants;
    private RecordingLookupCache lookup;
    private RecordingKeycloak keycloak;
    private RecordingArcade arcade;
    private RecordingFuseki fuseki;
    private RecordingSchema schema;
    private RecordingArcadeClient arcadeClient;
    private TenantService service;

    @BeforeEach
    void setUp() {
        tenants = new RecordingRepository();
        lookup = new RecordingLookupCache();
        keycloak = new RecordingKeycloak();
        arcade = new RecordingArcade();
        fuseki = new RecordingFuseki();
        schema = new RecordingSchema();
        arcadeClient = new RecordingArcadeClient();
        // MigrationRunner is constructed against the real (no-op recording)
        // ArcadeDbTemplate with an empty registry — DEDICATED provisioning will call
        // runPending which then has nothing to do. The orchestration assertions
        // below don't care about the migration step, just that provisioning
        // completes without exception.
        MigrationRunner migrationRunner = new MigrationRunner(
                arcadeClient, new MigrationRegistry(java.util.List.of()), null);
        service = new TenantService(tenants, lookup, keycloak, arcade, fuseki, schema, arcadeClient,
                migrationRunner, null);
        // The @Value-annotated fields aren't populated outside Spring; mirror
        // the defaults from application.yml so the test exercises the production
        // naming conventions.
        ReflectionTestUtils.setField(service, "sharedDatabase", "orphos");
        ReflectionTestUtils.setField(service, "realmPrefix", "tenant-");
    }

    // ── create(): happy paths ─────────────────────────────────────────────

    @Test
    void createSharedTenantWiresKeycloakOnly() {
        Tenant created = service.create(new TenantDtos.CreateTenantRequest(
                "acme.orphos.io", "Acme Corp", IsolationMode.SHARED, null));

        assertThat(created.isolationMode()).isEqualTo(IsolationMode.SHARED);
        assertThat(created.arcadeDatabase()).isEqualTo("orphos");
        assertThat(created.keycloakRealm()).isEqualTo("tenant-" + created.id());
        assertThat(created.status()).isEqualTo(TenantStatus.ACTIVE);

        assertThat(tenants.inserted).containsExactly(created.id());
        assertThat(keycloak.realmsEnsured).containsExactly("tenant-" + created.id());
        // SHARED mode skips ArcadeDB + Fuseki provisioning entirely.
        assertThat(arcade.databasesEnsured).isEmpty();
        assertThat(fuseki.datasetsEnsured).isEmpty();
        assertThat(schema.appliedTo).isEmpty();
        // Lookup cache evicted post-insert so the next read sees the new row.
        assertThat(lookup.evictions).hasSize(1);
    }

    @Test
    void createDedicatedTenantProvisionsAllResources() {
        Tenant created = service.create(new TenantDtos.CreateTenantRequest(
                "bank.example", "Big Bank", IsolationMode.DEDICATED, null));

        String expectedDb = "eip_tenant_" + created.id().toString().replace("-", "");
        assertThat(created.isolationMode()).isEqualTo(IsolationMode.DEDICATED);
        assertThat(created.arcadeDatabase()).isEqualTo(expectedDb);

        // Provisioning order matters: realm → DB → dataset → schema. Any
        // shuffle here would mean a partial failure leaves orphan resources
        // in a different state than the rollback expects.
        assertThat(keycloak.realmsEnsured).containsExactly("tenant-" + created.id());
        assertThat(arcade.databasesEnsured).containsExactly(expectedDb);
        assertThat(fuseki.datasetsEnsured).containsExactly(expectedDb);
        assertThat(schema.appliedTo).containsExactly(expectedDb);
    }

    @Test
    void createNormalizesDomainToLowercase() {
        Tenant created = service.create(new TenantDtos.CreateTenantRequest(
                "  ACME.Orphos.IO  ", "Acme", IsolationMode.SHARED, null));
        assertThat(created.domain()).isEqualTo("acme.orphos.io");
    }

    @Test
    void createDefaultsToDedicatedWhenIsolationMissing() {
        // TenantService.create() deliberately defaults a missing isolationMode to
        // DEDICATED (own ArcadeDB + Fuseki dataset); callers opt into SHARED.
        Tenant created = service.create(new TenantDtos.CreateTenantRequest(
                "acme.example", "Acme", null, null));
        assertThat(created.isolationMode()).isEqualTo(IsolationMode.DEDICATED);
    }

    // ── create(): conflict + rollback ─────────────────────────────────────

    @Test
    void createRejectsDuplicateDomainWithoutTouchingExternalSystems() {
        // Seed an existing tenant with the conflicting domain.
        tenants.preload(activeTenant(UUID.randomUUID(), "acme.example", IsolationMode.SHARED));

        assertThatThrownBy(() -> service.create(new TenantDtos.CreateTenantRequest(
                "acme.example", "Acme Two", IsolationMode.SHARED, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        // No external side effects on conflict — that's the contract.
        assertThat(keycloak.realmsEnsured).isEmpty();
        assertThat(arcade.databasesEnsured).isEmpty();
        assertThat(fuseki.datasetsEnsured).isEmpty();
        assertThat(tenants.inserted).isEmpty();
    }

    @Test
    void createRollsBackTenantRowWhenKeycloakFails() {
        keycloak.failNextEnsureRealmWith(new RuntimeException("Keycloak down"));

        assertThatThrownBy(() -> service.create(new TenantDtos.CreateTenantRequest(
                "acme.example", "Acme", IsolationMode.SHARED, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));

        // Row was inserted but immediately marked DELETED so the next retry
        // can re-create with the same domain (unique-index conflict path).
        assertThat(tenants.inserted).hasSize(1);
        UUID newId = tenants.inserted.get(0);
        assertThat(tenants.statusUpdates).containsExactly(
                new StatusUpdate(newId, TenantStatus.DELETED));
    }

    @Test
    void createRollsBackTenantRowWhenArcadeProvisioningFails() {
        arcade.failNextEnsureDatabaseWith(new RuntimeException("ArcadeDB unreachable"));

        assertThatThrownBy(() -> service.create(new TenantDtos.CreateTenantRequest(
                "bank.example", "Big Bank", IsolationMode.DEDICATED, null)))
                .isInstanceOf(ResponseStatusException.class);

        UUID newId = tenants.inserted.get(0);
        assertThat(tenants.statusUpdates).containsExactly(
                new StatusUpdate(newId, TenantStatus.DELETED));
        // Fuseki + schema were never reached — rollback short-circuited.
        assertThat(fuseki.datasetsEnsured).isEmpty();
        assertThat(schema.appliedTo).isEmpty();
    }

    @Test
    void createRollsBackTenantRowWhenFusekiProvisioningFails() {
        fuseki.failNextEnsureDatasetWith(new RuntimeException("Fuseki refused connection"));

        assertThatThrownBy(() -> service.create(new TenantDtos.CreateTenantRequest(
                "bank.example", "Big Bank", IsolationMode.DEDICATED, null)))
                .isInstanceOf(ResponseStatusException.class);

        UUID newId = tenants.inserted.get(0);
        // ArcadeDB succeeded but its database is left orphaned (acceptable
        // MVP trade-off — manual cleanup). Tenant row is still marked DELETED.
        assertThat(arcade.databasesEnsured).hasSize(1);
        assertThat(tenants.statusUpdates).containsExactly(
                new StatusUpdate(newId, TenantStatus.DELETED));
    }

    // ── hardDelete() ──────────────────────────────────────────────────────

    @Test
    void hardDeleteForDedicatedReleasesAllResources() {
        UUID id = UUID.randomUUID();
        Tenant t = new Tenant(id, "bank.example", "Big Bank",
                TenantStatus.ACTIVE, IsolationMode.DEDICATED,
                "eip_tenant_" + id.toString().replace("-", ""),
                null,
                "tenant-" + id,
                Instant.now(), Instant.now());
        tenants.preload(t);

        service.hardDelete(id, /* cascadeSharedData */ false);

        // Order: Fuseki dataset → ArcadeDB database → Keycloak realm → row.
        assertThat(fuseki.datasetsDropped).containsExactly(t.arcadeDatabase());
        assertThat(arcade.databasesDropped).containsExactly(t.arcadeDatabase());
        assertThat(keycloak.realmsDeleted).containsExactly(t.keycloakRealm());
        assertThat(tenants.hardDeleted).containsExactly(id);
    }

    @Test
    void hardDeleteForSharedWithoutCascadeKeepsRowData() {
        UUID id = UUID.randomUUID();
        Tenant t = activeTenant(id, "acme.example", IsolationMode.SHARED);
        tenants.preload(t);

        service.hardDelete(id, /* cascadeSharedData */ false);

        // SHARED row data is NOT wiped by default — auditable + recoverable.
        assertThat(arcadeClient.commandsRun).isEmpty();
        assertThat(fuseki.updatesExecuted).isEmpty();
        // External: Keycloak realm always released.
        assertThat(keycloak.realmsDeleted).containsExactly(t.keycloakRealm());
        // SHARED tenants don't own a dedicated DB to drop.
        assertThat(arcade.databasesDropped).isEmpty();
        assertThat(fuseki.datasetsDropped).isEmpty();
        // Tenant row hard-deleted.
        assertThat(tenants.hardDeleted).containsExactly(id);
    }

    @Test
    void hardDeleteForSharedWithCascadeWipesRowDataAndNamedGraph() {
        UUID id = UUID.randomUUID();
        Tenant t = activeTenant(id, "acme.example", IsolationMode.SHARED);
        tenants.preload(t);

        service.hardDelete(id, /* cascadeSharedData */ true);

        // 14 tenant-scoped doc types get DELETE FROM commands. We don't pin
        // the exact list here (it lives in the service for now); just assert
        // we ran some + that they all carry the right tenantId param.
        assertThat(arcadeClient.commandsRun).isNotEmpty();
        assertThat(arcadeClient.commandsRun).allSatisfy(cmd -> {
            assertThat(cmd.sql).startsWith("DELETE FROM ").contains("WHERE tenantId = :tenantId");
            assertThat(cmd.params.get("tenantId")).isEqualTo(id.toString());
        });
        // Named-graph drop in Fuseki, scoped to this tenant only.
        assertThat(fuseki.updatesExecuted).hasSize(1);
        assertThat(fuseki.updatesExecuted.get(0).sparql).isEqualTo(
                "DROP SILENT GRAPH <urn:eip:tenant:" + id + ">");
    }

    @Test
    void hardDeleteRefusesDemoTenant() {
        UUID demoId = UUID.randomUUID();
        Tenant demo = new Tenant(demoId, DEMO_DOMAIN, "Demo",
                TenantStatus.ACTIVE, IsolationMode.SHARED,
                "orphos", null, "eip",
                Instant.now(), Instant.now());
        tenants.preload(demo);

        assertThatThrownBy(() -> service.hardDelete(demoId, false))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        // Demo guardrail must trip BEFORE any external release.
        assertThat(keycloak.realmsDeleted).isEmpty();
        assertThat(arcade.databasesDropped).isEmpty();
        assertThat(fuseki.datasetsDropped).isEmpty();
        assertThat(tenants.hardDeleted).isEmpty();
    }

    @Test
    void hardDeleteAbsorbsPartialReleaseFailures() {
        UUID id = UUID.randomUUID();
        Tenant t = new Tenant(id, "bank.example", "Big Bank",
                TenantStatus.ACTIVE, IsolationMode.DEDICATED,
                "eip_tenant_x", null, "tenant-x",
                Instant.now(), Instant.now());
        tenants.preload(t);

        // Keycloak realm drop fails — must NOT abort the rest of the release.
        keycloak.failNextDeleteRealmWith(new RuntimeException("Keycloak unreachable"));

        service.hardDelete(id, false);

        // Row STILL gets hard-deleted even though one release failed.
        // (Orphan resources are an MVP trade-off; the row deletion ensures
        //  the platform's view of "tenant exists" is consistent.)
        assertThat(tenants.hardDeleted).containsExactly(id);
        // Fuseki + ArcadeDB drops happened before the Keycloak failure — log.
        assertThat(fuseki.datasetsDropped).hasSize(1);
        assertThat(arcade.databasesDropped).hasSize(1);
    }

    @Test
    void hardDeleteThrowsWhenTenantNotFound() {
        assertThatThrownBy(() -> service.hardDelete(UUID.randomUUID(), false))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static Tenant activeTenant(UUID id, String domain, IsolationMode mode) {
        String db = mode == IsolationMode.DEDICATED
                ? "eip_tenant_" + id.toString().replace("-", "")
                : "orphos";
        return new Tenant(id, domain, "Test Tenant",
                TenantStatus.ACTIVE, mode,
                db, null, "tenant-" + id,
                Instant.now(), Instant.now());
    }

    // ── Recording stubs (no Mockito per project convention) ───────────────

    /** Records inserts/updates/deletes and serves preloaded tenants for findBy lookups. */
    static class RecordingRepository extends TenantRepository {
        final List<UUID> inserted = new ArrayList<>();
        final List<StatusUpdate> statusUpdates = new ArrayList<>();
        final List<UUID> hardDeleted = new ArrayList<>();
        final Map<UUID, Tenant> store = new HashMap<>();
        final Map<String, Tenant> byDomain = new HashMap<>();

        RecordingRepository() { super(null); }

        void preload(Tenant t) {
            store.put(t.id(), t);
            byDomain.put(t.domain(), t);
        }

        @Override public Tenant insert(Tenant t) {
            inserted.add(t.id());
            preload(t);
            return t;
        }
        @Override public Optional<Tenant> findById(UUID id) {
            Tenant t = store.get(id);
            return (t != null && t.status() != TenantStatus.DELETED) ? Optional.of(t) : Optional.empty();
        }
        @Override public Optional<Tenant> findByIdIncludingDeleted(UUID id) {
            return Optional.ofNullable(store.get(id));
        }
        @Override public Optional<Tenant> findByDomain(String domain) {
            Tenant t = byDomain.get(domain);
            return (t != null && t.status() != TenantStatus.DELETED) ? Optional.of(t) : Optional.empty();
        }
        @Override public List<Tenant> listActive() { return new ArrayList<>(store.values()); }
        @Override public void updateStatus(UUID id, TenantStatus s) {
            statusUpdates.add(new StatusUpdate(id, s));
            Tenant prev = store.get(id);
            if (prev != null) store.put(id, withStatus(prev, s));
        }
        @Override public void updateDisplayName(UUID id, String name) { /* not exercised here */ }
        @Override public void updateDomain(UUID id, String newDomain) { /* not exercised here */ }
        @Override public void hardDelete(UUID id) {
            hardDeleted.add(id);
            store.remove(id);
        }

        private static Tenant withStatus(Tenant t, TenantStatus s) {
            return new Tenant(t.id(), t.domain(), t.displayName(), s, t.isolationMode(),
                    t.arcadeDatabase(), t.arcadeConnectionId(), t.keycloakRealm(),
                    t.createdAt(), Instant.now());
        }
    }

    record StatusUpdate(UUID id, TenantStatus status) {}

    static class RecordingLookupCache extends TenantLookupServiceImpl {
        final List<EvictKey> evictions = new ArrayList<>();
        RecordingLookupCache() { super(null); }
        @Override public void evict(UUID tenantId, String domain) {
            evictions.add(new EvictKey(tenantId, domain));
        }
        record EvictKey(UUID id, String domain) {}
    }

    static class RecordingKeycloak extends KeycloakProvisioningClient {
        final List<String> realmsEnsured = new ArrayList<>();
        final List<String> realmsDeleted = new ArrayList<>();
        private RuntimeException nextEnsureFailure;
        private RuntimeException nextDeleteFailure;

        RecordingKeycloak() {
            super("http://noop", "admin", "noop", "admin-cli", "", "",
                    "azure", "Sign in with Microsoft", "", "", "", "");
        }

        void failNextEnsureRealmWith(RuntimeException e) { this.nextEnsureFailure = e; }
        void failNextDeleteRealmWith(RuntimeException e) { this.nextDeleteFailure = e; }

        @Override public synchronized boolean ensureRealm(String name, String display, String tenantId) {
            if (nextEnsureFailure != null) {
                RuntimeException e = nextEnsureFailure;
                nextEnsureFailure = null;
                throw e;
            }
            realmsEnsured.add(name);
            return true;
        }
        @Override public synchronized void deleteRealm(String name) {
            if (nextDeleteFailure != null) {
                RuntimeException e = nextDeleteFailure;
                nextDeleteFailure = null;
                throw e;
            }
            realmsDeleted.add(name);
        }
        // ensureRealmHasTenantScope: not exercised by these tests; default no-op
        @Override public synchronized void ensureRealmHasTenantScope(String name, String tenantId) { /* no-op */ }
    }

    static class RecordingArcade extends ArcadeProvisioningClient {
        final List<String> databasesEnsured = new ArrayList<>();
        final List<String> databasesDropped = new ArrayList<>();
        private RuntimeException nextEnsureFailure;

        void failNextEnsureDatabaseWith(RuntimeException e) { this.nextEnsureFailure = e; }

        @Override public boolean ensureDatabase(String connectionId, String name) {
            if (nextEnsureFailure != null) {
                RuntimeException e = nextEnsureFailure;
                nextEnsureFailure = null;
                throw e;
            }
            databasesEnsured.add(name);
            return true;
        }
        @Override public void dropDatabase(String connectionId, String name) {
            databasesDropped.add(name);
        }
    }

    static class RecordingFuseki extends FusekiProvisioningClient {
        final List<String> datasetsEnsured = new ArrayList<>();
        final List<String> datasetsDropped = new ArrayList<>();
        final List<UpdateCall> updatesExecuted = new ArrayList<>();
        private RuntimeException nextEnsureFailure;

        void failNextEnsureDatasetWith(RuntimeException e) { this.nextEnsureFailure = e; }

        @Override public boolean ensureDataset(String name) {
            if (nextEnsureFailure != null) {
                RuntimeException e = nextEnsureFailure;
                nextEnsureFailure = null;
                throw e;
            }
            datasetsEnsured.add(name);
            return true;
        }
        @Override public void dropDataset(String name) { datasetsDropped.add(name); }
        @Override public void executeUpdate(String dataset, String sparql) {
            updatesExecuted.add(new UpdateCall(dataset, sparql));
        }
        record UpdateCall(String dataset, String sparql) {}
    }

    static class RecordingSchema extends SchemaBootstrap {
        final List<String> appliedTo = new ArrayList<>();
        RecordingSchema() { super(null, null); }
        @Override public void applyTo(String connectionId, String dbName) { appliedTo.add(dbName); }
    }

    static class RecordingArcadeClient extends ArcadeDbTemplate {
        final List<CommandCall> commandsRun = new ArrayList<>();
        RecordingArcadeClient() { super(new ArcadeDbProperties()); }
        @Override public void commandNoResult(String sql, Map<String, Object> params) {
            commandsRun.add(new CommandCall(sql, new HashMap<>(params)));
        }
        @Override public List<Map<String, Object>> query(String sql, Map<String, Object> p) { return List.of(); }
        @Override public List<Map<String, Object>> command(String sql, Map<String, Object> p) { return List.of(); }
        record CommandCall(String sql, Map<String, Object> params) {}
    }
}
