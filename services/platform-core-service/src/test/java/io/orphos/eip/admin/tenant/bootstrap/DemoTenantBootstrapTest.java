package io.orphos.eip.admin.tenant.bootstrap;

import io.orphos.eip.admin.tenant.fuseki.FusekiProvisioningClient;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.core.arcadedb.ArcadeDbProperties;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code eip.tenant.demo.seed-sample-data} gate: the demo tenant row
 * is always ensured, but the sample-data steps (tenantId backfill + Fuseki graph
 * migration) run only when the flag is {@code true}.
 *
 * <p>Uses hand-rolled recording doubles, not Mockito — matching this module's
 * test convention (Mockito's inline mock maker can't instrument these classes
 * under the current JDK).</p>
 */
class DemoTenantBootstrapTest {

    private static final String DEMO_ID = "127a2ec3-cb19-365e-9ebe-c9c19ed5e5d8";

    private DemoTenantBootstrap bootstrap(RecArcade arcade, RecTenants tenants,
                                          RecFuseki fuseki, boolean seedSampleData) {
        DemoTenantBootstrap b = new DemoTenantBootstrap(arcade, tenants, new RecKeycloak(), fuseki);
        ReflectionTestUtils.setField(b, "demoIdStr", DEMO_ID);
        ReflectionTestUtils.setField(b, "demoDomain", "acme.internal");
        ReflectionTestUtils.setField(b, "demoDisplayName", "Acme");
        ReflectionTestUtils.setField(b, "demoRealm", "eip");
        ReflectionTestUtils.setField(b, "demoDatabase", "orphos");
        ReflectionTestUtils.setField(b, "enabled", true);
        ReflectionTestUtils.setField(b, "patchRealm", false);   // keep Keycloak out of the test
        ReflectionTestUtils.setField(b, "migrateFuseki", true); // gated by seedSampleData anyway
        ReflectionTestUtils.setField(b, "seedSampleData", seedSampleData);
        return b;
    }

    @Test
    void cleanTenantMode_createsTenantButSkipsSampleData() {
        RecArcade arcade = new RecArcade();
        RecTenants tenants = new RecTenants();
        RecFuseki fuseki = new RecFuseki();

        bootstrap(arcade, tenants, fuseki, false).run(null);

        // The tenant is still created…
        assertThat(tenants.inserts).isEqualTo(1);
        // …but no sample-data work happens.
        assertThat(arcade.commandCalls).isZero();
        assertThat(fuseki.updates).isZero();
    }

    @Test
    void sampleDataMode_backfillsAndMigrates() {
        RecArcade arcade = new RecArcade();
        RecTenants tenants = new RecTenants();
        RecFuseki fuseki = new RecFuseki();

        bootstrap(arcade, tenants, fuseki, true).run(null);

        assertThat(tenants.inserts).isEqualTo(1);
        assertThat(arcade.commandCalls).isGreaterThan(0);   // one UPDATE per tenant-scoped doc type
        assertThat(fuseki.updates).isEqualTo(1);            // default graph → demo named graph
    }

    @Test
    void existingTenant_reconcilesDisplayNameToInstanceName() {
        RecArcade arcade = new RecArcade();
        RecTenants tenants = new RecTenants();
        RecFuseki fuseki = new RecFuseki();
        // A pre-existing demo row with the old default name.
        tenants.preloaded = new Tenant(UUID.fromString(DEMO_ID), "acme.internal", "Demo Tenant",
                io.orphos.eip.admin.tenant.model.TenantStatus.ACTIVE,
                io.orphos.eip.admin.tenant.model.IsolationMode.SHARED,
                "orphos", null, "eip", java.time.Instant.now(), java.time.Instant.now());

        bootstrap(arcade, tenants, fuseki, false).run(null);

        // No insert (row exists) but the display name is reconciled to the new instance name.
        assertThat(tenants.inserts).isZero();
        assertThat(tenants.displayNameUpdates).containsExactly("Acme");
    }

    // ── recording doubles ────────────────────────────────────────────────────

    static class RecArcade extends ArcadeDbTemplate {
        int commandCalls = 0;
        RecArcade() { super(new ArcadeDbProperties()); }
        @Override public List<Map<String, Object>> command(String sql, Map<String, Object> p) {
            commandCalls++;
            return List.of();
        }
    }

    static class RecTenants extends TenantRepository {
        int inserts = 0;
        Tenant preloaded;                 // when set, the demo row "already exists"
        final List<String> displayNameUpdates = new java.util.ArrayList<>();
        RecTenants() { super(null); }
        @Override public Optional<Tenant> findByIdIncludingDeleted(UUID id) {
            return Optional.ofNullable(preloaded);
        }
        @Override public Tenant insert(Tenant t) { inserts++; return t; }
        @Override public void updateDisplayName(UUID id, String name) { displayNameUpdates.add(name); }
    }

    static class RecFuseki extends FusekiProvisioningClient {
        int updates = 0;
        @Override public void executeUpdate(String dataset, String sparql) { updates++; }
    }

    static class RecKeycloak extends KeycloakProvisioningClient {
        RecKeycloak() {
            super("http://noop", "admin", "noop", "admin-cli", "", "",
                    "azure", "Sign in with Microsoft", "", "", "", "");
        }
    }
}
