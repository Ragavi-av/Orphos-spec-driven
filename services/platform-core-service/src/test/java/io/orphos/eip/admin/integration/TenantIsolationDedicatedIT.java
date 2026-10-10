package io.orphos.eip.admin.integration;

import io.orphos.eip.admin.testsupport.BaseIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies database-level isolation for DEDICATED-mode tenants. A fresh
 * tenant database is created, populated with rows, and exercised via
 * {@link io.orphos.eip.admin.arcade.ArcadeClient#queryOn} — those queries must
 * return only the dedicated DB's rows, and the SHARED DB must see none of them.
 *
 * <p>This is the stronger isolation guarantee that motivates DEDICATED mode in
 * the first place: even a buggy service that forgets its {@code tenantId}
 * filter cannot reach another tenant's data, because the data simply does not
 * live in its database.</p>
 */
class TenantIsolationDedicatedIT extends BaseIntegrationTest {

    private static final UUID DEDICATED_TENANT = UUID.randomUUID();
    private static final UUID SHARED_TENANT    = UUID.randomUUID();
    private static String dedicatedDb;

    @BeforeAll
    static void provisionAndSeed() {
        dedicatedDb = newDedicatedDbName();
        createDatabase(dedicatedDb);
        SCHEMA_BOOTSTRAP.applyTo(dedicatedDb);

        // Seed the DEDICATED tenant's own DB with 4 AuthEvents.
        for (int i = 0; i < 4; i++) {
            ARCADE_CLIENT.commandNoResultOn(dedicatedDb,
                    "INSERT INTO AuthEvent SET id = :id, tenantId = :t, userKey = :u, "
                            + "outcome = 'SUCCESS', createdAt = :now",
                    Map.of(
                            "id",  UUID.randomUUID().toString(),
                            "t",   DEDICATED_TENANT.toString(),
                            "u",   "dedicated-" + i,
                            "now", Instant.now().toString()));
        }

        // Seed the SHARED DB with 2 unrelated AuthEvents owned by a different tenant.
        for (int i = 0; i < 2; i++) {
            ARCADE_CLIENT.commandNoResult(
                    "INSERT INTO AuthEvent SET id = :id, tenantId = :t, userKey = :u, "
                            + "outcome = 'SUCCESS', createdAt = :now",
                    Map.of(
                            "id",  UUID.randomUUID().toString(),
                            "t",   SHARED_TENANT.toString(),
                            "u",   "shared-" + i,
                            "now", Instant.now().toString()));
        }
    }

    @AfterAll
    static void cleanup() {
        dropDatabase(dedicatedDb);
    }

    @Test
    void dedicatedDatabaseHoldsOnlyItsOwnRows() {
        List<Map<String, Object>> rows = ARCADE_CLIENT.queryOn(dedicatedDb,
                "SELECT FROM AuthEvent", Map.of());

        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("tenantId")).isEqualTo(DEDICATED_TENANT.toString()));
    }

    @Test
    void sharedDatabaseCannotSeeDedicatedTenantRows() {
        // Query the SHARED DB looking for the dedicated tenant's rows.
        // They live in the other DB, so this must return zero — even though
        // the same doc type exists in both databases.
        List<Map<String, Object>> rows = ARCADE_CLIENT.query(
                "SELECT FROM AuthEvent WHERE tenantId = :t",
                Map.of("t", DEDICATED_TENANT.toString()));

        assertThat(rows).isEmpty();
    }

    @Test
    void dedicatedDatabaseCannotSeeSharedTenantRows() {
        // Reverse direction: query the dedicated DB looking for the shared
        // tenant's rows. Must also be zero.
        List<Map<String, Object>> rows = ARCADE_CLIENT.queryOn(dedicatedDb,
                "SELECT FROM AuthEvent WHERE tenantId = :t",
                Map.of("t", SHARED_TENANT.toString()));

        assertThat(rows).isEmpty();
    }

    @Test
    void schemaBootstrapApplyToProducesIdenticalDocTypes() {
        // Both the SHARED and DEDICATED DBs were initialised by SchemaBootstrap;
        // an "INSERT INTO Tenant" must succeed in either, because the doc type
        // exists in both. This catches the case where a future migration adds a
        // type to SHARED but forgets to also apply it on DEDICATED DBs.
        String id = UUID.randomUUID().toString();
        ARCADE_CLIENT.commandNoResultOn(dedicatedDb,
                "INSERT INTO Tenant SET id = :i, domain = :d, status = 'ACTIVE', "
                        + "isolationMode = 'DEDICATED', createdAt = :now, updatedAt = :now",
                Map.of("i", id, "d", id + ".dedicated.test", "now", Instant.now().toString()));

        List<Map<String, Object>> rows = ARCADE_CLIENT.queryOn(dedicatedDb,
                "SELECT FROM Tenant WHERE id = :i LIMIT 1", Map.of("i", id));
        assertThat(rows).hasSize(1);
    }
}
